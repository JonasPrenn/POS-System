package com.example.vereins_kassensystem.server.web

import com.example.vereins_kassensystem.server.db.Database
import com.example.vereins_kassensystem.server.db.execute
import com.example.vereins_kassensystem.server.db.query
import com.example.vereins_kassensystem.server.db.queryOne
import com.example.vereins_kassensystem.server.devices.Argon2
import com.example.vereins_kassensystem.server.devices.Tokens
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

/** Die Bereiche der Verwaltung; an ihnen hängen die Rechte, nicht an einzelnen Seiten. */
enum class Area { OVERVIEW, MEMBERS, STATEMENTS, CASH, REPORTS, BOOKS, STOCK, PRODUCTS, PURCHASES, DEVICES, USERS, AUDIT, SETTINGS }

/**
 * Wer die Web-Oberfläche benutzt (docs/WEB-VERWALTUNG.md, 2.4). Chargen wechseln jedes
 * Semester — Rollen sind deshalb Grundfunktion, nicht Zugabe.
 *
 * Der Budenwart sieht keine Deckel: Die Mitgliederliste einer katholischen Verbindung sind
 * Daten nach Art. 9 DSGVO, und wer das Lager führt, braucht sie nicht.
 */
enum class Role(val label: String, val hint: String, vararg areas: Area) {
    ADMIN("Administrator", "Alles, einschließlich Benutzer, Geräte und Einstellungen", *Area.entries.toTypedArray()),
    // Bankverbindung und E-Mail-Versand stehen in den Einstellungen; beides ist Sache des Kassiers.
    KASSIER("Kassier", "Alles zu Geld und Mitgliedern, koppelt und sperrt Geräte, Einstellungen",
        Area.OVERVIEW, Area.MEMBERS, Area.STATEMENTS, Area.CASH, Area.REPORTS, Area.BOOKS, Area.STOCK, Area.PRODUCTS, Area.PURCHASES, Area.DEVICES, Area.AUDIT, Area.SETTINGS),
    VORSTAND("Senior und Chargen", "Liest mit: Übersicht, Mitglieder, Abrechnung, Kasse, Berichte, Bücher, Lager, Sortiment, Einkauf",
        Area.OVERVIEW, Area.MEMBERS, Area.STATEMENTS, Area.CASH, Area.REPORTS, Area.BOOKS, Area.STOCK, Area.PRODUCTS, Area.PURCHASES),
    BUDENWART("Budenwart", "Lager, Sortiment und Einkauf — keine Deckel", Area.STOCK, Area.PRODUCTS, Area.PURCHASES),
    PRUEFER("Rechnungsprüfer", "Lesend und auf Zeit: Abrechnung, Kasse, Berichte, Bücher, Einkauf, Protokoll", Area.STATEMENTS, Area.CASH, Area.REPORTS, Area.BOOKS, Area.PURCHASES, Area.AUDIT);

    val areas: Set<Area> = areas.toSet()

    fun may(area: Area) = area in areas

    /** Mitglieder anlegen und auf Deckel buchen: wer das Geld verantwortet, sonst niemand. */
    val writesMembers: Boolean get() = this == ADMIN || this == KASSIER

    /** Belege erfassen und Wareneingang buchen: Kassier und Budenwart. */
    val writesPurchases: Boolean get() = this == ADMIN || this == KASSIER || this == BUDENWART
    /** Sortiment und Preise: wer die Bude führt, pflegt auch, was sie verkauft. */
    val writesProducts: Boolean get() = this == ADMIN || this == KASSIER || this == BUDENWART
}

class WebUser(
    val id: UUID,
    val login: String,
    val displayName: String,
    val role: Role,
    val active: Boolean,
    val validUntil: LocalDate?,
    val lastLoginAt: Instant?,
) {
    fun usable(today: LocalDate): Boolean = active && (validUntil == null || !today.isAfter(validUntil))

    companion object {
        /** Der Posteingang handelt ohne Menschen: ein Akteur fürs Protokoll, der kein Benutzer ist. */
        val SYSTEM_ID: UUID = UUID(0L, 0L)
        val SYSTEM = WebUser(SYSTEM_ID, "posteingang", "Posteingang (automatisch)", Role.KASSIER, true, null, null)
    }

    val initials: String
        get() = displayName.split(' ').filter { it.isNotBlank() && it.first().isLetter() && !it.endsWith('.') }
            .let { parts -> listOfNotNull(parts.firstOrNull(), parts.drop(1).lastOrNull()) }
            .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
}

