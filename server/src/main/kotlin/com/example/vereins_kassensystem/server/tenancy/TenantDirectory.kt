package com.example.vereins_kassensystem.server.tenancy

import com.example.vereins_kassensystem.MinimumVersion
import com.example.vereins_kassensystem.server.ServerConfig
import com.example.vereins_kassensystem.server.db.Database
import com.example.vereins_kassensystem.server.db.queryOne
import com.example.vereins_kassensystem.server.devices.PairingFailed
import com.example.vereins_kassensystem.server.devices.Registration
import com.example.vereins_kassensystem.server.devices.Tokens
import com.example.vereins_kassensystem.server.web.AccountProblem
import com.example.vereins_kassensystem.server.web.Accounts
import com.example.vereins_kassensystem.server.web.ImapMailbox
import com.example.vereins_kassensystem.server.web.Mailbox
import com.example.vereins_kassensystem.server.web.Mailer
import com.example.vereins_kassensystem.server.web.Role
import com.example.vereins_kassensystem.server.web.SmtpMailer
import com.example.vereins_kassensystem.server.web.Updates
import com.example.vereins_kassensystem.server.web.VereinSettings
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Die Vereine auf diesem Server, jeder mit eigener Datenbank, eigenen Geräten, eigener Verwaltung.
 *
 * Für die Apps ändert sich nichts: Ein Gerät koppelt mit einem Code und gehört danach dem Verein,
 * der den Code erzeugt hat; sein Token trägt die Geräte-ID, und [forDevice] weiß, wo sie steht.
 * Die Datenbank von vor den Vereinen ist der erste Verein ([default]) — mit allen Daten, Geräten
 * und Zugängen, wie sie waren, unter /verwaltung wie bisher.
 *
 * Lässt sich die Systemdatenbank nicht anlegen (etwa weil der Datenbankbenutzer das nicht darf),
 * läuft der erste Verein allein weiter, wie vor den Vereinen: [system] ist dann null, und die
 * Systemverwaltung sagt, warum.
 */
