package com.example.vereins_kassensystem.server.web

import com.example.vereins_kassensystem.data.Ledger
import com.example.vereins_kassensystem.platform.Ids
import com.example.vereins_kassensystem.server.db.Database
import com.example.vereins_kassensystem.server.db.execute
import com.example.vereins_kassensystem.server.db.query
import com.example.vereins_kassensystem.server.db.queryOne
import com.example.vereins_kassensystem.ui.format.Money
import java.math.BigDecimal
import java.sql.Connection
import java.sql.Date
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class Profile(val memberId: UUID, val number: String, val email: String, val address: String, val consentEmail: Boolean, val notes: String) {
    val canEmail get() = consentEmail && email.contains('@')

    companion object {
        fun empty(memberId: UUID) = Profile(memberId, "", "", "", false, "")
    }
}

class StatementRun(val id: UUID, val label: String, val from: LocalDate, val to: LocalDate, val dueDate: LocalDate, val threshold: Double, val extraLabel: String, val extraAmount: Double, val createdAt: Instant, val createdBy: String, val count: Int, val sent: Int, val paid: Int, val amount: Double, val paidAmount: Double)

enum class StatementStatus { OPEN, PAID, CANCELLED }

class Statement(
    val id: UUID, val runId: UUID, val memberId: UUID, val memberName: String, val number: String,
    val opening: Double, val closing: Double, val amount: Double, val extraLabel: String, val extraAmount: Double,
    val dueDate: LocalDate, val status: StatementStatus, val sentAt: Instant?, val sentVia: String?, val sentTo: String,
    val remindedAt: Instant?, val reminderLevel: Int, val paidAt: LocalDate?, val createdAt: Instant,
) {
    fun overdue(today: LocalDate) = status == StatementStatus.OPEN && dueDate.isBefore(today)
}

class BankTransaction(val id: UUID, val bookingDate: LocalDate, val amount: Double, val counterparty: String, val reference: String, val status: String, val statementId: UUID?)

/**
 * Deckel abrechnen (Konzept 4.2): Aus einem Deckel im Minus wird ein Vorgang mit Nummer,
 * Zahlungsziel und Zahlungseingang. Die Beträge sind Schnappschüsse zum Stichtag — der Deckel
 * läuft weiter, die Abrechnung nicht. Eine Zahlung wird als gewöhnliche Aufladung gebucht
 * ([Writes.bookTab]) und erreicht die Tablets über den Abgleich; einen zweiten Kontostand
 * gibt es nicht.
 */
class Statements(private val db: Database, private val writes: Writes, private val zone: ZoneId) {

    // ----------------------------------------------------------------- Profile

    fun profile(memberId: UUID): Profile = db.read { c -> profile(c, memberId) }

    private fun profile(c: Connection, memberId: UUID): Profile =
        c.queryOne("SELECT * FROM member_profiles WHERE member_id = ?", memberId) { it.profile() } ?: Profile.empty(memberId)

    fun profiles(memberIds: Collection<UUID>): Map<UUID, Profile> = if (memberIds.isEmpty()) emptyMap() else db.read { c ->
        c.query("SELECT * FROM member_profiles WHERE member_id = ANY(?)", c.createArrayOf("uuid", memberIds.toTypedArray())) { it.profile() }.associateBy { it.memberId }
    }

    private fun ResultSet.profile() = Profile(getObject("member_id", UUID::class.java), getString("number"), getString("email"), getString("address"), getBoolean("consent_email"), getString("notes"))

    fun saveProfile(by: WebUser, memberId: UUID, number: String, email: String, address: String, consent: Boolean, notes: String) {
        val mail = email.trim()
        if (mail.isNotEmpty() && (!mail.contains('@') || mail.contains(' '))) throw AccountProblem("Das ist keine E-Mail-Adresse.")
        if (consent && mail.isEmpty()) throw AccountProblem("Einwilligung ohne Adresse — bitte die E-Mail-Adresse eintragen.")
        db.transaction { c ->
            val name = c.queryOne("SELECT name FROM members WHERE id = ? AND NOT deleted", memberId) { it.getString("name") } ?: throw AccountProblem("Dieses Mitglied gibt es nicht mehr.")
            c.execute(
                """
                INSERT INTO member_profiles (member_id, number, email, address, consent_email, notes, updated_at) VALUES (?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (member_id) DO UPDATE SET number = EXCLUDED.number, email = EXCLUDED.email, address = EXCLUDED.address,
                  consent_email = EXCLUDED.consent_email, notes = EXCLUDED.notes, updated_at = now()
                """.trimIndent(),
                memberId, number.trim().take(20), mail.take(120), address.trim().take(400), consent, notes.trim().take(1000)
            )
            AuditLog.record(c, by.id, by.displayName, "profile.update", name, if (consent) "E-Mail-Versand erlaubt" else "kein E-Mail-Versand")
        }
    }

