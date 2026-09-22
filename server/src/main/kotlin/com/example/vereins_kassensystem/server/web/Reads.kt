package com.example.vereins_kassensystem.server.web

import com.example.vereins_kassensystem.data.Ledger
import com.example.vereins_kassensystem.data.entity.ContainerCloseReason
import com.example.vereins_kassensystem.data.entity.ContainerType
import com.example.vereins_kassensystem.data.entity.StockItem
import com.example.vereins_kassensystem.data.entity.StockTracking
import com.example.vereins_kassensystem.data.entity.TappedContainer
import com.example.vereins_kassensystem.data.stock.Inventory
import com.example.vereins_kassensystem.data.stock.StockItemState
import com.example.vereins_kassensystem.server.db.Database
import com.example.vereins_kassensystem.server.db.query
import com.example.vereins_kassensystem.server.db.queryOne
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/** Umsatz nach Zahlart. Der Deckel ist Umsatz, aber kein Geldeingang — deshalb getrennt. */
data class Revenue(val cash: Double = 0.0, val card: Double = 0.0, val tab: Double = 0.0) {
    val total get() = cash + card + tab
    operator fun plus(o: Revenue) = Revenue(cash + o.cash, card + o.card, tab + o.tab)
}

class DayRevenue(val day: LocalDate, val revenue: Revenue)

class Checkout(val at: Instant, val device: String?, val who: String?, val what: String, val paymentType: String, val amount: Double, val topUp: Boolean, val refund: Boolean)

class TabTotals(val owedSum: Double, val owedCount: Int, val overLimit: Int, val creditSum: Double, val creditCount: Int)

class MemberLine(val id: UUID, val name: String, val nickname: String, val category: String?, val limit: Double, val balance: Double, val lastAt: Instant?, val blockedReason: String? = null) {
    val blocked get() = !blockedReason.isNullOrBlank()
    /** „Lukas Hofer v. Sokrates“, wie in der App. */
    val displayName get() = if (nickname.isBlank()) name else "$name v. $nickname"
    val overLimit get() = balance < limit
    val owes get() = balance < 0

    /** Dieselbe Einteilung wie balanceColor() in der App: nach Zustand, nicht nach Vorzeichen. */
    val tone: String
        get() = when {
            balance < limit -> "c-error"
            balance < 0 && (limit == 0.0 || balance <= limit * 0.75) -> "c-warning"
            balance < 0 -> "c-muted"
            else -> "c-primary"
        }
}

class StatementLine(val at: Instant, val text: String, val effect: Double, val after: Double)

class DeliveryLine(val id: UUID, val supplier: String, val total: Double?, val at: Instant, val photoKey: String?, val note: String?, val positions: Int)

class DeliveryPosition(val item: String, val quantity: Double, val unit: String, val cost: Double?, val source: String)

class StockLine(val state: StockItemState, val available: Double, val low: Boolean, val value: Double?)

class MonthReport(val month: YearMonth, val revenue: Revenue, val topUpCash: Double, val topUpCard: Double, val topUpOther: Double, val tips: Double, val refunds: Double, val purchases: Double)

/**
 * Alles, was die Verwaltung liest. Nur SELECTs, alle in einem Schnappschuss ([Database.read]);
 * geschrieben wird in Phase 1 auf keine der synchronisierten Tabellen.
 */
class Reads(private val db: Database, val zone: ZoneId) {

    private fun startOf(day: LocalDate): Timestamp = Timestamp.from(day.atStartOfDay(zone).toInstant())

    // ------------------------------------------------------------- Umsatz

