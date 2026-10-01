package com.example.vereins_kassensystem.server.tenancy

import com.example.vereins_kassensystem.server.ServerConfig
import com.example.vereins_kassensystem.server.db.Database
import com.example.vereins_kassensystem.server.devices.DevicePrincipal
import com.example.vereins_kassensystem.server.devices.DeviceStore
import com.example.vereins_kassensystem.server.media.ReceiptStore
import com.example.vereins_kassensystem.server.payments.OnlinePayments
import com.example.vereins_kassensystem.server.sync.SyncStore
import com.example.vereins_kassensystem.server.web.Mailbox
import com.example.vereins_kassensystem.server.web.Mailer
import com.example.vereins_kassensystem.server.web.TenantWeb
import com.example.vereins_kassensystem.server.web.Updates
import kotlinx.coroutines.asContextElement
import java.text.Normalizer
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/** Ein Verein, wie die Systemdatenbank ihn kennt. */
data class TenantInfo(
    val id: UUID,
    /** Steht bei der Anmeldung hinter dem @: kassier@clunia. Wählt der Verein, eindeutig im System. */
    val slug: String,
    val name: String,
    val dbName: String,
    /** Relativ zu MEDIA_DIR; leer beim ersten Verein, dessen Belege schon dort lagen. */
    val mediaDir: String,
    val isDefault: Boolean,
    val active: Boolean,
    val createdAt: Instant,
)

/**
 * Ein Verein zur Laufzeit: seine Datenbank und alles, was darauf arbeitet — Geräte, Abgleich,
 * Belege, Verwaltung. Kein Teil davon weiß, dass es andere Vereine gibt; welcher gemeint ist,
 * entscheidet [TenantDirectory] beim Gerätetoken, beim Kopplungscode und bei der Anmeldung.
 */
class Tenant internal constructor(
    info: TenantInfo,
    val db: Database,
    config: ServerConfig,
    updates: Updates,
    mailer: Mailer,
    mailbox: Mailbox,
    payments: OnlinePayments,
    val directory: TenantDirectory,
) {
    /** Kürzel und Name ändern sich, wenn der Verein es will; die Datenbank bleibt dieselbe. */
    @Volatile var info: TenantInfo = info
        internal set

    val id: UUID get() = info.id
    val devices = DeviceStore(db, config.pairingCodeTtl)
    val sync = SyncStore(db)
    val receipts = ReceiptStore(if (info.mediaDir.isEmpty()) config.mediaDir else config.mediaDir.resolve(info.mediaDir))
    val web = TenantWeb(config, db, devices, receipts, mailer, mailbox, updates, this, payments)

    override fun toString() = "Verein ${info.slug}"
}

/** Ein Gerät samt seinem Verein — was die Routen der Geräte als angemeldet sehen. */
class TenantDevice(val device: DevicePrincipal, val tenant: Tenant) {
    val id: UUID get() = device.id
}

/**
 * Der Verein der laufenden Anfrage. Die Seiten der Verwaltung fragen nicht danach — sie arbeiten
 * mit dem, was hier steht ([com.example.vereins_kassensystem.server.web.CurrentWeb]). Gesetzt wird
 * er einmal je Anfrage, aus dem Sitzungscookie, und gilt für die Koroutine samt jedem Thread, auf
 * dem sie weiterläuft.
 */
object TenantContext {
    private val current = ThreadLocal<Tenant?>()

    fun current(): Tenant? = current.get()

    fun element(tenant: Tenant): CoroutineContext = current.asContextElement(tenant)
}

/** Das Kürzel eines Vereins: was erlaubt ist, was reserviert, und ein Vorschlag aus dem Namen. */
object Kuerzel {
    /** Die Systemverwaltung: name@system. */
    const val SYSTEM = "system"

    private val PATTERN = Regex("[a-z0-9]([a-z0-9-]{0,28}[a-z0-9])?")
    private val RESERVED = setOf(SYSTEM, "admin", "verwaltung", "server")

    fun normalize(text: String): String = text.trim().lowercase()

    /** Null, wenn [slug] als Kürzel taugt — sonst, warum nicht. Ob es schon vergeben ist, weiß nur das Verzeichnis. */
    fun problem(slug: String): String? = when {
        slug.length < 2 || !PATTERN.matches(slug) -> "Das Kürzel braucht 2 bis 30 Zeichen: Kleinbuchstaben, Ziffern und Bindestriche, nicht am Anfang oder Ende."
        slug in RESERVED -> "„$slug“ ist für den Server reserviert."
        else -> null
    }

    /**
     * Ein Vorschlag aus dem Vereinsnamen: das erste Wort, das keine Abkürzung ist — aus
     * „KMV Clunia Feldkirch“ wird „clunia“. Umlaute werden ausgeschrieben.
     */
    fun suggest(name: String): String {
        val words = name.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val word = words.firstOrNull { it.length >= 3 && !(it.length <= 4 && it == it.uppercase() && it.any(Char::isLetter)) && it.any(Char::isLetter) }
            ?: return "verein"
        val plain = Normalizer.normalize(
            word.lowercase().replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss"),
            Normalizer.Form.NFD,
        ).replace(Regex("[^a-z0-9]"), "").take(30)
        return plain.takeIf { problem(it) == null } ?: "verein"
    }
}