    // ------------------------------------------------------------------ Läufe

    private val RUN = """
        SELECT r.*, (SELECT COUNT(*) FROM statements s WHERE s.run_id = r.id AND s.status <> 'CANCELLED') AS count,
               (SELECT COUNT(*) FROM statements s WHERE s.run_id = r.id AND s.status <> 'CANCELLED' AND s.sent_at IS NOT NULL) AS sent,
               (SELECT COUNT(*) FROM statements s WHERE s.run_id = r.id AND s.status = 'PAID') AS paid,
               (SELECT COALESCE(SUM(s.amount), 0) FROM statements s WHERE s.run_id = r.id AND s.status <> 'CANCELLED') AS amount,
               (SELECT COALESCE(SUM(s.amount), 0) FROM statements s WHERE s.run_id = r.id AND s.status = 'PAID') AS paid_amount
        FROM statement_runs r
    """.trimIndent()

    private fun ResultSet.run() = StatementRun(
        getObject("id", UUID::class.java), getString("label"), getDate("period_from").toLocalDate(), getDate("period_to").toLocalDate(), getDate("due_date").toLocalDate(),
        getDouble("threshold"), getString("extra_label"), getDouble("extra_amount"), getTimestamp("created_at").toInstant(), getString("created_by"),
        getInt("count"), getInt("sent"), getInt("paid"), getDouble("amount"), getDouble("paid_amount")
    )

    fun runs(): List<StatementRun> = db.read { c -> c.query("$RUN ORDER BY r.period_to DESC, r.created_at DESC") { it.run() } }

    fun run(id: UUID): StatementRun? = db.read { c -> c.queryOne("$RUN WHERE r.id = ?", id) { it.run() } }

    /** Der Tag nach dem letzten Stichtag — wo der nächste Lauf anfängt. */
    fun nextPeriodStart(fallback: LocalDate): LocalDate = db.read { c ->
        c.queryOne("SELECT MAX(period_to) AS d FROM statement_runs") { it.getDate("d")?.toLocalDate() }?.plusDays(1) ?: fallback
    }

    /** Wer mit dem Lauf abgerechnet würde — zur Vorschau, bevor etwas entsteht. */
    fun preview(to: LocalDate, threshold: Double): List<Pair<MemberLine, Double>> = db.read { c ->
        balancesAt(c, to.plusDays(1)).filter { it.second < threshold && it.second < 0 }
    }

    private fun balancesAt(c: Connection, before: LocalDate): List<Pair<MemberLine, Double>> = c.query(
        """
        SELECT m.id, m.name, m.nickname, k.name AS category, COALESCE(k.negative_balance_limit, 0) AS lim,
               COALESCE((SELECT SUM(e.balance_effect) FROM transaction_effects e WHERE e.member_id = m.id AND e.occurred_at < ?), 0) AS balance
        FROM members m LEFT JOIN member_categories k ON k.id = m.category_id AND NOT k.deleted
        WHERE NOT m.deleted ORDER BY balance, lower(m.name)
        """.trimIndent(), startOf(before)
    ) { MemberLine(it.getObject("id", UUID::class.java), it.getString("name"), it.getString("nickname"), it.getString("category"), it.getDouble("lim"), it.getDouble("balance"), null) to it.getDouble("balance") }

    class RunRequest(val label: String, val from: LocalDate, val to: LocalDate, val dueDate: LocalDate, val threshold: Double, val extraLabel: String, val extraAmount: Double, val includeAll: Boolean)

