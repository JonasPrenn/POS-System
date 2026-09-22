package com.example.vereins_kassensystem.server.web

import com.example.vereins_kassensystem.data.Ledger
import com.example.vereins_kassensystem.server.db.Database
import com.example.vereins_kassensystem.server.db.execute
import com.example.vereins_kassensystem.server.db.query
import com.example.vereins_kassensystem.server.db.queryOne
import com.example.vereins_kassensystem.ui.format.Money
import java.sql.Connection
import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneId

/** Eine Zeile der Einnahmen-Ausgaben-Rechnung: ein Konto, sein Bereich, der Betrag im Jahr und im Jahr davor. */
class BookLine(val code: String, val name: String, val area: String, val income: Boolean, val amount: Double, val previous: Double)

/** Das Rechnungsjahr: erster Tag, erster Tag danach, und ob es noch läuft. */
class FiscalYear(val label: Int, val from: LocalDate, val to: LocalDate) {
    fun contains(day: LocalDate) = !day.isBefore(from) && day.isBefore(to)
}

class YearBooks(val year: FiscalYear, val previous: FiscalYear, val lines: List<BookLine>, val onTab: Double, val onTabPrevious: Double) {
    val income get() = lines.filter { it.income }.sumOf { it.amount }
    val expense get() = lines.filter { !it.income }.sumOf { it.amount }
    val incomePrevious get() = lines.filter { it.income }.sumOf { it.previous }
    val expensePrevious get() = lines.filter { !it.income }.sumOf { it.previous }
}

/** Die Vermögensübersicht zum Stichtag — jede Zahl mit dem, woraus sie kommt. */
class Assets(
    val asOf: LocalDate, val cash: Double, val cashDetail: String, val bank: Double?, val stockValue: Double?,
    val receivables: Double, val memberCredits: Double, val openInvoices: Double,
) {
    val total get() = cash + (bank ?: 0.0) + (stockValue ?: 0.0) + receivables - memberCredits - openInvoices
}

class JournalEntry(val day: LocalDate, val account: String, val area: String, val text: String, val amount: Double, val reference: String)

/**
 * Die Bücher (Konzept 4.6): eine Einnahmen-Ausgaben-Rechnung nach dem Zufluss-Abfluss-Prinzip,
 * hergeleitet aus dem, was die Tablets buchen und die Verwaltung an Belegen führt — nicht aus
 * einer zweiten Buchhaltung. Einnahme ist, was bar, mit Karte oder per Überweisung eingegangen
 * ist: Verkäufe an der Theke, Aufladungen und Zahlungen auf Abrechnungen, Trinkgeld. Was auf
 * den Deckel geschrieben wurde, ist eine Forderung und steht in der Vermögensübersicht, nicht
 * in den Einnahmen. Ausgabe ist ein bezahlter Beleg zum Zahltag; Lagerzeilen ohne eigenes
 * Konto gelten als Getränkeeinkauf, Belegzeilen tragen ihr Konto. Kein Konto für doppelte
 * Buchführung, keine Abschreibung, kein Lohn — das ist der Steuerberater.
 */
class Books(private val db: Database, private val zone: ZoneId) {

    fun fiscalYear(label: Int, startMonth: Int): FiscalYear {
        val from = LocalDate.of(label, startMonth, 1)
        return FiscalYear(label, from, from.plusYears(1))
    }

    fun year(year: FiscalYear, previous: FiscalYear): YearBooks = db.read { c ->
        val now = amounts(c, year)
        val before = amounts(c, previous)
        val accounts = c.query("SELECT code, name, area, kind FROM accounts WHERE active ORDER BY sort") { listOf(it.getString("code"), it.getString("name"), it.getString("area"), it.getString("kind")) }
        val lines = accounts.map { (code, name, area, kind) -> BookLine(code, name, area, kind == "INCOME", now[code] ?: 0.0, before[code] ?: 0.0) } +
            BookLine(TIPS, "Trinkgeld", "BUDE", true, now[TIPS] ?: 0.0, before[TIPS] ?: 0.0)
        YearBooks(year, previous, lines.filter { it.amount != 0.0 || it.previous != 0.0 || it.code in ALWAYS }, onTab(c, year), onTab(c, previous))
    }

