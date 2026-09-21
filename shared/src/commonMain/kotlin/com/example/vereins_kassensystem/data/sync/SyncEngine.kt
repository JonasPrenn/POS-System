package com.example.vereins_kassensystem.data.sync

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.example.vereins_kassensystem.data.AppDatabase
import com.example.vereins_kassensystem.data.SettingsRepository
import com.example.vereins_kassensystem.data.entity.PendingChange
import com.example.vereins_kassensystem.data.entity.SyncState
import com.example.vereins_kassensystem.data.repository.AppRepository
import com.example.vereins_kassensystem.platform.Ids
import com.example.vereins_kassensystem.platform.Platform
import com.example.vereins_kassensystem.platform.PlatformKind
import com.example.vereins_kassensystem.platform.nowMillis
import com.example.vereins_kassensystem.platform.readPhotoBytes
import com.example.vereins_kassensystem.platform.storeDownloadedPhoto
import com.example.vereins_kassensystem.sync.PushOperation
import com.example.vereins_kassensystem.sync.PushResponse
import com.example.vereins_kassensystem.sync.PushStatus
import com.example.vereins_kassensystem.sync.SyncApi
import com.example.vereins_kassensystem.sync.SyncClient
import com.example.vereins_kassensystem.sync.SyncHttpException
import com.example.vereins_kassensystem.sync.WireJson
import com.example.vereins_kassensystem.sync.installSyncDefaults
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Was der Abgleich gerade nicht kann — in Worten, die am Tresen etwas bedeuten. */
sealed interface SyncProblem {
    val message: String

    /** Kein Netz, Server aus, Zeitüberschreitung. Die Warteschlange bleibt, es geht von selbst weiter. */
    data object Offline : SyncProblem {
        override val message = "Server nicht erreichbar"
    }

    /** 401: Das Token gilt nicht mehr, etwa weil das Gerät am Server gesperrt wurde. */
    data object NeedsPairing : SyncProblem {
        override val message = "Gerät ist am Server nicht mehr angemeldet"
    }

    /** 422: Der Server hält eine Änderung für nicht anwendbar. Ein Programmfehler, kein Betriebsfall. */
    data class Rejected(val detail: String) : SyncProblem {
        override val message = "Der Server hat eine Änderung abgelehnt"
    }

    data class Other(override val message: String) : SyncProblem
}

/**
 * Was die Oberfläche über den Abgleich wissen muss (Spezifikation 4.4): Ein Kassier, der
 * nicht weiß, dass sein Gerät seit einer Stunde allein arbeitet, trifft falsche
 * Entscheidungen.
 */
data class SyncStatus(
    val paired: Boolean = false,
    val deviceLabel: String? = null,
    val serverUrl: String? = null,
    /** Buchungen und Änderungen, die der Server noch nicht hat. */
    val pending: Int = 0,
    val lastSyncAt: Long? = null,
    val running: Boolean = false,
    val problem: SyncProblem? = null,
)

/** Wie eine Kopplung ausging. */
sealed interface PairingResult {
    /** Der Server war leer: Dieses Gerät ist die Quelle, [rows] Zeilen gehen als Erstbefüllung hoch. */
    data class Source(val rows: Int) : PairingResult

    /** Das Gerät war leer und übernimmt, was der Server hat. */
    data object Joined : PairingResult

    /**
     * Server **und** Gerät haben Daten. Zusammenführen würde Dubletten ergeben, die nur von
     * Hand zu bereinigen sind (Spezifikation 2.5) — also entscheidet ein Mensch:
     * [SyncEngine.adoptServerState] oder [SyncEngine.unpair].
     */
    data object NeedsDecision : PairingResult

    data class Failed(val message: String) : PairingResult
}

/**
 * Der Abgleich aus Kapitel 4: schieben, was in der Warteschlange liegt, dann ziehen, was
 * der Server Neues hat. Beides stößt das Gerät an; der Server ruft nie von sich aus an.
 *
 * Der Verkauf wartet nie auf diese Klasse. Geschrieben wird lokal, und was hier scheitert,
 * bleibt in der Warteschlange und wird später wieder versucht — ohne Meldung im
 * Verkaufsweg, nur mit dem Hinweis im Status.
 */