    /**
     * Erzeugt den Lauf und je Mitglied eine Abrechnung. Zu zahlen ist das Minus am Stichtag
     * plus Zusatzzeile; wer im Plus ist, bekommt mit [RunRequest.includeAll] trotzdem einen
     * Auszug — mit Betrag 0 oder nur der Zusatzzeile, etwa dem Semesterbeitrag.
     */
    fun createRun(by: WebUser, request: RunRequest): StatementRun {
        val label = request.label.trim().take(80).ifEmpty { throw AccountProblem("Der Lauf braucht einen Namen, etwa „Bierrechnung August“.") }
        if (request.to.isBefore(request.from)) throw AccountProblem("Der Stichtag liegt vor dem Beginn des Zeitraums.")
        if (request.dueDate.isBefore(request.to)) throw AccountProblem("Das Zahlungsziel liegt vor dem Stichtag.")
        if (request.extraAmount < 0 || request.extraAmount > 10_000) throw AccountProblem("Der Zusatzbetrag ist nicht plausibel.")
        if (request.extraAmount > 0 && request.extraLabel.isBlank()) throw AccountProblem("Wofür ist der Zusatzbetrag? Bitte eine Bezeichnung.")
        return db.transaction { c ->
            val runId = UUID.fromString(Ids.new())
            c.execute(
                "INSERT INTO statement_runs (id, label, period_from, period_to, due_date, threshold, extra_label, extra_amount, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                runId, label, Date.valueOf(request.from), Date.valueOf(request.to), Date.valueOf(request.dueDate), money(request.threshold), request.extraLabel.trim().take(80), money(request.extraAmount), by.displayName
            )
            val openings = balancesAt(c, request.from).associate { it.first.id to it.second }
            val closings = balancesAt(c, request.to.plusDays(1))
            var created = 0
            for ((member, closing) in closings) {
                val due = maxOf(0.0, -closing)
                if (!request.includeAll && !(closing < request.threshold && closing < 0)) continue
                val amount = Money.cents(due + request.extraAmount)
                val seq = c.queryOne("SELECT nextval('statement_seq') AS n") { it.getLong("n") }!!
                val number = "VD-%d%02d-%04d".format(request.to.year, request.to.monthValue, seq)
                c.execute(
                    "INSERT INTO statements (id, run_id, member_id, member_name, number, opening, closing, amount, extra_label, extra_amount, due_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.fromString(Ids.new()), runId, member.id, member.name, number, money(openings[member.id] ?: 0.0), money(closing), money(amount),
                    request.extraLabel.trim().take(80), money(request.extraAmount), Date.valueOf(request.dueDate)
                )
                created++
            }
            if (created == 0) throw AccountProblem("Niemand liegt unter der Schwelle — es gäbe keine einzige Abrechnung.")
            AuditLog.record(c, by.id, by.displayName, "statement.run", label, "$created Abrechnungen, Zahlungsziel ${request.dueDate}")
            c.queryOne("$RUN WHERE r.id = ?", runId) { it.run() }!!
        }
    }

    // ------------------------------------------------------------ Abrechnungen

    private fun ResultSet.statement() = Statement(
        getObject("id", UUID::class.java), getObject("run_id", UUID::class.java), getObject("member_id", UUID::class.java), getString("member_name"), getString("number"),
        getDouble("opening"), getDouble("closing"), getDouble("amount"), getString("extra_label"), getDouble("extra_amount"),
        getDate("due_date").toLocalDate(), StatementStatus.valueOf(getString("status")), getTimestamp("sent_at")?.toInstant(), getString("sent_via"), getString("sent_to"),
        getTimestamp("reminded_at")?.toInstant(), getInt("reminder_level"), getDate("paid_at")?.toLocalDate(), getTimestamp("created_at").toInstant()
    )

    fun statements(runId: UUID): List<Statement> = db.read { c -> c.query("SELECT * FROM statements WHERE run_id = ? ORDER BY amount DESC, member_name", runId) { it.statement() } }

    fun nicknameOf(memberId: UUID): String = db.read { c -> c.queryOne("SELECT nickname FROM members WHERE id = ?", memberId) { it.getString("nickname") } ?: "" }

    fun statement(id: UUID): Statement? = db.read { c -> c.queryOne("SELECT * FROM statements WHERE id = ?", id) { it.statement() } }

    fun openStatements(): List<Statement> = db.read { c -> c.query("SELECT * FROM statements WHERE status = 'OPEN' AND amount > 0 ORDER BY due_date, member_name") { it.statement() } }

    fun statementsOf(memberId: UUID, limit: Int): List<Statement> = db.read { c -> c.query("SELECT * FROM statements WHERE member_id = ? ORDER BY created_at DESC LIMIT ?", memberId, limit) { it.statement() } }