    /** Beträge je Konto im Zeitraum, Einnahmen positiv wie Ausgaben — die Seite kennt das Vorzeichen über das Konto. */
    private fun amounts(c: Connection, y: FiscalYear): Map<String, Double> {
        val from = startOf(y.from); val to = startOf(y.to)
        val result = HashMap<String, Double>()
        // Einnahmen aus den Buchungen: bar und Karte an der Theke, Aufladungen bar, Karte, Überweisung.
        c.query(
            """
            SELECT SUM(CASE WHEN payment_type IN ('CASH', 'CARD') THEN revenue ELSE 0 END) AS sales,
                   SUM(CASE WHEN product_ref = ?::uuid AND payment_type IN ('CASH', 'CARD', 'BANK') THEN price * quantity * CASE WHEN is_refund THEN -1 ELSE 1 END ELSE 0 END) AS top_ups,
                   SUM(CASE WHEN product_ref = ?::uuid AND payment_type IN ('CASH', 'CARD') THEN price * quantity * CASE WHEN is_refund THEN -1 ELSE 1 END ELSE 0 END) AS tips
            FROM transaction_effects WHERE occurred_at >= ? AND occurred_at < ?
            """.trimIndent(), Ledger.TOPUP_REF, Ledger.TIP_REF, from, to
        ) { result["E-BUDE"] = it.getDouble("sales"); result["E-AUFL"] = it.getDouble("top_ups"); result[TIPS] = it.getDouble("tips") }
        // Ausgaben: bezahlte Belege zum Zahltag — Belegzeilen auf ihr Konto, der Rest des Bruttobetrags als Getränkeeinkauf.
        c.query(
            """
            SELECT a.code, SUM(l.amount) AS total FROM purchase_lines l JOIN accounts a ON a.id = l.account_id
            JOIN purchase_documents p ON p.id = l.document_id
            WHERE p.payment <> 'OPEN' AND p.paid_at >= ? AND p.paid_at < ? GROUP BY a.code
            """.trimIndent(), java.sql.Date.valueOf(y.from), java.sql.Date.valueOf(y.to)
        ) { result.merge(it.getString("code"), it.getDouble("total"), Double::plus) }
        c.queryOne(
            """
            SELECT COALESCE(SUM(p.gross - COALESCE((SELECT SUM(l.amount) FROM purchase_lines l WHERE l.document_id = p.id), 0)), 0) AS rest
            FROM purchase_documents p WHERE p.payment <> 'OPEN' AND p.gross IS NOT NULL AND p.paid_at >= ? AND p.paid_at < ?
            """.trimIndent(), java.sql.Date.valueOf(y.from), java.sql.Date.valueOf(y.to)
        ) { result.merge("A-GETR", it.getDouble("rest"), Double::plus) }
        // Wareneingang vom Tablet ohne Beleg in der Verwaltung: mit Betrag gilt er als bar bezahlt am Tag des Eingangs.
        c.queryOne(
            """
            SELECT COALESCE(SUM(d.receipt_total), 0) AS total FROM deliveries d
            WHERE NOT d.deleted AND d.receipt_total IS NOT NULL AND d.occurred_at >= ? AND d.occurred_at < ?
              AND NOT EXISTS (SELECT 1 FROM purchase_documents p WHERE p.delivery_id = d.id)
            """.trimIndent(), from, to
        ) { result.merge("A-GETR", it.getDouble("total"), Double::plus) }
        return result.mapValues { Money.cents(it.value) }
    }

    /** Was im Jahr auf Deckel geschrieben wurde: kein Zufluss, aber die Zahl, die der Kassier neben den Einnahmen erwartet. */
    private fun onTab(c: Connection, y: FiscalYear): Double =
        c.queryOne("SELECT COALESCE(SUM(revenue), 0) AS v FROM transaction_effects WHERE payment_type = ? AND occurred_at >= ? AND occurred_at < ?", Ledger.MEMBER_BALANCE, startOf(y.from), startOf(y.to)) { it.getDouble("v") } ?: 0.0