    fun revenueByDay(from: LocalDate, toInclusive: LocalDate): List<DayRevenue> = db.read { c ->
        val rows = c.query(
            "SELECT (occurred_at AT TIME ZONE ?)::date AS day, payment_type, SUM(revenue) AS amount " +
                "FROM transaction_effects WHERE occurred_at >= ? AND occurred_at < ? GROUP BY 1, 2",
            zone.id, startOf(from), startOf(toInclusive.plusDays(1))
        ) { Triple(it.getDate("day").toLocalDate(), it.getString("payment_type"), it.getDouble("amount")) }
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(toInclusive) }.map { day ->
            DayRevenue(day, rows.filter { it.first == day }.fold(Revenue()) { sum, row -> sum + revenueOf(row.second, row.third) })
        }.toList()
    }

    private fun revenueOf(paymentType: String, amount: Double) = when (paymentType) {
        "CASH" -> Revenue(cash = amount)
        "CARD" -> Revenue(card = amount)
        Ledger.MEMBER_BALANCE -> Revenue(tab = amount)
        else -> Revenue()
    }

    fun recentCheckouts(limit: Int): List<Checkout> = db.read { c ->
        c.query(
            """
            SELECT MAX(e.occurred_at) AS at, MAX(d.label) AS device, MAX(e.member_name) AS who, MIN(e.payment_type) AS payment_type,
                   string_agg(CASE WHEN e.product_ref IN (?::uuid, ?::uuid, ?::uuid) THEN e.product_name
                                   ELSE e.quantity || ' × ' || e.product_name END, ', ' ORDER BY e.id) AS what,
                   SUM((e.price * e.quantity - e.discount_amount) * CASE WHEN e.is_refund THEN -1 ELSE 1 END) AS amount,
                   bool_and(e.product_ref = ?::uuid) AS top_up, bool_or(e.is_refund) AS refund
            FROM transaction_effects e LEFT JOIN devices d ON d.id = e.origin_device
            WHERE e.payment_type <> 'CORRECTION'
            GROUP BY e.transaction_group_id ORDER BY at DESC LIMIT ?
            """.trimIndent(),
            Ledger.TOPUP_REF, Ledger.MANUAL_REF, Ledger.TIP_REF, Ledger.TOPUP_REF, limit
        ) {
            Checkout(
                it.getTimestamp("at").toInstant(), it.getString("device"), it.getString("who"), it.getString("what"),
                it.getString("payment_type"), it.getDouble("amount"), it.getBoolean("top_up"), it.getBoolean("refund")
            )
        }
    }

    // ------------------------------------------------------------ Mitglieder

    fun members(): List<MemberLine> = db.read { c -> members(c) }

    private fun members(c: Connection): List<MemberLine> = c.query(
        """
        SELECT m.id, m.name, m.nickname, m.blocked_reason, k.name AS category, COALESCE(k.negative_balance_limit, 0) AS lim, b.balance,
               (SELECT MAX(t.occurred_at) FROM transactions t
                 WHERE t.member_id = m.id AND NOT t.deleted AND t.payment_type <> 'CORRECTION') AS last_at
        FROM members m
        JOIN member_balances b ON b.member_id = m.id
        LEFT JOIN member_categories k ON k.id = m.category_id AND NOT k.deleted
        WHERE NOT m.deleted ORDER BY lower(m.name)
        """.trimIndent()
    ) {
        MemberLine(
            it.getObject("id", UUID::class.java), it.getString("name"), it.getString("nickname"), it.getString("category"),
            it.getDouble("lim"), it.getDouble("balance"), it.getTimestamp("last_at")?.toInstant(), it.getString("blocked_reason")
        )
    }

    fun tabTotals(members: List<MemberLine>) = TabTotals(
        owedSum = members.filter { it.owes }.sumOf { it.balance },
        owedCount = members.count { it.owes },
        overLimit = members.count { it.overLimit },
        creditSum = members.filter { it.balance > 0 }.sumOf { it.balance },
        creditCount = members.count { it.balance > 0 },
    )

    /** Der Kontoauszug: jede Zeile mit dem Stand danach — summiert, nirgends gespeichert. */
    fun statement(memberId: UUID, limit: Int): List<StatementLine> = db.read { c ->
        c.query(
            """
            SELECT * FROM (
              SELECT e.occurred_at, e.id, e.balance_effect,
                     CASE WHEN e.product_ref IN (?::uuid, ?::uuid) THEN e.product_name
                          ELSE e.quantity || ' × ' || e.product_name END
                       || CASE WHEN e.is_refund THEN ' (Storno)' ELSE '' END AS label,
                     SUM(e.balance_effect) OVER (ORDER BY e.occurred_at, e.id) AS after
              FROM transaction_effects e WHERE e.member_id = ?
            ) s WHERE s.balance_effect <> 0 ORDER BY s.occurred_at DESC, s.id DESC LIMIT ?
            """.trimIndent(),
            Ledger.TOPUP_REF, Ledger.MANUAL_REF, memberId, limit
        ) { StatementLine(it.getTimestamp("occurred_at").toInstant(), it.getString("label"), it.getDouble("balance_effect"), it.getDouble("after")) }
    }

    // ----------------------------------------------------------------- Lager

    /**
     * Der Bestand, hergeleitet wie in der App (`DerivedSql.kt`): Eingänge minus Abgänge, volle
     * Gebinde als Eingänge minus Anstiche, „gezapft" als Abgänge im Zeitfenster des Anstichs.
     * Gerechnet wird danach mit derselben `Inventory` aus `:core` — auf dem Server kommt also
     * dieselbe Zahl heraus wie an der Theke.
     */
    fun stock(): List<StockLine> = db.read { c ->
        val items = c.query(
            """
            SELECT s.id, s.name, s.unit, s.tracking, s.min_level,
                   ROUND((COALESCE((SELECT SUM(e.quantity) FROM stock_entries e
                                     WHERE e.stock_item_id = s.id AND e.container_type_id IS NULL AND NOT e.deleted), 0)
                        - COALESCE((SELECT SUM(d.volume) FROM stock_draws d
                                     WHERE d.stock_item_id = s.id AND NOT d.deleted), 0))::numeric, 6) AS simple_quantity
            FROM stock_items s WHERE NOT s.deleted ORDER BY lower(s.name)
            """.trimIndent()
        ) {
            StockItem(
                id = it.getString("id"), name = it.getString("name"), unit = it.getString("unit"),
                tracking = StockTracking.valueOf(it.getString("tracking")),
                simpleQuantity = it.getDouble("simple_quantity"), minLevel = it.getDouble("min_level")
            )
        }
        val types = c.query(
            """
            SELECT k.id, k.stock_item_id, k.label, k.nominal_size, k.initial_yield_estimate,
                   ROUND(COALESCE((SELECT SUM(e.quantity) FROM stock_entries e
                                    WHERE e.container_type_id = k.id AND NOT e.deleted), 0))::int
                   - (SELECT COUNT(*) FROM tapped_containers t WHERE t.container_type_id = k.id AND NOT t.deleted)::int AS full_count
            FROM container_types k WHERE NOT k.deleted
            """.trimIndent()
        ) {
            ContainerType(
                id = it.getString("id"), stockItemId = it.getString("stock_item_id"), label = it.getString("label"),
                nominalSize = it.getDouble("nominal_size"), initialYieldEstimate = it.getDouble("initial_yield_estimate"),
                fullCount = it.getInt("full_count")
            )
        }
        val tapped = c.query(
            """
            SELECT t.id, t.container_type_id, t.opened_at, t.closed_at, t.close_reason, t.discarded_volume, t.note,
                   COALESCE((SELECT SUM(d.volume) FROM stock_draws d
                              WHERE NOT d.deleted AND d.stock_item_id = k.stock_item_id AND d.occurred_at >= t.opened_at
                                AND (t.closed_at IS NULL OR d.occurred_at < t.closed_at)), 0) AS drawn
            FROM tapped_containers t JOIN container_types k ON k.id = t.container_type_id WHERE NOT t.deleted
            """.trimIndent()
        ) {
            TappedContainer(
                id = it.getString("id"), containerTypeId = it.getString("container_type_id"), drawn = it.getDouble("drawn"),
                openedAt = it.getTimestamp("opened_at").time, closedAt = it.getTimestamp("closed_at")?.time,
                closeReason = it.getString("close_reason")?.let(ContainerCloseReason::valueOf),
                discardedVolume = it.getDouble("discarded_volume"), note = it.getString("note")
            )
        }
        // Einstandspreis je Einheit, gleitender Durchschnitt über alle Eingänge mit Betrag. Bei
        // Fassware zählt nur, was sein Gebinde kennt: Ein alter Eingang „2 Gebinde" ohne Größe
        // ergäbe sonst einen Literpreis von über hundert Euro.
        val unitCost = c.query(
            """
            SELECT e.stock_item_id, SUM(e.total_cost) / NULLIF(SUM(e.quantity * COALESCE(k.nominal_size, 1)), 0) AS cost
            FROM stock_entries e JOIN stock_items s ON s.id = e.stock_item_id
            LEFT JOIN container_types k ON k.id = e.container_type_id
            WHERE NOT e.deleted AND e.total_cost IS NOT NULL AND e.quantity > 0
              AND (s.tracking = 'SIMPLE' OR e.container_type_id IS NOT NULL)
            GROUP BY e.stock_item_id
            """.trimIndent()
        ) { it.getString("stock_item_id") to it.getDouble("cost") }.toMap()

        items.map { item ->
            val ownTypes = types.filter { it.stockItemId == item.id }
            val state = StockItemState(item, ownTypes, tapped.filter { t -> ownTypes.any { it.id == t.containerTypeId } })
            val available = Inventory.available(state)
            StockLine(state, available, Inventory.isLow(state), unitCost[item.id]?.let { it * maxOf(available, 0.0) })
        }
    }

    // --------------------------------------------------------------- Einkauf

    fun deliveries(limit: Int): List<DeliveryLine> = db.read { c ->
        c.query(
            """
            SELECT d.id, d.supplier, d.receipt_total, d.occurred_at, d.photo_key, d.note,
                   (SELECT COUNT(*) FROM stock_entries e WHERE e.delivery_id = d.id AND NOT e.deleted) AS positions
            FROM deliveries d WHERE NOT d.deleted ORDER BY d.occurred_at DESC LIMIT ?
            """.trimIndent(), limit
        ) {
            DeliveryLine(
                it.getObject("id", UUID::class.java), it.getString("supplier"), it.getBigDecimal("receipt_total")?.toDouble(),
                it.getTimestamp("occurred_at").toInstant(), it.getString("photo_key"), it.getString("note"), it.getInt("positions")
            )
        }
    }

    fun deliveryPositions(id: UUID): List<DeliveryPosition> = db.read { c ->
        c.query(
            "SELECT item_name, quantity, unit_label, total_cost, source FROM stock_entries WHERE delivery_id = ? AND NOT deleted ORDER BY occurred_at, id", id
        ) { DeliveryPosition(it.getString("item_name"), it.getDouble("quantity"), it.getString("unit_label"), it.getBigDecimal("total_cost")?.toDouble(), it.getString("source")) }
    }

    fun photoKeyExists(key: String): Boolean = db.read { c ->
        c.queryOne("SELECT 1 FROM deliveries WHERE photo_key = ? AND NOT deleted", key) { true } ?: false
    }

    // -------------------------------------------------------------- Berichte

    /** Zwölf Monate ab [first]: Umsatz nach Zahlart, Aufladungen, Trinkgeld, Stornos, Wareneingang. */
    fun months(first: YearMonth): List<MonthReport> = db.read { c ->
        val from = startOf(first.atDay(1))
        val to = startOf(first.plusMonths(12).atDay(1))
        val sales = c.query(
            """
            SELECT date_trunc('month', occurred_at AT TIME ZONE ?)::date AS month, payment_type,
                   SUM(revenue) AS revenue,
                   SUM(CASE WHEN product_ref = ?::uuid THEN price * quantity * CASE WHEN is_refund THEN -1 ELSE 1 END ELSE 0 END) AS top_up,
                   SUM(CASE WHEN product_ref = ?::uuid THEN price * quantity ELSE 0 END) AS tips,
                   SUM(CASE WHEN is_refund AND product_ref <> ?::uuid THEN price * quantity - discount_amount ELSE 0 END) AS refunds
            FROM transaction_effects WHERE occurred_at >= ? AND occurred_at < ? GROUP BY 1, 2
            """.trimIndent(),
            zone.id, Ledger.TOPUP_REF, Ledger.TIP_REF, Ledger.TOPUP_REF, from, to
        ) { r -> listOf(r.getDate("month").toLocalDate(), r.getString("payment_type"), r.getDouble("revenue"), r.getDouble("top_up"), r.getDouble("tips"), r.getDouble("refunds")) }
        // Wareneingang: der Beleg, wo es einen gibt (Belegdatum, Brutto), sonst der Wareneingang vom Tablet.
        val purchases = c.query(
            """
            SELECT date_trunc('month', day)::date AS month, SUM(amount) AS total FROM (
              SELECT p.document_date AS day, p.gross AS amount FROM purchase_documents p WHERE p.gross IS NOT NULL
              UNION ALL
              SELECT (d.occurred_at AT TIME ZONE ?)::date, d.receipt_total FROM deliveries d
               WHERE NOT d.deleted AND d.receipt_total IS NOT NULL AND NOT EXISTS (SELECT 1 FROM purchase_documents p WHERE p.delivery_id = d.id)
            ) x WHERE day >= ? AND day < ? GROUP BY 1
            """.trimIndent(),
            zone.id, java.sql.Date.valueOf(first.atDay(1)), java.sql.Date.valueOf(first.plusMonths(12).atDay(1))
        ) { YearMonth.from(it.getDate("month").toLocalDate()) to it.getDouble("total") }.toMap()

        (0 until 12).map { first.plusMonths(it.toLong()) }.map { month ->
            val own = sales.filter { YearMonth.from(it[0] as LocalDate) == month }
            fun sum(index: Int, type: String? = null) = own.filter { type == null || it[1] == type }.sumOf { it[index] as Double }
            MonthReport(
                month = month,
                revenue = own.fold(Revenue()) { acc, row -> acc + revenueOf(row[1] as String, row[2] as Double) },
                topUpCash = sum(3, "CASH"), topUpCard = sum(3, "CARD"),
                // Korrekturen sind keine Aufladung: Übernahmebuchungen der Umstellung, kein Geld.
                topUpOther = own.filter { it[1] != "CASH" && it[1] != "CARD" && it[1] != "CORRECTION" }.sumOf { it[3] as Double },
                tips = sum(4), refunds = sum(5), purchases = purchases[month] ?: 0.0,
            )
        }
    }

    /** Je Lagerartikel der Lieferant der letzten Lieferung, die ihn enthielt — der Beleg, sonst der Wareneingang vom Tablet. */
    fun lastSuppliers(): Map<String, String> = db.read { c ->
        c.query(
            """
            SELECT DISTINCT ON (e.stock_item_id) e.stock_item_id, COALESCE(NULLIF(p.supplier_name, ''), d.supplier, '') AS supplier
            FROM stock_entries e JOIN deliveries d ON d.id = e.delivery_id LEFT JOIN purchase_documents p ON p.delivery_id = d.id
            WHERE NOT e.deleted AND NOT d.deleted AND e.quantity > 0 ORDER BY e.stock_item_id, d.occurred_at DESC
            """.trimIndent()
        ) { it.getObject("stock_item_id", UUID::class.java).toString() to it.getString("supplier") }.filter { it.second.isNotBlank() }.toMap()
    }

    fun firstBookingYear(): Int? = db.read { c ->
        c.queryOne("SELECT EXTRACT(YEAR FROM MIN(occurred_at AT TIME ZONE ?))::int AS y FROM transactions WHERE NOT deleted", zone.id) { r ->
            r.getInt("y").takeIf { !r.wasNull() }
        }
    }
}
