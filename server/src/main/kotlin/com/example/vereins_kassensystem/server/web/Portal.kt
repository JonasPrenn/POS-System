package com.example.vereins_kassensystem.server.web

import com.example.vereins_kassensystem.server.db.Database
import com.example.vereins_kassensystem.server.db.execute
import com.example.vereins_kassensystem.server.db.query
import com.example.vereins_kassensystem.server.db.queryOne
import com.example.vereins_kassensystem.server.devices.Tokens
import com.example.vereins_kassensystem.server.payments.Checkout
import com.example.vereins_kassensystem.server.payments.CheckoutRequest
import com.example.vereins_kassensystem.server.payments.OnlinePayments
import com.example.vereins_kassensystem.server.payments.PaymentAccount
import com.example.vereins_kassensystem.server.payments.PaymentProblem
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.SecureRandom
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Der Deckel für Mitglieder, am PC und am Handy: anmelden mit einem Link an die Adresse aus dem
 * Profil, ohne Passwort; den eigenen Stand sehen; online aufladen.
 *
 * Bezahlt wird beim Anbieter ([OnlinePayments]). Gebucht wird erst, wenn der Anbieter auf Nachfrage
 * „bezahlt“ sagt — eine Benachrichtigung allein genügt nie —, und dann als gewöhnliche Aufladung mit
 * Zahlungsart Karte, über denselben Weg wie in der Verwaltung ([Writes.bookTab]). Die Buchung trägt
 * die id der Aufladung: Wie oft der Anbieter auch Bescheid gibt, gebucht wird einmal. Jede App seit
 * 1.2.1 kennt eine solche Buchung.
 */