    /** Die Zeilen des Zeitraums, für den Auszug: neueste zuletzt, mit Stand danach. */
    fun lines(s: Statement, run: StatementRun): List<StatementLine> = db.read { c ->
        c.query(
            """
            SELECT e.occurred_at, e.balance_effect,
                   CASE WHEN e.product_ref IN (?::uuid, ?::uuid) THEN e.product_name ELSE e.quantity || ' × ' || e.product_name END
                     || CASE WHEN e.is_refund THEN ' (Storno)' ELSE '' END AS label,
                   ? + SUM(e.balance_effect) OVER (ORDER BY e.occurred_at, e.id) AS after
            FROM transaction_effects e WHERE e.member_id = ? AND e.occurred_at >= ? AND e.occurred_at < ? AND e.balance_effect <> 0
            ORDER BY e.occurred_at, e.id
            """.trimIndent(),
            Ledger.TOPUP_REF, Ledger.MANUAL_REF, money(s.opening), s.memberId, startOf(run.from), startOf(run.to.plusDays(1))
        ) { StatementLine(it.getTimestamp("occurred_at").toInstant(), it.getString("label"), it.getDouble("balance_effect"), it.getDouble("after")) }
    }

    fun markSent(by: WebUser?, id: UUID, via: String, to: String, now: Instant = Instant.now()) = db.transaction { c ->
        c.execute("UPDATE statements SET sent_at = ?, sent_via = ?, sent_to = ? WHERE id = ? AND status = 'OPEN'", Timestamp.from(now), via, to.take(120), id)
        if (by != null) AuditLog.record(c, by.id, by.displayName, "statement.sent", numberOf(c, id), if (via == "EMAIL") "per E-Mail an $to" else "gedruckt")
    }

    fun markReminded(by: WebUser, id: UUID, via: String, now: Instant = Instant.now()) = db.transaction { c ->
        c.execute("UPDATE statements SET reminded_at = ?, reminder_level = reminder_level + 1 WHERE id = ? AND status = 'OPEN'", Timestamp.from(now), id)
        AuditLog.record(c, by.id, by.displayName, "statement.reminded", numberOf(c, id), via)
    }

    fun cancel(by: WebUser, id: UUID, reason: String) {
        if (reason.trim().length < 3) throw AccountProblem("Ein Storno braucht einen Grund.")
        db.transaction { c ->
            val s = c.queryOne("SELECT * FROM statements WHERE id = ? FOR UPDATE", id) { it.statement() } ?: throw AccountProblem("Diese Abrechnung gibt es nicht.")
            if (s.status == StatementStatus.PAID) throw AccountProblem("Eine bezahlte Abrechnung wird nicht storniert; die Zahlung steht als Aufladung auf dem Deckel.")
            c.execute("UPDATE statements SET status = 'CANCELLED' WHERE id = ?", id)
            AuditLog.record(c, by.id, by.displayName, "statement.cancelled", s.number, reason.trim().take(200))
        }
    }

    /**
     * Ein Zahlungseingang: als Aufladung gebucht (kommt auf die Tablets), Abrechnung bezahlt.
     * [bankId] verknüpft den importierten Umsatz, wenn er von dort kommt. Der Buchungsschlüssel
     * ist aus der Abrechnung abgeleitet: dieselbe Abrechnung wird nie zweimal bezahlt gebucht.
     */
    fun recordPayment(by: WebUser, statementId: UUID, amount: Double, kind: TopUpKind, paidAt: LocalDate, bankId: UUID? = null): Boolean {
        val s = statement(statementId) ?: throw AccountProblem("Diese Abrechnung gibt es nicht.")
        if (s.status != StatementStatus.OPEN) throw AccountProblem("Die Abrechnung ${s.number} ist ${if (s.status == StatementStatus.PAID) "schon bezahlt" else "storniert"}.")
        if (amount <= 0) throw AccountProblem("Der Zahlungsbetrag muss größer als null sein.")
        val paymentId = UUID.nameUUIDFromBytes("payment:${s.id}".toByteArray())
        val booked = writes.bookTab(by, s.memberId, paymentId, amount, kind.paymentType, "Zahlung ${s.number}", paidAt.atStartOfDay(zone).toInstant().let { if (it.isAfter(Instant.now())) Instant.now() else it })
        db.transaction { c ->
            c.execute("UPDATE statements SET status = 'PAID', paid_at = ?, payment_id = ? WHERE id = ?", Date.valueOf(paidAt), paymentId, s.id)
            if (bankId != null) c.execute("UPDATE bank_transactions SET status = 'MATCHED', statement_id = ?, payment_id = ? WHERE id = ?", s.id, paymentId, bankId)
            AuditLog.record(c, by.id, by.displayName, "statement.paid", s.number, "${Money.format(amount)} ${kind.label}${if (kotlin.math.abs(amount - s.amount) > 0.005) " (gefordert ${Money.format(s.amount)})" else ""}")
        }
        return booked
    }

