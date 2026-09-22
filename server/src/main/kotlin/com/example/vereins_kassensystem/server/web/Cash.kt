package com.example.vereins_kassensystem.server.web

import com.example.vereins_kassensystem.data.Ledger
import com.example.vereins_kassensystem.server.db.Database
import com.example.vereins_kassensystem.server.db.query
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class CashSessionLine(
    val id: UUID, val device: String, val openedAt: Instant, val openedBy: String, val openingCount: Double,
    val closedAt: Instant?, val closedBy: String?, val closingCount: Double?, val note: String?,
    /** Bareinnahmen des Geräts in der Schicht, Entnahmen, Einlagen — aus den Buchungen, nicht gespeichert. */
    val cashIn: Double, val withdrawals: Double, val deposits: Double,
) {
    val expected: Double get() = openingCount + cashIn + deposits - withdrawals
    val difference: Double? get() = closingCount?.let { it - expected }
}

class CashMovementLine(val at: Instant, val sessionId: UUID, val device: String, val kind: String, val amount: Double, val reason: String, val by: String)

/** Eine Zeile im Kassenbuch: chronologisch, mit dem Bestand danach, nur Barbewegungen. */
class CashBookEntry(val at: Instant, val device: String, val text: String, val detail: String, val kind: String, val amount: Double?, val balance: Double?)

class DeviceDay(val device: String, val revenue: Revenue, val topUpCash: Double, val refunds: Double)

/**
 * Das Kassenbuch (Konzept 4.5), zusammengesetzt aus dem, was die Tablets melden: Zählungen
 * beim Öffnen und Schließen, Bareinnahmen je Gerät und Schicht (`transaction_effects` nach
 * `origin_device`), Entnahmen und Einlagen. Dazu, was die Verwaltung bar verbucht: Aufladungen
 * am Schreibtisch und Belege, die bar aus der Kasse bezahlt wurden. Nichts davon lässt sich
 * hier ändern — ein Fehler bekommt eine Gegenbuchung am Tablet.
 */
class Cash(private val db: Database, private val zone: ZoneId) {

    private fun startOf(day: LocalDate): Timestamp = Timestamp.from(day.atStartOfDay(zone).toInstant())

    /** Was ein Gerät bar eingenommen hat: Verkäufe nach Rabatt, Aufladungen, Trinkgeld; Stornos ziehen ab. */
    private val CASH_IN = """
        COALESCE(SUM(CASE WHEN e.payment_type = 'CASH' THEN
          (CASE WHEN e.product_ref IN ('${Ledger.TOPUP_REF}'::uuid, '${Ledger.TIP_REF}'::uuid) THEN e.price * e.quantity ELSE e.price * e.quantity - e.discount_amount END)
          * CASE WHEN e.is_refund THEN -1 ELSE 1 END ELSE 0 END), 0)
    """.trimIndent()

    fun sessions(from: LocalDate, toInclusive: LocalDate, openToo: Boolean = true): List<CashSessionLine> = db.read { c ->
        c.query(
            """
            SELECT s.*, COALESCE(d.label, s.device_label) AS device,
                   (SELECT $CASH_IN FROM transaction_effects e WHERE e.origin_device = s.origin_device AND e.occurred_at >= s.opened_at AND (s.closed_at IS NULL OR e.occurred_at < s.closed_at)) AS cash_in,
                   (SELECT COALESCE(SUM(amount), 0) FROM cash_movements m WHERE m.session_id = s.id AND NOT m.deleted AND m.kind = 'WITHDRAWAL') AS withdrawals,
                   (SELECT COALESCE(SUM(amount), 0) FROM cash_movements m WHERE m.session_id = s.id AND NOT m.deleted AND m.kind = 'DEPOSIT') AS deposits
            FROM cash_sessions s LEFT JOIN devices d ON d.id = s.origin_device
            WHERE NOT s.deleted AND ((s.opened_at >= ? AND s.opened_at < ?) OR (? AND s.closed_at IS NULL))
            ORDER BY s.opened_at DESC
            """.trimIndent(), startOf(from), startOf(toInclusive.plusDays(1)), openToo
        ) {
            CashSessionLine(
                it.getObject("id", UUID::class.java), it.getString("device"), it.getTimestamp("opened_at").toInstant(), it.getString("opened_by"), it.getDouble("opening_count"),
                it.getTimestamp("closed_at")?.toInstant(), it.getString("closed_by"), it.getBigDecimal("closing_count")?.toDouble(), it.getString("note"),
                it.getDouble("cash_in"), it.getDouble("withdrawals"), it.getDouble("deposits")
            )
        }
    }