    /**
     * Die Vermögensübersicht zum Stichtag: Kassabestand aus der letzten Zählung je Gerät, Bankstand wie
     * eingetragen, Lagerwert nur für heute (die Vergangenheit rechnet niemand zurück), Forderungen und
     * Guthaben aus den Buchungen bis zum Stichtag, offene Belege zum Stichtag.
     */
    fun assets(asOf: LocalDate, today: LocalDate, bankBalance: Double?, stockValueToday: Double?): Assets = db.read { c ->
        val cutoff = startOf(asOf.plusDays(1))
        val counts = c.query(
            """
            SELECT DISTINCT ON (s.origin_device) COALESCE(d.label, s.device_label) AS device, s.closing_count, s.closed_at,
                   s.opening_count, s.opened_at,
                   (SELECT COALESCE(SUM(m.amount * CASE WHEN m.kind = 'DEPOSIT' THEN 1 ELSE -1 END), 0) FROM cash_movements m WHERE m.session_id = s.id AND NOT m.deleted AND m.occurred_at < ?) AS moved,
                   (SELECT COALESCE(SUM(CASE WHEN e.payment_type = 'CASH' THEN
                        (CASE WHEN e.product_ref IN (?::uuid, ?::uuid) THEN e.price * e.quantity ELSE e.price * e.quantity - e.discount_amount END)
                        * CASE WHEN e.is_refund THEN -1 ELSE 1 END ELSE 0 END), 0) FROM transaction_effects e
                     WHERE e.origin_device = s.origin_device AND e.occurred_at >= s.opened_at AND e.occurred_at < ? AND (s.closed_at IS NULL OR e.occurred_at < s.closed_at)) AS cash_in
            FROM cash_sessions s LEFT JOIN devices d ON d.id = s.origin_device
            WHERE NOT s.deleted AND s.opened_at < ? ORDER BY s.origin_device, s.opened_at DESC
            """.trimIndent(), cutoff, Ledger.TOPUP_REF, Ledger.TIP_REF, cutoff, cutoff
        ) { r ->
            val closedAt = r.getTimestamp("closed_at")
            val closedBefore = closedAt != null && closedAt.toInstant().isBefore(cutoff.toInstant())
            val amount = if (closedBefore) r.getDouble("closing_count") else r.getDouble("opening_count") + r.getDouble("cash_in") + r.getDouble("moved")
            Triple(r.getString("device"), amount, closedBefore)
        }
        val cash = counts.sumOf { it.second }
        val detail = if (counts.isEmpty()) "noch keine Schicht gezählt" else counts.joinToString(" · ") { "${it.first} ${euro(it.second)}${if (it.third) "" else " (laufend)"}" }
        val balances = c.query("SELECT member_id, SUM(balance_effect) AS b FROM transaction_effects WHERE member_id IS NOT NULL AND occurred_at < ? GROUP BY member_id", cutoff) { it.getDouble("b") }
        val open = c.queryOne(
            "SELECT COALESCE(SUM(gross), 0) AS v FROM purchase_documents WHERE gross IS NOT NULL AND document_date <= ? AND (payment = 'OPEN' OR paid_at IS NULL OR paid_at > ?)",
            java.sql.Date.valueOf(asOf), java.sql.Date.valueOf(asOf)
        ) { it.getDouble("v") } ?: 0.0
        Assets(
            asOf, Money.cents(cash), detail, bankBalance, if (asOf >= today) stockValueToday else null,
            Money.cents(-balances.filter { it < 0 }.sum()), Money.cents(balances.filter { it > 0 }.sum()), Money.cents(open)
        )
    }