/** Eine angemeldete Sitzung: wer, und das Geheimnis, das jedes Formular mitschicken muss. */
class WebSession(val user: WebUser, val csrf: String)

class AccountProblem(message: String) : RuntimeException(message)

/**
 * Benutzer und Sitzungen. Passwörter mit Argon2id wie die Gerätetoken; im Cookie steht ein
 * Zufallswert, in der Datenbank nur sein Hash.
 */
class Accounts(private val db: Database, private val today: () -> LocalDate = LocalDate::now) {

    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun anyUser(): Boolean = db.transaction { c -> c.queryOne("SELECT 1 FROM users LIMIT 1") { true } ?: false }

    fun list(): List<WebUser> = db.transaction { c -> c.query("SELECT * FROM users ORDER BY active DESC, display_name") { it.user() } }

    fun find(id: UUID): WebUser? = db.transaction { c -> c.queryOne("SELECT * FROM users WHERE id = ?", id) { it.user() } }

    fun create(login: String, displayName: String, role: Role, password: String, validUntil: LocalDate? = null): WebUser {
        val cleanLogin = login.trim()
        val cleanName = displayName.trim()
        if (cleanLogin.length < 3) throw AccountProblem("Der Anmeldename braucht mindestens drei Zeichen.")
        if (cleanName.isEmpty()) throw AccountProblem("Bitte einen Namen angeben.")
        checkPassword(password)
        val id = UUID.randomUUID()
        db.transaction { c ->
            if (c.queryOne("SELECT 1 FROM users WHERE lower(login) = lower(?)", cleanLogin) { true } == true) {
                throw AccountProblem("Den Anmeldenamen „$cleanLogin“ gibt es schon.")
            }
            c.execute(
                "INSERT INTO users (id, login, display_name, role, password_hash, valid_until) VALUES (?, ?, ?, ?, ?, ?)",
                id, cleanLogin, cleanName, role.name, Argon2.hash(password.toByteArray()), validUntil?.let(java.sql.Date::valueOf)
            )
        }
        return find(id)!!
    }

    fun update(id: UUID, role: Role, active: Boolean, validUntil: LocalDate?) {
        db.transaction { c ->
            c.execute("UPDATE users SET role = ?, active = ?, valid_until = ? WHERE id = ?", role.name, active, validUntil?.let(java.sql.Date::valueOf), id)
            // Wer gesperrt oder herabgestuft wird, soll das sofort merken, nicht erst nach zwölf Stunden.
            c.execute("DELETE FROM web_sessions WHERE user_id = ?", id)
        }
    }

    fun setPassword(id: UUID, password: String) {
        checkPassword(password)
        db.transaction { c ->
            c.execute("UPDATE users SET password_hash = ? WHERE id = ?", Argon2.hash(password.toByteArray()), id)
            c.execute("DELETE FROM web_sessions WHERE user_id = ?", id)
        }
    }

    /** Wie viele aktive Administratoren es gibt — der letzte darf sich nicht selbst aussperren. */
    fun activeAdmins(): Int = list().count { it.role == Role.ADMIN && it.usable(today()) }

    /** Prüft Name und Passwort. Null bei jedem Fehlschlag — welcher es war, erfährt niemand. */
    fun login(login: String, password: String, now: Instant = Instant.now()): Pair<String, WebSession>? {
        val row = db.transaction { c ->
            c.queryOne("SELECT * FROM users WHERE lower(login) = lower(?)", login.trim()) { it.user() to it.getString("password_hash") }
        }
        // Auch ohne Treffer rechnen, damit die Antwortzeit keinen Anmeldenamen verrät.
        val hash = row?.second ?: DUMMY_HASH
        val matches = Argon2.verify(password.toByteArray(), hash)
        val user = row?.first
        if (!matches || user == null || !user.usable(today())) return null

        val token = ByteArray(32).also(random::nextBytes).let(encoder::encodeToString)
        val csrf = ByteArray(24).also(random::nextBytes).let(encoder::encodeToString)
        db.transaction { c ->
            c.execute(
                "INSERT INTO web_sessions (token_hash, user_id, csrf, expires_at) VALUES (?, ?, ?, ?)",
                Tokens.sha256Hex(token), user.id, csrf, Timestamp.from(now.plus(MAX_AGE))
            )
            c.execute("UPDATE users SET last_login_at = ? WHERE id = ?", Timestamp.from(now), user.id)
            c.execute("DELETE FROM web_sessions WHERE expires_at < ?", Timestamp.from(now))
        }
        return token to WebSession(user, csrf)
    }