    fun movements(from: LocalDate, toInclusive: LocalDate): List<CashMovementLine> = db.read { c ->
        c.query(
            """
            SELECT m.occurred_at, m.session_id, m.kind, m.amount, m.reason, m.by_name, COALESCE(d.label, s.device_label) AS device
            FROM cash_movements m JOIN cash_sessions s ON s.id = m.session_id LEFT JOIN devices d ON d.id = s.origin_device
            WHERE NOT m.deleted AND m.occurred_at >= ? AND m.occurred_at < ? ORDER BY m.occurred_at
            """.trimIndent(), startOf(from), startOf(toInclusive.plusDays(1))
        ) { CashMovementLine(it.getTimestamp("occurred_at").toInstant(), it.getObject("session_id", UUID::class.java), it.getString("device"), it.getString("kind"), it.getDouble("amount"), it.getString("reason"), it.getString("by_name")) }
    }

    /**
     * Tagesbericht: Umsatz nach Zahlart je Gerät, dazu Aufladungen in bar und Stornos. Ein
     * Gerät ohne eines davon — etwa die Verwaltung mit einer Aufladung per Überweisung —
     * bleibt weg, eine Zeile aus Nullen sagt nichts.
     */
    fun dayReport(day: LocalDate): List<DeviceDay> = db.read { c ->
        c.query(
            """
            SELECT COALESCE(d.label, 'Verwaltung') AS device, e.payment_type, SUM(e.revenue) AS revenue,
                   SUM(CASE WHEN e.product_ref = '${Ledger.TOPUP_REF}'::uuid AND e.payment_type = 'CASH' THEN e.price * e.quantity * CASE WHEN e.is_refund THEN -1 ELSE 1 END ELSE 0 END) AS top_up_cash,
                   SUM(CASE WHEN e.is_refund AND e.product_ref <> '${Ledger.TOPUP_REF}'::uuid THEN e.price * e.quantity - e.discount_amount ELSE 0 END) AS refunds
            FROM transaction_effects e LEFT JOIN devices d ON d.id = e.origin_device
            WHERE e.occurred_at >= ? AND e.occurred_at < ? AND e.payment_type <> 'CORRECTION'
            GROUP BY 1, 2 ORDER BY 1
            """.trimIndent(), startOf(day), startOf(day.plusDays(1))
        ) { listOf(it.getString("device"), it.getString("payment_type"), it.getDouble("revenue"), it.getDouble("top_up_cash"), it.getDouble("refunds")) }
            .groupBy { it[0] as String }
            .map { (device, rows) ->
                DeviceDay(
                    device,
                    rows.fold(Revenue()) { acc, r -> acc + when (r[1]) { "CASH" -> Revenue(cash = r[2] as Double); "CARD" -> Revenue(card = r[2] as Double); Ledger.MEMBER_BALANCE -> Revenue(tab = r[2] as Double); else -> Revenue() } },
                    rows.sumOf { it[3] as Double }, rows.sumOf { it[4] as Double }
                )
            }
            .filter { it.revenue.total != 0.0 || it.topUpCash != 0.0 || it.refunds != 0.0 }
    }

    /**
     * Das Kassenbuch eines Zeitraums, chronologisch. Je Schicht: Öffnung mit Zählung, die
     * Bareinnahmen bis zum Schluss als eine Zeile, Entnahmen und Einlagen einzeln, Schluss mit
     * Zählung und Differenz. Der Bestand danach wird je Gerät fortgeführt — von der Zählung
     * aus, nicht aus einem gespeicherten Zähler.
     */
    fun book(from: LocalDate, toInclusive: LocalDate): List<CashBookEntry> {
        val sessions = sessions(from, toInclusive, openToo = false).sortedBy { it.openedAt }
        val movements = movements(from, toInclusive)
        val entries = ArrayList<CashBookEntry>()
        for (s in sessions) {
            var balance = s.openingCount
            entries += CashBookEntry(s.openedAt, s.device, "Schicht geöffnet, Wechselgeld gezählt", s.openedBy, "COUNT", null, balance)
            for (m in movements.filter { it.sessionId == s.id }) {
                val signed = if (m.kind == "WITHDRAWAL") -m.amount else m.amount
                balance += signed
                entries += CashBookEntry(m.at, s.device, if (m.kind == "WITHDRAWAL") "Entnahme: ${m.reason}" else "Einlage: ${m.reason}", m.by, m.kind, signed, balance)
            }
            val closedAt = s.closedAt ?: continue
            balance += s.cashIn
            entries += CashBookEntry(closedAt, s.device, "Bareinnahmen der Schicht", "Verkäufe, Aufladungen und Trinkgeld in bar", "SALES", s.cashIn, balance)
            val counted = s.closingCount ?: continue
            val diff = counted - balance
            entries += CashBookEntry(
                closedAt, s.device, "Schicht geschlossen, Bestand gezählt: ${com.example.vereins_kassensystem.ui.format.Money.format(counted)}",
                listOfNotNull(s.closedBy, s.note).joinToString(" · "), if (kotlin.math.abs(diff) >= 0.005) "DIFFERENCE" else "COUNT", if (kotlin.math.abs(diff) >= 0.005) diff else null, counted
            )
        }
        return entries.sortedWith(compareBy({ it.at }, { it.device }))
    }
}
