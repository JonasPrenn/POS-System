package com.example.vereins_kassensystem

import com.example.vereins_kassensystem.data.sync.RowCodec
import com.example.vereins_kassensystem.sync.BalanceResponse
import com.example.vereins_kassensystem.sync.ChangeDto
import com.example.vereins_kassensystem.sync.ChangesResponse
import com.example.vereins_kassensystem.sync.HealthResponse
import com.example.vereins_kassensystem.sync.PushOperation
import com.example.vereins_kassensystem.sync.PushResponse
import com.example.vereins_kassensystem.sync.PushResult
import com.example.vereins_kassensystem.sync.PushStatus
import com.example.vereins_kassensystem.sync.RegisterResponse
import com.example.vereins_kassensystem.sync.SyncApi
import com.example.vereins_kassensystem.sync.SyncHttpException
import io.ktor.http.ContentType
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Der Server, im Speicher nachgebaut — mit denselben Regeln wie `server/sync/SyncStore.kt`,
 * soweit der Abgleich der App sie zu spüren bekommt: eine globale Sequenz, Idempotenz über
 * `client_change_id`, alles oder nichts je Aufruf, „letzter Schreibvorgang gewinnt" über
 * `base_updated_at`, anfügende Tabellen ohne Ändern und Löschen.
 *
 * Dazu Schalter, mit denen ein Test das Vereinsheim-WLAN spielt: [online],
 * [loseNextResponse] (die Antwort geht verloren, nachdem alles verbucht ist), [revoked],
 * [rejectedEntity] (422).
 *
 * Dass der echte Server sich so verhält, prüfen dessen eigene Tests; dass der Client aus
 * `:core` zu ihm passt, `SyncClientTest`. Hier geht es um die Engine.
 */
class FakeSyncServer {

    private class Stored(var row: JsonObject, var seq: Long, var updatedAt: String, var deleted: Boolean = false)

    private val rows = LinkedHashMap<String, Stored>()
    private val answered = HashMap<String, PushResult>()
    private val receipts = HashMap<String, ByteArray>()
    private var seq = 0L
    private var clock = 1_790_000_000_000

    var online = true
    var loseNextResponse = false
    var revoked = false
    var rejectedEntity: String? = null
    var pushCalls = 0
        private set

    private val appendOnly = setOf("transactions", "stock_entries", "stock_draws")

    fun count(entity: String): Int = rows.keys.count { it.startsWith("$entity/") }

    fun row(entity: String, id: String): JsonObject? = rows["$entity/$id"]?.row

    fun api(): SyncApi = object : SyncApi {
        override suspend fun health() = HealthResponse("ok", RowCodec.iso(clock), "2", "ok").also { reach() }

        override suspend fun register(pairingCode: String, label: String, platform: String): RegisterResponse {
            reach()
            if (pairingCode != PAIRING_CODE) throw SyncHttpException(409, "pairing_failed", "Kopplungscode unbekannt")
            // Wer sich neu anmeldet, ist ein neues Gerät mit gültigem Token — wie am echten Server.
            revoked = false
            return RegisterResponse(deviceId = "018f2b6c-7d1e-7a00-8000-0000000000d${label.length % 10}", token = "vd_dev_test")
        }

        override suspend fun changes(since: Long, limit: Int): ChangesResponse {
            authorize()
            val newer = rows.entries.filter { it.value.seq > since }.sortedBy { it.value.seq }
            val page = newer.take(limit)
            return ChangesResponse(
                changes = page.map { (key, stored) -> ChangeDto(key.substringBefore('/'), stored.seq, stored.deleted, wire(stored)) },
                nextSince = page.lastOrNull()?.value?.seq ?: since,
                hasMore = newer.size > limit,
                serverTime = RowCodec.iso(clock),
            )
        }

        override suspend fun push(operations: List<PushOperation>): PushResponse {
            authorize()
            pushCalls++
            operations.firstOrNull { it.entity == rejectedEntity }?.let {
                throw SyncHttpException(422, "unprocessable", "Operation (${it.entity}, ${it.op}): nicht anwendbar")
            }
            operations.firstOrNull { it.op != "insert" && it.entity in appendOnly }?.let {
                throw SyncHttpException(422, "unprocessable", "'${it.entity}' wird nie geändert")
            }
            val results = operations.map { op -> answered.getOrPut(op.clientChangeId) { apply(op) } }
            if (loseNextResponse) {
                loseNextResponse = false
                throw IOException("Verbindung abgebrochen, nachdem der Server verbucht hatte")
            }
            return PushResponse(results, results.mapNotNull { it.seq }.maxOrNull() ?: seq)
        }

        override suspend fun balance(memberId: String) = BalanceResponse(memberId, "0.00", RowCodec.iso(clock))

        override suspend fun uploadReceipt(bytes: ByteArray, contentType: ContentType): String {
            authorize()
            return "beleg-${receipts.size + 1}.jpg".also { receipts[it] = bytes }
        }

        override suspend fun downloadReceipt(photoKey: String): ByteArray {
            authorize()
            return receipts[photoKey] ?: throw SyncHttpException(404, "not_found", "Beleg unbekannt")
        }
    }

    private fun reach() {
        if (!online) throw IOException("kein Netz")
    }

    private fun authorize() {
        reach()
        if (revoked) throw SyncHttpException(401, "unauthorized", "Token ungültig oder gesperrt")
    }

    private fun apply(op: PushOperation): PushResult {
        val id = op.row["id"]!!.jsonPrimitive.content
        val key = "${op.entity}/$id"
        val existing = rows[key]
        fun stale() = PushResult(op.clientChangeId, PushStatus.IGNORED_STALE, current = existing?.let(::wire))
        return when (op.op) {
            "insert" -> if (existing != null) stale() else touch(key, Stored(op.row, 0, ""), op)
            "update" -> when {
                existing == null || existing.deleted -> stale()
                op.baseUpdatedAt != null && existing.updatedAt > op.baseUpdatedAt!! -> stale()
                else -> touch(key, existing.also { it.row = JsonObject(it.row + op.row) }, op)
            }
            else -> when {
                existing == null -> stale()
                existing.deleted -> PushResult(op.clientChangeId, PushStatus.APPLIED, existing.seq)
                else -> touch(key, existing.also { it.deleted = true }, op)
            }
        }
    }

    private fun touch(key: String, stored: Stored, op: PushOperation): PushResult {
        clock += 1_000
        stored.seq = ++seq
        stored.updatedAt = RowCodec.iso(clock)
        rows[key] = stored
        return PushResult(op.clientChangeId, PushStatus.APPLIED, stored.seq)
    }

    private fun wire(stored: Stored) = JsonObject(
        stored.row + mapOf("updated_at" to JsonPrimitive(stored.updatedAt)) +
            if (stored.deleted) mapOf("deleted_at" to JsonPrimitive(stored.updatedAt)) else emptyMap()
    )

    companion object {
        const val PAIRING_CODE = "8K4M-2QX9"
    }
}