    /** Die Sitzung zum Cookie — oder null, wenn sie abgelaufen ist oder der Benutzer nicht mehr darf. */
    fun session(token: String, now: Instant = Instant.now()): WebSession? = db.transaction { c ->
        val hash = Tokens.sha256Hex(token)
        val found = c.queryOne(
            "SELECT u.*, s.csrf, s.last_seen_at AS seen FROM web_sessions s JOIN users u ON u.id = s.user_id " +
                "WHERE s.token_hash = ? AND s.expires_at > ?",
            hash, Timestamp.from(now)
        ) { Triple(it.user(), it.getString("csrf"), it.getTimestamp("seen").toInstant()) } ?: return@transaction null
        val (user, csrf, seen) = found
        if (!user.usable(today()) || Duration.between(seen, now) > IDLE) {
            c.execute("DELETE FROM web_sessions WHERE token_hash = ?", hash)
            return@transaction null
        }
        // Nicht bei jedem Seitenaufruf schreiben; die Minute genügt für „zwölf Stunden ohne Klick".
        if (Duration.between(seen, now) > Duration.ofMinutes(1)) {
            c.execute("UPDATE web_sessions SET last_seen_at = ? WHERE token_hash = ?", Timestamp.from(now), hash)
        }
        WebSession(user, csrf)
    }

    fun logout(token: String) {
        db.transaction { c -> c.execute("DELETE FROM web_sessions WHERE token_hash = ?", Tokens.sha256Hex(token)) }
    }

    private fun checkPassword(password: String) {
        if (password.length < MIN_PASSWORD) throw AccountProblem("Das Passwort braucht mindestens $MIN_PASSWORD Zeichen.")
    }

    private fun ResultSet.user() = WebUser(
        id = getObject("id", UUID::class.java),
        login = getString("login"),
        displayName = getString("display_name"),
        role = Role.valueOf(getString("role")),
        active = getBoolean("active"),
        validUntil = getDate("valid_until")?.toLocalDate(),
        lastLoginAt = getTimestamp("last_login_at")?.toInstant(),
    )

    companion object {
        const val MIN_PASSWORD = 10
        val IDLE: Duration = Duration.ofHours(12)
        val MAX_AGE: Duration = Duration.ofDays(14)
        private val DUMMY_HASH: String by lazy { Argon2.hash("kein-benutzer".toByteArray()) }
    }
}

/** Das Änderungsprotokoll (4.7). Schreibt in der Transaktion des Aufrufers, wenn er eine hat. */
class AuditLog(private val db: Database) {

    class Entry(val at: Instant, val actor: String, val action: String, val subject: String, val detail: String)

    fun record(user: WebUser?, action: String, subject: String = "", detail: String = "") =
        db.transaction { c -> record(c, user?.id, user?.displayName ?: "System", action, subject, detail) }

    fun recent(limit: Int, actionPrefix: String? = null): List<Entry> = db.transaction { c ->
        c.query(
            "SELECT at, actor, action, subject, detail FROM audit_log WHERE (?::text IS NULL OR action LIKE ? || '%') ORDER BY at DESC, id DESC LIMIT ?",
            actionPrefix, actionPrefix, limit
        ) { Entry(it.getTimestamp("at").toInstant(), it.getString("actor"), it.getString("action"), it.getString("subject"), it.getString("detail")) }
    }

    companion object {
        fun record(c: Connection, userId: UUID?, actor: String, action: String, subject: String = "", detail: String = "") {
            // Der Systemakteur hat keine Benutzerzeile; im Protokoll steht nur sein Name.
            c.execute("INSERT INTO audit_log (user_id, actor, action, subject, detail) VALUES (?, ?, ?, ?, ?)", userId?.takeIf { it != WebUser.SYSTEM_ID }, actor, action, subject, detail)
        }
    }
}