class TenantDirectory private constructor(
    val config: ServerConfig,
    private val databases: Databases?,
    val system: SystemStore?,
    /** Warum es keine Systemdatenbank gibt — für die Seite der Systemverwaltung. */
    val systemProblem: String?,
    val updates: Updates,
    private val mailer: Mailer,
    private val mailbox: Mailbox,
) : AutoCloseable {

    private val tenants = ConcurrentHashMap<UUID, Tenant>()
    private val routes = ConcurrentHashMap<UUID, UUID>()

    lateinit var default: Tenant
        private set

    /** Der erste Verein vorn, dann nach Alter. */
    fun all(): List<Tenant> = tenants.values.sortedWith(compareByDescending<Tenant> { it.info.isDefault }.thenBy { it.info.createdAt }.thenBy { it.info.slug })

    fun byId(id: UUID): Tenant? = tenants[id]

    fun bySlug(slug: String): Tenant? = Kuerzel.normalize(slug).let { s -> tenants.values.firstOrNull { it.info.slug == s } }

    /** Ein frischer Server: nur der erste Verein, und der hat noch keinen Zugang. Dann führt /verwaltung zur Einrichtung. */
    fun needsSetup(): Boolean = tenants.size == 1 && !default.web.accounts.anyUser()

    // ------------------------------------------------------------------ Geräte

    /**
     * Der Verein eines Geräts: aus dem Speicher, sonst aus der Systemdatenbank, sonst gesucht —
     * Geräte von vor den Vereinen haben keinen Eintrag und stehen im ersten Verein.
     */
    fun forDevice(deviceId: UUID): Tenant? {
        val known = routes[deviceId]?.let(tenants::get)
            ?: system?.let { runCatching { it.route(deviceId) }.getOrNull() }?.let(tenants::get)?.also { routes[deviceId] = it.id }
            ?: all().firstOrNull { it.devices.knows(deviceId) }?.also { remember(deviceId, it) }
            ?: return null
        return known.takeIf { it.info.active }
    }

    fun authenticate(token: String): TenantDevice? {
        val parsed = Tokens.parseDeviceToken(token) ?: return null
        val tenant = forDevice(parsed.deviceId) ?: return null
        return tenant.devices.authenticate(token)?.let { TenantDevice(it, tenant) }
    }

    /**
     * Koppeln: Der Code sagt, welcher Verein. Codes sind zufällig und kurzlebig; dass zwei Vereine
     * denselben offen haben, ist so gut wie ausgeschlossen — und dann wird abgelehnt statt geraten.
     * Ein unbekannter Code geht an den ersten Verein, der ihn ablehnt wie bisher.
     */
    fun register(code: String, label: String, platform: String): Pair<Tenant, Registration> {
        val hash = Tokens.sha256Hex(Tokens.normalizePairingCode(code))
        val holders = all().filter { it.info.active && it.devices.knowsCode(hash) }
        val tenant = when (holders.size) {
            0 -> default
            1 -> holders.single()
            else -> holders.singleOrNull { it.devices.hasOpenCode(hash) } ?: throw PairingFailed("Kopplungscode nicht eindeutig — bitte einen neuen erzeugen")
        }
        val registration = tenant.devices.register(code, label, platform)
        remember(registration.deviceId, tenant)
        return tenant to registration
    }

    private fun remember(deviceId: UUID, tenant: Tenant) {
        routes[deviceId] = tenant.id
        // Scheitert das, findet [forDevice] das Gerät beim nächsten Mal durch Suchen.
        system?.let { runCatching { it.putRoute(deviceId, tenant.id) }.onFailure { e -> log.warn("Weg für Gerät {} nicht gespeichert", deviceId, e) } }
    }

    // ------------------------------------------------------------------ Vereine

    /**
     * Ein neuer Verein: eigene Datenbank, eigener Ordner für Belege, ein erster Administrator.
     * Name und Farbe stehen danach nur hier — auf die Tablets kommt erst, was der Verein in seinen
     * Einstellungen speichert. Sonst sähe ein Tablet beim Koppeln einen Server mit Daten und würde
     * seine eigenen nicht hochladen.
     */
    fun create(name: String, slugText: String, adminName: String, adminLogin: String, adminPassword: String): Tenant {
        val system = system ?: throw AccountProblem(NO_SYSTEM)
        val databases = checkNotNull(databases)
        val cleanName = name.trim().take(80).ifEmpty { throw AccountProblem("Bitte den Namen des Vereins angeben.") }
        val slug = checkedSlug(slugText, except = null)
        Accounts.check(adminLogin, adminName, adminPassword)

        val id = UUID.randomUUID()
        val dbName = freeDatabaseName(databases, slug)
        val db = databases.open(dbName, Database.MIGRATIONS_VEREIN, POOL_PER_VEREIN)
        val info = TenantInfo(id, slug, cleanName, dbName, "vereine/$id", isDefault = false, active = true, createdAt = Instant.now())
        val tenant = try {
            Accounts(db).create(adminLogin, adminName, Role.ADMIN, adminPassword)
            system.insert(info)
            Tenant(info, db, config, updates, mailer, mailbox, this)
        } catch (e: Exception) {
            db.close()
            throw e
        }
        tenants[id] = tenant
        tenant.web.settings.mirrorMinAppVersion(minAppVersion)
        return tenant
    }

    /** Neues Kürzel oder neuer Name. Die Datenbank behält ihren Namen; Anmeldungen gehen ab sofort mit dem neuen Kürzel. */
    fun rename(tenant: Tenant, slugText: String = tenant.info.slug, name: String = tenant.info.name) {
        val system = system ?: throw AccountProblem(NO_SYSTEM)
        val slug = if (Kuerzel.normalize(slugText) == tenant.info.slug) tenant.info.slug else checkedSlug(slugText, except = tenant.id)
        val cleanName = name.trim().take(80).ifEmpty { tenant.info.name }
        if (slug == tenant.info.slug && cleanName == tenant.info.name) return
        system.rename(tenant.id, slug, cleanName)
        tenant.info = tenant.info.copy(slug = slug, name = cleanName)
    }

    private fun checkedSlug(text: String, except: UUID?): String {
        val slug = Kuerzel.normalize(text)
        Kuerzel.problem(slug)?.let { throw AccountProblem(it) }
        if (checkNotNull(system).slugTaken(slug, except)) throw AccountProblem("Das Kürzel „$slug“ ist schon vergeben.")
        return slug
    }

    private fun freeDatabaseName(databases: Databases, slug: String): String {
        val base = "${databases.firstName}_${slug.replace('-', '_')}".take(56)
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "${base}_$it" }
            .first { name -> tenants.values.none { it.info.dbName == name } && !databases.exists(name) }
    }

    // ------------------------------------------------------------- Mindestversion

    /** Die älteste App, die noch kassieren darf; "0.0.0" heißt: alle. */
    val minAppVersion: String get() = system?.let { runCatching { it.setting(MIN_APP_VERSION) }.getOrNull() } ?: NO_MINIMUM

    /**
     * Setzt die Mindestversion und gibt sie allen Vereinen für ihre Tablets mit. Apps ab dieser
     * Fassung sperren sich darunter; ältere kennen die Sperre nicht und arbeiten weiter — deshalb
     * bleibt der Server zu ihnen kompatibel, bis alle Geräte aktualisiert sind.
     */
    fun setMinAppVersion(text: String) {
        val system = system ?: throw AccountProblem(NO_SYSTEM)
        val version = text.trim()
        if (codeOf(version) == null) throw AccountProblem("Die Mindestversion hat die Form 1.3.0 — drei Zahlen, die zweite und dritte unter 100.")
        system.putSetting(MIN_APP_VERSION, version)
        for (tenant in all()) tenant.web.settings.mirrorMinAppVersion(version)
    }

    override fun close() {
        for (tenant in tenants.values) if (!tenant.info.isDefault) runCatching { tenant.db.close() }
        system?.db?.let { runCatching { it.close() } }
    }

    private fun load(first: Database) {
        val known = system?.tenants().orEmpty()
        val firstInfo = known.firstOrNull { it.isDefault } ?: firstTenant(first).also { info -> system?.insert(info) }
        default = Tenant(firstInfo, first, config, updates, mailer, mailbox, this).also { tenants[it.id] = it }
        for (info in known.filterNot { it.isDefault }) {
            try {
                val db = checkNotNull(databases).open(info.dbName, Database.MIGRATIONS_VEREIN, POOL_PER_VEREIN)
                tenants[info.id] = Tenant(info, db, config, updates, mailer, mailbox, this)
            } catch (e: Exception) {
                // Ein Verein, dessen Datenbank fehlt, hält die anderen nicht auf.
                log.error("Verein {}: Datenbank {} nicht verfügbar", info.slug, info.dbName, e)
            }
        }
        if (system != null) {
            val version = minAppVersion
            for (tenant in all()) runCatching { tenant.web.settings.mirrorMinAppVersion(version) }
        }
    }

    /** Der erste Verein, wie die Datenbank von vor den Vereinen ihn kennt: Name aus den Einstellungen, Kürzel daraus. */
    private fun firstTenant(first: Database): TenantInfo {
        val name = first.transaction { c -> c.queryOne("SELECT value FROM settings WHERE key = 'club_name'") { it.getString(1) } }.orEmpty().trim()
        return TenantInfo(
            id = UUID.randomUUID(), slug = Kuerzel.suggest(name), name = name.ifBlank { "Verein" },
            dbName = databases?.firstName.orEmpty(), mediaDir = "", isDefault = true, active = true, createdAt = Instant.now(),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(TenantDirectory::class.java)

        const val MIN_APP_VERSION = VereinSettings.MIN_APP_VERSION
        const val NO_MINIMUM = VereinSettings.NO_MIN_APP_VERSION
        private const val POOL_PER_VEREIN = 4
        private const val NO_SYSTEM = "Ohne Systemdatenbank gibt es auf diesem Server nur den ersten Verein."

        /** Wie in der App: 1.3.0 → 103000 ([MinimumVersion]). Null, wenn es keine Version ist. */
        fun codeOf(version: String): Int? = MinimumVersion.codeOf(version)

        /**
         * Öffnet die Systemdatenbank (legt sie beim ersten Start an) und alle Vereine. [first] ist
         * die Datenbank aus DATABASE_URL, schon migriert; ohne [databases] gibt es nur sie.
         */
        fun open(config: ServerConfig, first: Database, databases: Databases?, mailer: Mailer = SmtpMailer, mailbox: Mailbox = ImapMailbox): TenantDirectory {
            val updates = Updates(config.updatesDir, config.version, config.versionDate)
            var problem: String? = null
            val system = if (databases == null) null else try {
                SystemStore(databases.open(databases.systemName, Database.MIGRATIONS_SYSTEM, poolSize = 2)) { LocalDate.now(config.zone) }
            } catch (e: Exception) {
                log.warn("Systemdatenbank nicht verfügbar — es läuft nur der erste Verein", e)
                problem = generateSequence<Throwable>(e) { it.cause }.last().message?.lineSequence()?.firstOrNull() ?: e.toString()
                null
            }
            return TenantDirectory(config, databases, system, problem ?: if (databases == null) "Keine Systemdatenbank eingerichtet." else null, updates, mailer, mailbox)
                .also { it.load(first) }
        }
    }
}