class Portal(
    private val db: Database,
    private val settings: VereinSettings,
    private val reads: Reads,
    private val writes: Writes,
    private val mailer: Mailer,
    private val payments: OnlinePayments,
) {

    class Session(val email: String, val csrf: String)

    class TopUp(
        val id: UUID, val memberId: UUID, val memberName: String, val amount: BigDecimal, val status: String,
        val email: String, val detail: String, val createdAt: Instant, val settledAt: Instant?,
    ) {
        val paid get() = status == "PAID"
        val open get() = status == "PENDING"
    }

    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    // ------------------------------------------------------------ Anmeldung

    /**
     * Schickt einen Anmeldelink, wenn die Adresse zu einem Mitglied gehört. Wer fragt, erfährt nicht,
     * ob sie bekannt ist — die Seite sagt immer dasselbe; true heißt nur für die Tests: verschickt.
     * [link] baut aus dem Token die Adresse, die in der Mail steht.
     */
    fun requestLink(email: String, verein: String, link: (String) -> String, now: Instant = Instant.now()): Boolean {
        val address = email.trim()
        if ('@' !in address || address.length > 200 || memberIds(address).isEmpty()) return false
        // Höchstens drei Links je Viertelstunde und Adresse — sonst wird das Postfach eines Mitglieds zum Ziel.
        val recent = db.transaction { c ->
            c.queryOne("SELECT count(*) FROM portal_links WHERE lower(email) = lower(?) AND created_at > ?", address, Timestamp.from(now.minus(Duration.ofMinutes(15)))) { it.getInt(1) }
        } ?: 0
        if (recent >= 3) return false
        val token = newToken()
        db.transaction { c ->
            c.execute("INSERT INTO portal_links (token_hash, email, created_at, expires_at) VALUES (?, ?, ?, ?)", Tokens.sha256Hex(token), address, Timestamp.from(now), Timestamp.from(now.plus(LINK_VALID)))
            c.execute("DELETE FROM portal_links WHERE expires_at < ?", Timestamp.from(now.minus(Duration.ofDays(1))))
        }
        val text = """
            |Hallo,
            |
            |mit diesem Link meldest du dich bei $verein an. Dort siehst du deinen Deckel und kannst ihn aufladen:
            |
            |${link(token)}
            |
            |Der Link gilt ${LINK_VALID.toMinutes()} Minuten und nur einmal. Hast du ihn nicht angefordert, ignoriere diese E-Mail einfach.
            |
            |$verein
        """.trimMargin()
        return mailer.send(settings.load().smtp, Mail(address, "Anmelden: dein Deckel bei $verein", text, null, null)) == null
    }

    /** Löst einen Anmeldelink ein — einmal, solange er gilt — und liefert das Token der neuen Sitzung. */
    fun redeem(token: String, now: Instant = Instant.now()): String? = db.transaction { c ->
        val hash = Tokens.sha256Hex(token)
        val email = c.queryOne(
            "SELECT email FROM portal_links WHERE token_hash = ? AND used_at IS NULL AND expires_at > ? FOR UPDATE", hash, Timestamp.from(now)
        ) { it.getString(1) } ?: return@transaction null
        c.execute("UPDATE portal_links SET used_at = ? WHERE token_hash = ?", Timestamp.from(now), hash)
        val session = newToken()
        c.execute(
            "INSERT INTO portal_sessions (token_hash, email, csrf, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
            Tokens.sha256Hex(session), email, newToken(), Timestamp.from(now), Timestamp.from(now.plus(SESSION_VALID))
        )
        c.execute("DELETE FROM portal_sessions WHERE expires_at < ?", Timestamp.from(now))
        session
    }

    fun session(token: String, now: Instant = Instant.now()): Session? = db.transaction { c ->
        c.queryOne("SELECT email, csrf FROM portal_sessions WHERE token_hash = ? AND expires_at > ?", Tokens.sha256Hex(token), Timestamp.from(now)) {
            Session(it.getString("email"), it.getString("csrf"))
        }
    }

    fun logout(token: String) {
        db.transaction { c -> c.execute("DELETE FROM portal_sessions WHERE token_hash = ?", Tokens.sha256Hex(token)) }
    }

    /** Die Mitglieder hinter einer Adresse — meist eines, manchmal Vater und Sohn. */
    fun members(email: String): List<MemberLine> {
        val ids = memberIds(email).toSet()
        return if (ids.isEmpty()) emptyList() else reads.members().filter { it.id in ids }
    }

    private fun memberIds(email: String): List<UUID> = db.read { c ->
        c.query(
            "SELECT p.member_id FROM member_profiles p JOIN members m ON m.id = p.member_id WHERE lower(trim(p.email)) = lower(?) AND NOT m.deleted",
            email.trim()
        ) { it.getObject(1, UUID::class.java) }
    }

    // ------------------------------------------------------------- Aufladen

    /**
     * Legt eine Aufladung an und beim Anbieter die Zahlung dazu; liefert die Aufladung und die
     * Bezahlseite. [urls] baut aus der id der Aufladung die Adressen für Benachrichtigung und Rückkehr.
     */
    fun start(session: Session, memberId: UUID, amount: BigDecimal, urls: (UUID) -> Pair<String, String>): Pair<TopUp, String> {
        val online = settings.load().online
        if (!online.usable) throw AccountProblem("Online aufladen ist bei diesem Verein nicht eingeschaltet.")
        val member = members(session.email).firstOrNull { it.id == memberId } ?: throw AccountProblem("Dieser Deckel gehört nicht zu dieser Anmeldung.")
        val euros = amount.setScale(2, RoundingMode.HALF_UP)
        if (euros < BigDecimal(online.min) || euros > BigDecimal(online.max)) {
            throw AccountProblem("Aufladen geht mit ${euro(online.min.toDouble())} bis ${euro(online.max.toDouble())}.")
        }
        val id = UUID.randomUUID()
        db.transaction { c ->
            c.execute("INSERT INTO online_topups (id, member_id, amount, provider, status, email) VALUES (?, ?, ?, 'sumup', 'PENDING', ?)", id, memberId, euros, session.email)
        }
        val (notifyUrl, returnUrl) = urls(id)
        val checkout = try {
            payments.create(account(online), CheckoutRequest(id, euros, "Deckel aufladen: ${member.name}", notifyUrl, returnUrl))
        } catch (e: PaymentProblem) {
            // Der Grund ist für den Kassier (er steht in der Verwaltung bei der Aufladung), nicht für das Mitglied.
            finish(id, "FAILED", e.message.orEmpty())
            throw AccountProblem(if (e.retry) "SumUp ist gerade nicht erreichbar. Bitte in ein paar Minuten noch einmal." else "Online aufladen geht gerade nicht. Bitte an der Theke aufladen — der Kassier sieht den Grund in der Verwaltung.")
        }
        val payUrl = checkout.payUrl ?: run {
            db.transaction { c -> c.execute("UPDATE online_topups SET checkout_id = ? WHERE id = ?", checkout.id, id) }
            finish(id, "FAILED", "Der Anbieter nannte keine Bezahlseite.")
            throw AccountProblem("Der Anbieter nannte keine Bezahlseite.")
        }
        db.transaction { c -> c.execute("UPDATE online_topups SET checkout_id = ?, pay_url = ? WHERE id = ?", checkout.id, payUrl, id) }
        return topUp(id)!! to payUrl
    }

    /** Die Bezahlseite einer offenen Aufladung — für die Seite, die nach dem Absenden kommt. */
    fun payUrl(id: UUID): String? = db.read { c -> c.queryOne("SELECT pay_url FROM online_topups WHERE id = ? AND status = 'PENDING'", id) { it.getString(1) } }

    /**
     * Fragt beim Anbieter nach und bucht, wenn bezahlt. Was nicht zum Angelegten passt — Referenz,
     * Betrag, Währung, Konto —, wird nicht gebucht. Ist der Anbieter nicht erreichbar, bleibt die
     * Aufladung offen ([PaymentProblem] geht an den Aufrufer: Die Benachrichtigung soll es später noch
     * einmal versuchen).
     */
    fun settle(id: UUID, now: Instant = Instant.now()): TopUp? {
        val topUp = topUp(id) ?: return null
        if (!topUp.open) return topUp
        val checkoutId = db.read { c -> c.queryOne("SELECT checkout_id FROM online_topups WHERE id = ?", id) { it.getString(1) } } ?: return topUp
        val online = settings.load().online
        if (!online.configured) return topUp
        val checkout = payments.fetch(account(online), checkoutId)
        when (checkout.status) {
            Checkout.Status.PENDING -> return topUp
            Checkout.Status.FAILED, Checkout.Status.EXPIRED -> finish(id, checkout.status.name, "", now)
            Checkout.Status.PAID -> {
                val mismatch = listOfNotNull(
                    "Referenz".takeIf { checkout.reference != id.toString() },
                    "Betrag".takeIf { checkout.amount.compareTo(topUp.amount) != 0 },
                    "Währung".takeIf { checkout.currency != "EUR" },
                    "Konto".takeIf { !checkout.merchantCode.equals(online.merchantCode, ignoreCase = true) },
                )
                val detail = if (mismatch.isNotEmpty()) {
                    "Nicht gebucht, passt nicht zur Aufladung: ${mismatch.joinToString()}. Beim Anbieter nachsehen."
                } else try {
                    writes.bookTab(ONLINE, topUp.memberId, id, topUp.amount.toDouble(), "CARD", "Online-Aufladung", now)
                    listOfNotNull("SumUp", checkout.transactionCode).joinToString(" ")
                } catch (e: AccountProblem) {
                    "Bezahlt, aber nicht gebucht: ${e.message} Von Hand gutschreiben oder erstatten."
                }
                finish(id, if (mismatch.isEmpty()) "PAID" else "FAILED", detail, now)
            }
        }
        return topUp(id)
    }

    /** Für die Benachrichtigung des Anbieters, die nur die Checkout-ID kennt. False: unbekannt. */
    fun settleCheckout(checkoutId: String): Boolean {
        val id = db.read { c -> c.queryOne("SELECT id FROM online_topups WHERE checkout_id = ?", checkoutId) { it.getObject(1, UUID::class.java) } } ?: return false
        settle(id)
        return true
    }

    /**
     * Was offen geblieben ist, weil eine Benachrichtigung nicht ankam: nachfragen. Nach zwei Tagen
     * gilt eine Aufladung als nicht abgeschlossen — die Bezahlseite gilt ohnehin nur eine halbe Stunde.
     */
    fun sweep(now: Instant = Instant.now()) {
        val open = db.read { c ->
            c.query("SELECT id, created_at FROM online_topups WHERE status = 'PENDING' ORDER BY created_at") { it.getObject(1, UUID::class.java) to it.getTimestamp(2).toInstant() }
        }
        for ((id, created) in open) {
            if (Duration.between(created, now) > Duration.ofDays(2)) finish(id, "EXPIRED", "Beim Anbieter nicht abgeschlossen.", now)
            else runCatching { settle(id, now) }
        }
    }

    fun topUp(id: UUID): TopUp? = db.read { c -> c.queryOne("$TOP_UPS WHERE t.id = ?", id) { it.topUp() } }

    /** Die letzten Online-Aufladungen, für die Verwaltung. */
    fun recent(limit: Int): List<TopUp> = db.read { c -> c.query("$TOP_UPS ORDER BY t.created_at DESC LIMIT ?", limit) { it.topUp() } }

    /** Prüft Schlüssel und Händlercode bei SumUp und liefert, was dort außer der Karte freigeschaltet ist. */
    fun check(apiKey: String, merchantCode: String): List<String> = payments.methods(PaymentAccount(apiKey.trim(), merchantCode.trim().uppercase()))

    private fun finish(id: UUID, status: String, detail: String, now: Instant = Instant.now()) {
        db.transaction { c ->
            c.execute("UPDATE online_topups SET status = ?, detail = ?, settled_at = ? WHERE id = ? AND status = 'PENDING'", status, detail.take(300), Timestamp.from(now), id)
        }
    }

    private fun account(online: OnlineTopUp) = PaymentAccount(online.apiKey, online.merchantCode)

    private fun newToken(): String = ByteArray(32).also(random::nextBytes).let(encoder::encodeToString)

    private fun ResultSet.topUp() = TopUp(
        getObject("id", UUID::class.java), getObject("member_id", UUID::class.java), getString("member_name"), getBigDecimal("amount"),
        getString("status"), getString("email"), getString("detail"), getTimestamp("created_at").toInstant(), getTimestamp("settled_at")?.toInstant(),
    )

    companion object {
        val LINK_VALID: Duration = Duration.ofMinutes(30)
        val SESSION_VALID: Duration = Duration.ofDays(30)

        /** Wer im Protokoll steht, wenn eine Online-Aufladung gebucht wird: kein Benutzer, das Mitglied selbst. */
        val ONLINE = WebUser(WebUser.SYSTEM_ID, "online", "Online-Aufladung", Role.KASSIER, true, null, null)

        private const val TOP_UPS = "SELECT t.*, m.name AS member_name FROM online_topups t JOIN members m ON m.id = t.member_id"

        /** Wie die Zahlungsarten heißen, die SumUp meldet. Karte gibt es immer. */
        fun methodLabel(id: String): String = when (id.lowercase()) {
            "apple_pay" -> "Apple Pay"
            "google_pay" -> "Google Pay"
            "eps" -> "EPS"
            "paypal" -> "PayPal"
            "ideal" -> "iDEAL"
            "bancontact" -> "Bancontact"
            "blik" -> "BLIK"
            else -> id
        }
    }
}