/** Der SMTP-Zugang des Vereins, für Abrechnungen und Erinnerungen. Leer: kein Versand. */
class Smtp(val host: String, val port: Int, val user: String, val password: String, val from: String, val startTls: Boolean) {
    val configured get() = host.isNotBlank() && from.isNotBlank()
}

/** Die Bankverbindung, wie sie auf den Kontoauszug kommt. */
class BankAccount(val holder: String, val iban: String, val bic: String) {
    val configured get() = iban.isNotBlank() && holder.isNotBlank()
}

/** Was der Verein über sich einstellt: Name, Farbe, Anschrift, Rechnungsjahr, Bank, E-Mail. */
class VereinSettings(private val db: Database) {

    class Values(
        val name: String, val accent: String, val fiscalStartMonth: Int, val address: String,
        val bank: BankAccount, val smtp: Smtp, val statementText: String,
        val imap: Imap = Imap("", 993, "", "", "INBOX", false), val mailLastPoll: Instant? = null, val mailLastError: String? = null,
    )

    fun load(): Values = db.transaction { c ->
        val all = c.query("SELECT key, value FROM settings") { it.getString("key") to it.getString("value") }.toMap()
        Values(
            name = all[NAME].orEmpty(),
            accent = all[ACCENT]?.takeIf(HEX::matches) ?: DEFAULT_ACCENT,
            fiscalStartMonth = all[FISCAL]?.toIntOrNull()?.takeIf { it in 1..12 } ?: 1,
            address = all[ADDRESS].orEmpty(),
            bank = BankAccount(all[BANK_HOLDER].orEmpty().ifBlank { all[NAME].orEmpty() }, all[IBAN].orEmpty(), all[BIC].orEmpty()),
            smtp = Smtp(all[SMTP_HOST].orEmpty(), all[SMTP_PORT]?.toIntOrNull() ?: 587, all[SMTP_USER].orEmpty(), all[SMTP_PASSWORD].orEmpty(), all[SMTP_FROM].orEmpty(), all[SMTP_TLS] != "0"),
            statementText = all[STATEMENT_TEXT].orEmpty(),
            imap = Imap(all[IMAP_HOST].orEmpty(), all[IMAP_PORT]?.toIntOrNull() ?: 993, all[IMAP_USER].orEmpty(), all[IMAP_PASSWORD].orEmpty(), all[IMAP_FOLDER].orEmpty().ifBlank { "INBOX" }, all[IMAP_ENABLED] == "1"),
            mailLastPoll = all[MAIL_LAST_POLL]?.let { runCatching { Instant.parse(it) }.getOrNull() }, mailLastError = all[MAIL_LAST_ERROR]?.takeIf { it.isNotBlank() },
        )
    }

    fun save(name: String, accent: String, fiscalStartMonth: Int, address: String) {
        if (!HEX.matches(accent)) throw AccountProblem("Die Vereinsfarbe muss eine Farbe der Form #RRGGBB sein.")
        if (fiscalStartMonth !in 1..12) throw AccountProblem("Der Monat muss zwischen 1 und 12 liegen.")
        put(NAME to name.trim(), ACCENT to accent.uppercase(), FISCAL to fiscalStartMonth.toString(), ADDRESS to address.trim().take(400))
    }

    fun saveBank(holder: String, iban: String, bic: String, statementText: String) {
        val clean = iban.replace(" ", "").uppercase()
        if (clean.isNotEmpty() && !ibanValid(clean)) throw AccountProblem("Die IBAN stimmt nicht — Prüfziffer oder Länge passen nicht.")
        put(BANK_HOLDER to holder.trim().take(70), IBAN to clean, BIC to bic.replace(" ", "").uppercase().take(11), STATEMENT_TEXT to statementText.trim().take(600))
    }

    /** Das Passwort bleibt, wenn das Feld leer abgeschickt wird — es wird nie zurück ins Formular geschrieben. */
    fun saveSmtp(host: String, port: Int, user: String, password: String?, from: String, startTls: Boolean) {
        if (port !in 1..65535) throw AccountProblem("Der Port muss zwischen 1 und 65535 liegen.")
        if (from.isNotBlank() && !from.contains('@')) throw AccountProblem("Die Absenderadresse ist keine E-Mail-Adresse.")
        put(SMTP_HOST to host.trim(), SMTP_PORT to port.toString(), SMTP_USER to user.trim(), SMTP_FROM to from.trim(), SMTP_TLS to if (startTls) "1" else "0")
        if (!password.isNullOrEmpty()) put(SMTP_PASSWORD to password)
    }