    // ------------------------------------------------------------ Bankumsätze

    fun allBankTransactions(limit: Int): List<BankTransaction> = db.read { c ->
        c.query("SELECT * FROM bank_transactions ORDER BY booking_date DESC, imported_at DESC LIMIT ?", limit) { it.bank() }
    }

    fun openBankTransactions(): List<BankTransaction> = db.read { c ->
        c.query("SELECT * FROM bank_transactions WHERE status = 'OPEN' ORDER BY booking_date DESC, imported_at DESC") { it.bank() }
    }

    private fun ResultSet.bank() = BankTransaction(getObject("id", UUID::class.java), getDate("booking_date").toLocalDate(), getDouble("amount"), getString("counterparty"), getString("reference"), getString("status"), getObject("statement_id", UUID::class.java))

    class ImportResult(val imported: Int, val duplicates: Int, val matched: Int, val ignored: Int)

    /**
     * Nimmt die Zeilen eines Auszugs auf. Was schon da war (gleicher Fingerabdruck), bleibt
     * liegen; Belastungen werden ohne Nachfrage als „nicht zuzuordnen" abgelegt; Gutschriften,
     * deren Text eine offene Abrechnungsnummer enthält, sind damit bezahlt.
     */
    fun importBank(by: WebUser, rows: List<BankImport.Row>): ImportResult {
        var imported = 0; var duplicates = 0; var matched = 0; var ignored = 0
        val open = openStatements().associateBy { it.number.uppercase() }
        for (row in rows) {
            val id = UUID.fromString(Ids.new())
            val inserted = db.transaction { c ->
                c.execute(
                    "INSERT INTO bank_transactions (id, fingerprint, booking_date, amount, counterparty, reference, status, imported_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (fingerprint) DO NOTHING",
                    id, row.fingerprint, Date.valueOf(row.date), money(row.amount), row.counterparty.take(140), row.reference.take(400), if (row.amount <= 0) "IGNORED" else "OPEN", by.displayName
                ) > 0
            }
            if (!inserted) { duplicates++; continue }
            imported++
            if (row.amount <= 0) { ignored++; continue }
            val hit = open.values.firstOrNull { row.reference.uppercase().replace(" ", "").contains(it.number.replace(" ", "")) }
            if (hit != null && recordPayment(by, hit.id, row.amount, TopUpKind.BANK, row.date, id)) matched++
        }
        db.transaction { c -> AuditLog.record(c, by.id, by.displayName, "bank.import", "", "$imported neu, $duplicates schon bekannt, $matched zugeordnet") }
        return ImportResult(imported, duplicates, matched, ignored)
    }

    fun assignBank(by: WebUser, bankId: UUID, statementId: UUID): Boolean {
        val row = db.read { c -> c.queryOne("SELECT * FROM bank_transactions WHERE id = ? AND status = 'OPEN'", bankId) { it.bank() } } ?: throw AccountProblem("Dieser Umsatz ist schon zugeordnet.")
        return recordPayment(by, statementId, row.amount, TopUpKind.BANK, row.bookingDate, bankId)
    }

    fun ignoreBank(by: WebUser, bankId: UUID) = db.transaction { c ->
        c.execute("UPDATE bank_transactions SET status = 'IGNORED' WHERE id = ? AND status = 'OPEN'", bankId)
        AuditLog.record(c, by.id, by.displayName, "bank.ignored", bankId.toString().take(8))
    }

    private fun numberOf(c: Connection, id: UUID) = c.queryOne("SELECT number FROM statements WHERE id = ?", id) { it.getString("number") } ?: ""
    private fun startOf(day: LocalDate): Timestamp = Timestamp.from(day.atStartOfDay(zone).toInstant())
    private fun money(v: Double): BigDecimal = BigDecimal.valueOf(Money.cents(v))
}