    /** Das Journal fürs CSV: Einnahmen je Tag und Konto, Ausgaben je Beleg — mit dem, was als Beleg dient. */
    fun journal(y: FiscalYear): List<JournalEntry> = db.read { c ->
        val from = startOf(y.from); val to = startOf(y.to)
        val entries = ArrayList<JournalEntry>()
        c.query(
            """
            SELECT (occurred_at AT TIME ZONE ?)::date AS day, payment_type,
                   SUM(CASE WHEN payment_type IN ('CASH', 'CARD') THEN revenue ELSE 0 END) AS sales,
                   SUM(CASE WHEN product_ref = ?::uuid THEN price * quantity * CASE WHEN is_refund THEN -1 ELSE 1 END ELSE 0 END) AS top_ups,
                   SUM(CASE WHEN product_ref = ?::uuid AND payment_type IN ('CASH', 'CARD') THEN price * quantity * CASE WHEN is_refund THEN -1 ELSE 1 END ELSE 0 END) AS tips
            FROM transaction_effects WHERE occurred_at >= ? AND occurred_at < ? AND payment_type IN ('CASH', 'CARD', 'BANK') GROUP BY 1, 2 ORDER BY 1, 2
            """.trimIndent(), zone.id, Ledger.TOPUP_REF, Ledger.TIP_REF, from, to
        ) { r ->
            val day = r.getDate("day").toLocalDate(); val type = PAYMENT[r.getString("payment_type")] ?: r.getString("payment_type")
            if (r.getDouble("sales") != 0.0) entries += JournalEntry(day, "E-BUDE", "BUDE", "Tageslosung $type", Money.cents(r.getDouble("sales")), "Kassenbuch")
            if (r.getDouble("top_ups") != 0.0) entries += JournalEntry(day, "E-AUFL", "BUDE", "Aufladungen und Zahlungen $type", Money.cents(r.getDouble("top_ups")), "Deckelkonten")
            if (r.getDouble("tips") != 0.0) entries += JournalEntry(day, TIPS, "BUDE", "Trinkgeld $type", Money.cents(r.getDouble("tips")), "Kassenbuch")
        }
        c.query(
            """
            SELECT p.paid_at, p.number, p.supplier_name, p.gross, p.payment,
                   COALESCE((SELECT SUM(l.amount) FROM purchase_lines l WHERE l.document_id = p.id), 0) AS lines
            FROM purchase_documents p WHERE p.payment <> 'OPEN' AND p.gross IS NOT NULL AND p.paid_at >= ? AND p.paid_at < ? ORDER BY p.paid_at
            """.trimIndent(), java.sql.Date.valueOf(y.from), java.sql.Date.valueOf(y.to)
        ) { r ->
            val rest = r.getDouble("gross") - r.getDouble("lines")
            val ref = listOf(r.getString("supplier_name"), r.getString("number")).filter { it.isNotBlank() }.joinToString(" ")
            if (rest != 0.0) entries += JournalEntry(r.getDate("paid_at").toLocalDate(), "A-GETR", "BUDE", "Wareneinkauf ${PAYMENT[r.getString("payment")] ?: ""}".trim(), -Money.cents(rest), ref)
        }
        c.query(
            """
            SELECT p.paid_at, p.number, p.supplier_name, p.payment, l.label, l.amount, a.code, a.area
            FROM purchase_lines l JOIN accounts a ON a.id = l.account_id JOIN purchase_documents p ON p.id = l.document_id
            WHERE p.payment <> 'OPEN' AND p.paid_at >= ? AND p.paid_at < ? ORDER BY p.paid_at, l.sort
            """.trimIndent(), java.sql.Date.valueOf(y.from), java.sql.Date.valueOf(y.to)
        ) { r ->
            val ref = listOf(r.getString("supplier_name"), r.getString("number")).filter { it.isNotBlank() }.joinToString(" ")
            entries += JournalEntry(r.getDate("paid_at").toLocalDate(), r.getString("code"), r.getString("area"), r.getString("label"), -Money.cents(r.getDouble("amount")), ref)
        }
        c.query(
            """
            SELECT (d.occurred_at AT TIME ZONE ?)::date AS day, d.supplier, d.receipt_total FROM deliveries d
            WHERE NOT d.deleted AND d.receipt_total IS NOT NULL AND d.occurred_at >= ? AND d.occurred_at < ?
              AND NOT EXISTS (SELECT 1 FROM purchase_documents p WHERE p.delivery_id = d.id) ORDER BY 1
            """.trimIndent(), zone.id, from, to
        ) { r -> entries += JournalEntry(r.getDate("day").toLocalDate(), "A-GETR", "BUDE", "Wareneingang am Tablet, ohne Beleg", -Money.cents(r.getDouble("receipt_total")), r.getString("supplier")) }
        entries.sortedWith(compareBy({ it.day }, { it.account }))
    }

    // ------------------------------------------------------------- Bankstand

    fun bankBalance(year: Int): Double? = db.read { c -> c.queryOne("SELECT value FROM settings WHERE key = ?", "books.bank.$year") { it.getString("value").toDoubleOrNull() } }

    fun saveBankBalance(by: WebUser, year: Int, text: String) {
        val value = if (text.isBlank()) null else Money.parse(text.trim().replace('−', '-')) ?: throw AccountProblem("Den Bankstand bitte als Zahl, etwa 1250,40.")
        db.transaction { c ->
            if (value == null) c.execute("DELETE FROM settings WHERE key = ?", "books.bank.$year")
            else c.execute("INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value", "books.bank.$year", Money.cents(value).toString())
            AuditLog.record(c, by.id, by.displayName, "books.bank", year.toString(), value?.let(::euro) ?: "gelöscht")
        }
    }

    private fun startOf(day: LocalDate): Timestamp = Timestamp.from(day.atStartOfDay(zone).toInstant())

    companion object {
        const val TIPS = "E-TRINK"
        private val ALWAYS = setOf("E-BUDE", "E-AUFL", "A-GETR")
        private val PAYMENT = mapOf("CASH" to "bar", "CARD" to "Karte", "BANK" to "Überweisung")
        val AREAS = linkedMapOf("BUDE" to "Budenbetrieb", "VEREIN" to "Vereinsleben", "FEST" to "Veranstaltungen")
    }
}