    fun saveImap(host: String, port: Int, user: String, password: String?, folder: String, enabled: Boolean) {
        if (enabled && host.isBlank()) throw AccountProblem("Zum Abrufen braucht es einen IMAP-Server.")
        put(IMAP_HOST to host.trim(), IMAP_PORT to port.toString(), IMAP_USER to user.trim(), IMAP_FOLDER to folder.trim().ifBlank { "INBOX" }, IMAP_ENABLED to if (enabled) "1" else "0")
        if (!password.isNullOrEmpty()) put(IMAP_PASSWORD to password)
    }

    /** Wann zuletzt abgerufen wurde und ob es gut ging — für die Seite, nicht fürs Protokoll. */
    fun notePoll(at: Instant, error: String?) = put(MAIL_LAST_POLL to at.toString(), MAIL_LAST_ERROR to error.orEmpty())

    private fun put(vararg pairs: Pair<String, String>) = db.transaction { c ->
        for ((key, value) in pairs) c.execute("INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value", key, value)
    }

    companion object {
        private const val NAME = "club_name"
        private const val ACCENT = "club_accent"
        private const val FISCAL = "fiscal_start_month"
        private const val ADDRESS = "club_address"
        private const val BANK_HOLDER = "bank_holder"
        private const val IBAN = "bank_iban"
        private const val BIC = "bank_bic"
        private const val SMTP_HOST = "smtp_host"
        private const val SMTP_PORT = "smtp_port"
        private const val SMTP_USER = "smtp_user"
        private const val SMTP_PASSWORD = "smtp_password"
        private const val SMTP_FROM = "smtp_from"
        private const val SMTP_TLS = "smtp_starttls"
        private const val STATEMENT_TEXT = "statement_text"
        private const val IMAP_HOST = "imap_host"
        private const val IMAP_PORT = "imap_port"
        private const val IMAP_USER = "imap_user"
        private const val IMAP_PASSWORD = "imap_password"
        private const val IMAP_FOLDER = "imap_folder"
        private const val IMAP_ENABLED = "imap_enabled"
        private const val MAIL_LAST_POLL = "mail_last_poll"
        private const val MAIL_LAST_ERROR = "mail_last_error"

        /** ISO 7064 mod 97-10, wie jede Bank sie prüft. */
        fun ibanValid(iban: String): Boolean {
            if (!Regex("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}").matches(iban)) return false
            val rearranged = iban.substring(4) + iban.substring(0, 4)
            val digits = rearranged.map { if (it.isDigit()) it.toString() else (it - 'A' + 10).toString() }.joinToString("")
            return digits.fold(0) { acc, ch -> (acc * 10 + (ch - '0')) % 97 } == 1
        }

        /** Pine40 — die Farbe des Produkts, solange der Verein keine eigene gewählt hat. */
        const val DEFAULT_ACCENT = "#146B4C"
        private val HEX = Regex("#[0-9a-fA-F]{6}")

        /** Dieselben Vorschläge wie in der App (ClubIdentity.kt). */
        val PRESETS = listOf(
            "Standard (Pine)" to "#146B4C", "Vereinsgrün" to "#2E7D32", "Rot" to "#C62828", "Bordeaux" to "#8E1538",
            "Blau" to "#1565C0", "Türkis" to "#00838F", "Violett" to "#6A1B9A", "Gold" to "#F9A825",
            "Orange" to "#EF6C00", "Schwarz" to "#2B2B2B",
        )

        /** Weiß oder fast Schwarz — was auf der Farbe besser lesbar ist (wie contrastingOn() in der App). */
        fun readableOn(hex: String): String {
            fun channel(i: Int): Double {
                val v = hex.substring(i, i + 2).toInt(16) / 255.0
                return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            }
            val l = 0.2126 * channel(1) + 0.7152 * channel(3) + 0.0722 * channel(5)
            val onWhite = 1.05 / (l + 0.05)
            val onInk = (l + 0.05) / (0.0091 + 0.05)   // Leuchtdichte von #16190F
            return if (onWhite >= onInk) "#FFFFFF" else "#16190F"
        }
    }
}