class SyncEngine(
    private val database: AppDatabase,
    private val repository: AppRepository,
    private val settings: SettingsRepository,
    private val platform: Platform,
    private val scope: CoroutineScope,
    private val apiFactory: (baseUrl: String, token: suspend () -> String?) -> SyncApi = { url, token -> SyncClient(sharedHttpClient, url, token) },
) {
    private val syncDao = database.syncDao()
    private val applier = SyncApplier(database)

    private val running = MutableStateFlow(false)
    private val problem = MutableStateFlow<SyncProblem?>(null)
    private val cycle = Mutex()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private var started = false

    val status: StateFlow<SyncStatus> = combine(
        combine(
            syncDao.observeState(SyncKeys.ENABLED),
            syncDao.observeState(SyncKeys.DEVICE_LABEL),
            syncDao.observeState(SyncKeys.LAST_SYNC_AT),
            settings.apiBaseUrl,
        ) { enabled, label, lastSync, url -> SyncStatus(paired = enabled == "1", deviceLabel = label, serverUrl = url, lastSyncAt = lastSync?.toLongOrNull()) },
        syncDao.observePendingCount(),
        running,
        problem,
    ) { base, pending, isRunning, currentProblem ->
        base.copy(pending = pending, running = isRunning, problem = currentProblem)
    }.stateIn(scope, SharingStarted.Eagerly, SyncStatus())

    // ---------------------------------------------------------------- Takt

    /**
     * Startet die Schleife, einmal für die Lebenszeit der App: sofort nach jeder Änderung
     * der Warteschlange, alle 60 Sekunden, auf Zuruf — und bei offener Warteschlange nach
     * einem Fehlschlag mit wachsendem Abstand (2 s, 4 s, 8 s, dann jede Minute).
     */
    fun start() {
        if (started) return
        started = true
        scope.launch { repository.outboxSignals.collect { requestSync() } }
        scope.launch {
            var failures = 0
            requestSync()
            while (true) {
                val wait = if (failures > 0 && syncDao.pendingCount() > 0) backoffMillis(failures) else PERIOD_MILLIS
                withTimeoutOrNull(wait) { wakeups.receive() }
                if (!isPaired()) {
                    failures = 0
                    continue
                }
                failures = if (syncOnce()) 0 else failures + 1
            }
        }
    }

    /** Beim Wechsel in den Vordergrund, nach einem Kassiervorgang, oder weil jemand „Jetzt abgleichen" tippt. */
    fun requestSync() {
        wakeups.trySend(Unit)
    }

    private fun backoffMillis(failures: Int): Long = when (failures) {
        1 -> 2_000L
        2 -> 4_000L
        3 -> 8_000L
        else -> PERIOD_MILLIS
    }

    // ------------------------------------------------------------ ein Durchlauf

    /** Ein vollständiger Abgleich. True, wenn er durchlief; false, wenn später wieder versucht wird. */
    suspend fun syncOnce(): Boolean = cycle.withLock {
        val api = currentApi() ?: return@withLock false
        running.value = true
        try {
            var rejected: SyncProblem.Rejected? = null
            uploadReceiptPhotos(api)
            pushAll(api) { rejected = it }
            pullAll(api)
            fetchReceiptPhotos(api)
            putState(SyncKeys.LAST_SYNC_AT, nowMillis().toString())
            problem.value = rejected
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncHttpException) {
            problem.value = when (e.status) {
                401 -> SyncProblem.NeedsPairing
                429, in 500..599 -> SyncProblem.Offline
                else -> SyncProblem.Other("Server antwortet mit ${e.status}: ${e.message}")
            }
            false
        } catch (e: Exception) {
            // Alles, was unterhalb von HTTP schiefgeht: kein Netz, Zeitüberschreitung, TLS.
            problem.value = SyncProblem.Offline
            false
        } finally {
            running.value = false
        }
    }

    // ------------------------------------------------------------ 4.2 Schieben

    private suspend fun pushAll(api: SyncApi, onRejected: (SyncProblem.Rejected) -> Unit) {
        while (true) {
            val batch = syncDao.nextBatch(PUSH_BATCH)
            if (batch.isEmpty()) return
            try {
                settle(batch, api.push(batch.map(::operationOf)))
            } catch (e: SyncHttpException) {
                if (e.status != 422) throw e
                // Der Server nimmt alle Operationen eines Aufrufs oder keine. Eine einzige
                // nicht anwendbare würde die Warteschlange für immer verstopfen — also
                // einzeln nachschieben und nur die eine aussortieren.
                pushOneByOne(api, batch, onRejected)
            }
        }
    }

    private suspend fun pushOneByOne(api: SyncApi, batch: List<PendingChange>, onRejected: (SyncProblem.Rejected) -> Unit) {
        for (change in batch) {
            try {
                settle(listOf(change), api.push(listOf(operationOf(change))))
            } catch (e: SyncHttpException) {
                if (e.status != 422) throw e
                val detail = "${change.entity} ${change.op} ${change.entityId}: ${e.message}"
                write {
                    syncDao.putState(SyncState(SyncKeys.LAST_REJECTED, detail))
                    syncDao.removeUpTo(change.seq)
                }
                onRejected(SyncProblem.Rejected(detail))
            }
        }
    }

    /**
     * Verbucht die Antwort: Wo der Server eine neuere Fassung hatte (`ignored_stale`),
     * übernimmt das Gerät dessen Stand; danach verlassen die Aufträge die Warteschlange.
     * Beides in einer Transaktion, damit ein Absturz dazwischen nichts doppelt schickt, was
     * ohnehin harmlos wäre, und nichts verliert, was es nicht wäre.
     */
    private suspend fun settle(batch: List<PendingChange>, response: PushResponse) = write {
        val byChangeId = batch.associateBy { it.changeId }
        for (result in response.results) {
            val current = result.current ?: continue
            if (result.status != PushStatus.IGNORED_STALE) continue
            val change = byChangeId[result.clientChangeId] ?: continue
            runCatching { applier.apply(change.entity, current, deleted = current["deleted_at"].isPresent()) }
        }
        syncDao.removeUpTo(batch.last().seq)
    }

    private fun operationOf(change: PendingChange) = PushOperation(
        clientChangeId = change.changeId,
        entity = change.entity,
        op = change.op,
        baseUpdatedAt = change.baseUpdatedAt,
        row = WireJson.parseToJsonElement(change.payload).jsonObject,
    )

    // -------------------------------------------------------------- 4.1 Ziehen

    /**
     * Seite um Seite, jede in einer Transaktion mit ihrem Lesezeiger: Bricht die Verbindung
     * mittendrin ab, kommt dieselbe Seite beim nächsten Mal wieder — die Zeilen sind
     * dieselben, das Ergebnis also auch.
     */
    private suspend fun pullAll(api: SyncApi) {
        var since = syncDao.state(SyncKeys.SINCE)?.toLongOrNull() ?: 0L
        do {
            val page = api.changes(since, PULL_PAGE)
            write {
                for (change in page.changes) {
                    // Eine unlesbare Zeile hält den Strom nicht auf; sie ist ein Programmfehler
                    // auf einer der beiden Seiten und käme sonst bei jedem Versuch wieder.
                    runCatching { applier.apply(change.entity, change.row, change.deleted) }
                }
                syncDao.putState(SyncState(SyncKeys.SINCE, page.nextSince.toString()))
            }
            since = page.nextSince
        } while (page.hasMore)
    }

    // ------------------------------------------------------------ 5.5 Belegfotos

    /** Fotos, die nur hier liegen, gehen hoch; der Schlüssel wird zur gewöhnlichen Änderung am Beleg. */
    private suspend fun uploadReceiptPhotos(api: SyncApi) {
        for (delivery in repository.deliveriesAwaitingUpload()) {
            val bytes = delivery.photoUri?.let { readPhotoBytes(it) } ?: continue
            val key = api.uploadReceipt(bytes, imageTypeOf(bytes))
            repository.setDeliveryPhotoKey(delivery.id, key)
        }
    }

    /** Fotos, die ein anderes Gerät aufgenommen hat, kommen herunter — einmal, dann liegen sie lokal. */
    private suspend fun fetchReceiptPhotos(api: SyncApi) {
        for (delivery in syncDao.allDeliveries()) {
            val key = delivery.photoKey ?: continue
            if (delivery.photoUri != null || delivery.sync.deleted) continue
            val bytes = runCatching { api.downloadReceipt(key) }.getOrNull() ?: continue
            val ref = storeDownloadedPhoto(key, bytes) ?: continue
            write { syncDao.upsertDelivery(delivery.copy(photoUri = ref)) }
        }
    }

    private fun imageTypeOf(bytes: ByteArray): ContentType = when {
        bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> ContentType.Image.PNG
        bytes.size > 11 && bytes.decodeToString(8, 12) == "WEBP" -> ContentType.parse("image/webp")
        bytes.size > 11 && bytes.decodeToString(4, 8) == "ftyp" -> ContentType.parse("image/heic")
        else -> ContentType.Image.JPEG
    }

    // --------------------------------------------------------------- Kopplung

    /** Prüft eine eingetippte Adresse, ohne etwas zu verändern. Null heißt: erreichbar und in Ordnung. */
    suspend fun checkServer(url: String): String? {
        val normalized = normalizeUrl(url) ?: return "Die Adresse muss mit https:// beginnen."
        return try {
            val health = apiFactory(normalized) { null }.health()
            if (health.status == "ok") null else "Der Server meldet: ${health.status}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Unter dieser Adresse antwortet kein VereinsDeckel-Server."
        }
    }

    /**
     * Koppelt das Gerät (Spezifikation 5.2) und entscheidet, wer wessen Bestand übernimmt
     * (2.5): Ist der Server leer, lädt dieses Gerät seinen Bestand hoch; ist das Gerät leer,
     * zieht es alles; haben beide Daten, passiert nichts ohne ausdrückliche Entscheidung.
     */
    suspend fun pair(url: String, pairingCode: String, label: String): PairingResult {
        val normalized = normalizeUrl(url) ?: return PairingResult.Failed("Die Adresse muss mit https:// beginnen.")
        val cleanLabel = label.trim().ifEmpty { platform.description }
        return try {
            val registration = apiFactory(normalized) { null }
                .register(pairingCode.trim(), cleanLabel, if (platform.kind == PlatformKind.IOS) "ios" else "android")
            settings.setDeviceToken(registration.token)
            settings.setApiBaseUrl(normalized)
            write {
                syncDao.putState(SyncState(SyncKeys.DEVICE_ID, registration.deviceId))
                syncDao.putState(SyncState(SyncKeys.DEVICE_LABEL, cleanLabel))
            }
            problem.value = null

            val serverHasData = apiFactory(normalized) { registration.token }.changes(since = 0, limit = 1).changes.isNotEmpty()
            val localRows = localRowCount()
            when {
                !serverHasData -> PairingResult.Source(becomeSource())
                localRows == 0 -> {
                    enable()
                    PairingResult.Joined
                }
                else -> PairingResult.NeedsDecision
            }.also { requestSync() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncHttpException) {
            PairingResult.Failed(
                when (e.status) {
                    409 -> "Der Kopplungscode ist unbekannt, abgelaufen oder schon benutzt."
                    429 -> "Zu viele Versuche. Bitte eine Minute warten."
                    else -> "Der Server antwortet mit ${e.status}: ${e.message}"
                }
            )
        } catch (e: Exception) {
            PairingResult.Failed("Server nicht erreichbar.")
        }
    }

    /**
     * Verwirft, was auf diesem Gerät liegt, und übernimmt den Stand des Servers. Der Aufrufer
     * sichert vorher — diese Funktion fragt nicht noch einmal nach.
     */
    suspend fun adoptServerState() {
        write {
            wipeAllTables()
            syncDao.clearQueue()
            syncDao.putState(SyncState(SyncKeys.SINCE, "0"))
            syncDao.putState(SyncState(SyncKeys.ENABLED, "1"))
        }
        requestSync()
    }

    /** Löst die Kopplung. Die Daten bleiben; was noch in der Warteschlange lag, geht nicht mehr hoch. */
    suspend fun unpair() {
        write {
            syncDao.clearQueue()
            syncDao.clearState()
        }
        settings.setDeviceToken(null)
        problem.value = null
    }

    /**
     * Macht dieses Gerät zur Quelle: alle Zeilen aller Tabellen als Einfügen in die
     * Warteschlange, Eltern vor Kindern, und im selben Zug die Kopplung scharf. Wer danach
     * verkauft, reiht sich dahinter ein; wer davor verkauft hat, ist in der Erstbefüllung.
     */
    private suspend fun becomeSource(): Int = write {
        var rows = 0
        suspend fun upload(entity: String, id: String, deleted: Boolean, row: JsonObject) {
            syncDao.enqueue(PendingChange(changeId = Ids.new(), entity = entity, entityId = id, op = "insert", payload = row.toString()))
            // Weich Gelöschtes geht mit: Buchungen verweisen darauf, und der Server prüft Verweise.
            if (deleted) {
                val tombstone = buildJsonObject { put("id", id) }
                syncDao.enqueue(PendingChange(changeId = Ids.new(), entity = entity, entityId = id, op = "delete", payload = tombstone.toString()))
            }
            rows++
        }
        syncDao.allCategories().forEach { upload(SyncTables.CATEGORIES, it.id, it.sync.deleted, RowCodec.encode(it)) }
        syncDao.allMembers().forEach { upload(SyncTables.MEMBERS, it.id, it.sync.deleted, RowCodec.encode(it)) }
        syncDao.allProducts().forEach { upload(SyncTables.PRODUCTS, it.id, it.sync.deleted, RowCodec.encode(it)) }
        syncDao.allVariants().forEach { upload(SyncTables.VARIANTS, it.id, it.sync.deleted, RowCodec.encode(it)) }
        syncDao.allStockItems().forEach { upload(SyncTables.STOCK_ITEMS, it.id, it.sync.deleted, RowCodec.encode(it)) }
        syncDao.allContainerTypes().forEach { upload(SyncTables.CONTAINER_TYPES, it.id, it.sync.deleted, RowCodec.encode(it)) }
        syncDao.allComponents().forEach { upload(SyncTables.COMPONENTS, it.id, it.sync.deleted, RowCodec.encode(it)) }
        syncDao.allDeliveries().forEach { upload(SyncTables.DELIVERIES, it.id, it.sync.deleted, RowCodec.encode(it)) }
        syncDao.allStockEntries().forEach { upload(SyncTables.STOCK_ENTRIES, it.id, false, RowCodec.encode(it)) }
        syncDao.allTapped().forEach { upload(SyncTables.TAPPED, it.id, it.sync.deleted, RowCodec.encode(it)) }
        syncDao.allTransactions().forEach { upload(SyncTables.TRANSACTIONS, it.id, false, RowCodec.encode(it)) }
        syncDao.allStockDraws().forEach { upload(SyncTables.STOCK_DRAWS, it.id, false, RowCodec.encode(it)) }

        syncDao.putState(SyncState(SyncKeys.SINCE, "0"))
        syncDao.putState(SyncState(SyncKeys.ENABLED, "1"))
        rows
    }

    private suspend fun enable() = write {
        syncDao.putState(SyncState(SyncKeys.SINCE, "0"))
        syncDao.putState(SyncState(SyncKeys.ENABLED, "1"))
    }

    private suspend fun localRowCount(): Int =
        syncDao.allCategories().size + syncDao.allMembers().size + syncDao.allProducts().size +
            syncDao.allStockItems().size + syncDao.allTransactions().size + syncDao.allStockEntries().size

    private suspend fun wipeAllTables() {
        syncDao.wipeStockDraws()
        syncDao.wipeTransactions()
        syncDao.wipeTapped()
        syncDao.wipeStockEntries()
        syncDao.wipeDeliveries()
        syncDao.wipeComponents()
        syncDao.wipeContainerTypes()
        syncDao.wipeStockItems()
        syncDao.wipeVariants()
        syncDao.wipeProducts()
        syncDao.wipeMembers()
        syncDao.wipeCategories()
    }

    // ---------------------------------------------------------------- Helfer

    private suspend fun isPaired(): Boolean = syncDao.state(SyncKeys.ENABLED) == "1"

    private suspend fun currentApi(): SyncApi? {
        if (!isPaired()) return null
        val url = settings.apiBaseUrl.first() ?: return null
        return apiFactory(url) { settings.deviceToken() }
    }

    private suspend fun putState(key: String, value: String) = write { syncDao.putState(SyncState(key, value)) }

    private suspend fun <T> write(block: suspend () -> T): T =
        database.useWriterConnection { transactor -> transactor.immediateTransaction { block() } }

    private fun kotlinx.serialization.json.JsonElement?.isPresent(): Boolean =
        this != null && this !is kotlinx.serialization.json.JsonNull

    companion object {
        private const val PERIOD_MILLIS = 60_000L
        private const val PUSH_BATCH = 200
        private const val PULL_PAGE = 500

        /** Ein Client für die Lebenszeit der App; die Engine kommt von der Plattform (OkHttp, Darwin). */
        private val sharedHttpClient: HttpClient by lazy { HttpClient { installSyncDefaults() } }

        /**
         * Die App verlangt HTTPS (Spezifikation 7.1). Die Ausnahme sind Adressen, die nur beim
         * Entwickeln vorkommen: der eigene Rechner, vom Emulator und vom Simulator aus gesehen.
         */
        fun normalizeUrl(input: String): String? {
            val url = input.trim().trimEnd('/')
            if (url.startsWith("https://") && url.length > 8) return url
            val local = listOf("http://localhost", "http://127.0.0.1", "http://10.0.2.2")
            return url.takeIf { candidate -> local.any { candidate == it || candidate.startsWith("$it:") } }
        }
    }
}
