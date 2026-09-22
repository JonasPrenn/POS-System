package com.example.vereins_kassensystem.server.web

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

enum class Payment(val label: String) { OPEN("offen"), CASH("bar aus der Kasse"), BANK("Überweisung"), CARD("Karte") }

class Supplier(val id: UUID, val name: String, val contact: String, val customerNumber: String, val documents: Int)

class Account(val id: UUID, val code: String, val name: String, val area: String, val kind: String)

/** Ein Beleg, wie die Liste ihn zeigt: das Tablet-Wesen (Wareneingang) und das Web-Wesen (Rechnung) in einer Zeile. */
class Document(
    val id: UUID,               // purchase_documents.id — oder, solange es keinen gibt, die delivery id
    val deliveryId: UUID?,
    val hasDocument: Boolean,
    val supplierId: UUID?,
    val supplier: String,
    val number: String,
    val date: LocalDate,
    val dueDate: LocalDate?,
    val gross: Double?,
    val vat: Double,
    val payment: Payment,
    val paidAt: LocalDate?,
    val fileKey: String?,
    val photoKey: String?,
    val note: String,
    val stockLines: Int,
    val expenseLines: Int,
) {
    val open get() = paidAt == null && payment == Payment.OPEN
    val overdue: (LocalDate) -> Boolean = { today -> open && dueDate != null && dueDate.isBefore(today) }
}

class StockLine2(val id: UUID, val item: String, val quantity: Double, val unit: String, val cost: Double?, val source: String)
class ExpenseLine(val id: UUID, val label: String, val amount: Double, val account: String)
class StockChoice(val id: UUID, val name: String, val unit: String, val tracking: String, val containerTypes: List<Pair<UUID, String>>)

/**
 * Einkauf (Konzept 4.4). Lesen im Schnappschuss; schreiben in zwei Sorten: Belegdaten und
 * Ausgabenzeilen sind serverseitig und laufen über [Database.transaction], Lagerpositionen und
 * der Wareneingang selbst sind synchronisierte Zeilen und laufen über [Database.write] —
 * dieselbe Sperre wie der Abgleich, damit kein Tablet eine Sequenznummer übersieht.
 */
class Purchases(private val db: Database, private val zone: ZoneId) {

    // ------------------------------------------------------------------ Lesen

    private val LIST = """
        SELECT COALESCE(p.id, d.id) AS id, d.id AS delivery_id, (p.id IS NOT NULL) AS has_document, p.supplier_id,
               COALESCE(NULLIF(p.supplier_name, ''), d.supplier, '') AS supplier, COALESCE(p.number, '') AS number,
               COALESCE(p.document_date, (d.occurred_at AT TIME ZONE ?)::date) AS document_date, p.due_date,
               COALESCE(p.gross, d.receipt_total) AS gross, COALESCE(p.vat, 0) AS vat, COALESCE(p.payment, 'OPEN') AS payment,
               p.paid_at, p.file_key, d.photo_key, COALESCE(p.note, d.note, '') AS note,
               (SELECT COUNT(*) FROM stock_entries e WHERE e.delivery_id = d.id AND NOT e.deleted) AS stock_lines,
               (SELECT COUNT(*) FROM purchase_lines l WHERE l.document_id = p.id) AS expense_lines,
               COALESCE(p.document_date, (d.occurred_at AT TIME ZONE ?)::date) AS sort_date, COALESCE(d.occurred_at, p.created_at) AS sort_at
        FROM deliveries d
        FULL OUTER JOIN purchase_documents p ON p.delivery_id = d.id
        WHERE d.id IS NULL OR NOT d.deleted
    """.trimIndent()

    private fun ResultSet.document() = Document(
        id = getObject("id", UUID::class.java), deliveryId = getObject("delivery_id", UUID::class.java), hasDocument = getBoolean("has_document"),
        supplierId = getObject("supplier_id", UUID::class.java), supplier = getString("supplier"), number = getString("number"),
        date = getDate("document_date").toLocalDate(), dueDate = getDate("due_date")?.toLocalDate(),
        gross = getBigDecimal("gross")?.toDouble(), vat = getDouble("vat"), payment = Payment.valueOf(getString("payment")),
        paidAt = getDate("paid_at")?.toLocalDate(), fileKey = getString("file_key"), photoKey = getString("photo_key"), note = getString("note"),
        stockLines = getInt("stock_lines"), expenseLines = getInt("expense_lines"),
    )

    fun documents(limit: Int): List<Document> = db.read { c ->
        c.query("$LIST ORDER BY sort_date DESC, sort_at DESC LIMIT ?", zone.id, zone.id, limit) { it.document() }
    }

    fun document(id: UUID): Document? = db.read { c -> find(c, id) }

    /** Die Belege eines Zeitraums nach Belegdatum, für die Prüfermappe. */
    fun documentsBetween(from: LocalDate, to: LocalDate): List<Document> = db.read { c ->
        c.query("SELECT * FROM ($LIST) x WHERE x.sort_date >= ? AND x.sort_date < ? ORDER BY x.sort_date, x.sort_at", zone.id, zone.id, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to)) { it.document() }
    }

    private fun find(c: Connection, id: UUID): Document? =
        c.queryOne("$LIST AND (p.id = ? OR d.id = ?)", zone.id, zone.id, id, id) { it.document() }

    fun stockLines(deliveryId: UUID?): List<StockLine2> = if (deliveryId == null) emptyList() else db.read { c ->
        c.query("SELECT id, item_name, quantity, unit_label, total_cost, source FROM stock_entries WHERE delivery_id = ? AND NOT deleted ORDER BY occurred_at, id", deliveryId) {
            StockLine2(it.getObject("id", UUID::class.java), it.getString("item_name"), it.getDouble("quantity"), it.getString("unit_label"), it.getBigDecimal("total_cost")?.toDouble(), it.getString("source"))
        }
    }

    fun expenseLines(documentId: UUID): List<ExpenseLine> = db.read { c ->
        c.query("SELECT l.id, l.label, l.amount, a.name AS account FROM purchase_lines l JOIN accounts a ON a.id = l.account_id WHERE l.document_id = ? ORDER BY l.sort, l.id", documentId) {
            ExpenseLine(it.getObject("id", UUID::class.java), it.getString("label"), it.getDouble("amount"), it.getString("account"))
        }
    }

    fun suppliers(): List<Supplier> = db.read { c ->
        c.query("SELECT s.*, (SELECT COUNT(*) FROM purchase_documents p WHERE p.supplier_id = s.id) AS documents FROM suppliers s ORDER BY lower(s.name)") {
            Supplier(it.getObject("id", UUID::class.java), it.getString("name"), it.getString("contact"), it.getString("customer_number"), it.getInt("documents"))
        }
    }

    fun expenseAccounts(): List<Account> = db.read { c ->
        c.query("SELECT id, code, name, area, kind FROM accounts WHERE active AND kind = 'EXPENSE' ORDER BY sort") { it.account() }
    }

    private fun ResultSet.account() = Account(getObject("id", UUID::class.java), getString("code"), getString("name"), getString("area"), getString("kind"))

    /** Lagerartikel mit ihren Gebinden — für die Zuordnung einer Belegzeile. */
    fun stockChoices(): List<StockChoice> = db.read { c ->
        val types = c.query("SELECT id, stock_item_id, label FROM container_types WHERE NOT deleted ORDER BY nominal_size DESC") {
            Triple(it.getObject("id", UUID::class.java), it.getObject("stock_item_id", UUID::class.java), it.getString("label"))
        }
        c.query("SELECT id, name, unit, tracking FROM stock_items WHERE NOT deleted ORDER BY lower(name)") { r ->
            val id = r.getObject("id", UUID::class.java)
            StockChoice(id, r.getString("name"), r.getString("unit"), r.getString("tracking"), types.filter { it.second == id }.map { it.first to it.third })
        }
    }

    fun fileKeyExists(key: String): Boolean = db.read { c -> c.queryOne("SELECT 1 FROM purchase_documents WHERE file_key = ?", key) { true } ?: false }

    /** Offene Belege: nicht bezahlt, sortiert nach Fälligkeit — was zuerst dran ist, steht oben. */
    fun openDocuments(): List<Document> = db.read { c ->
        c.query("$LIST AND p.paid_at IS NULL AND COALESCE(p.payment, 'OPEN') = 'OPEN' AND COALESCE(p.gross, d.receipt_total) IS NOT NULL ORDER BY p.due_date NULLS LAST, sort_date", zone.id, zone.id) { it.document() }
    }

    // --------------------------------------------------------------- Schreiben

    class Head(
        val supplier: String, val number: String, val date: LocalDate, val dueDate: LocalDate?, val gross: Double?,
        val vat: Double, val payment: Payment, val paidAt: LocalDate?, val note: String,
    )

    /**
     * Legt einen Beleg an oder schreibt seine Kopfdaten. [id] ist die id aus der Liste: die
     * eines Belegs, oder die eines Wareneingangs vom Tablet, der noch keinen hat — dann
     * entsteht er hier. Ohne [id] ein ganz neuer Beleg, der noch keinen Wareneingang hat.
     */
    fun saveHead(by: WebUser, id: UUID?, head: Head, fileKey: String?): UUID {
        val supplier = head.supplier.trim().replace(Regex("\\s+"), " ").take(80)
        if (supplier.isEmpty()) throw AccountProblem("Bitte einen Lieferanten angeben.")
        val number = head.number.trim().take(60)
        if (head.gross != null && (head.gross < 0 || head.gross > 100_000)) throw AccountProblem("Der Bruttobetrag ist nicht plausibel.")
        if (head.vat < 0 || (head.gross != null && head.vat > head.gross)) throw AccountProblem("Die enthaltene Umsatzsteuer kann nicht größer sein als der Beleg.")
        if (head.dueDate != null && head.dueDate.isBefore(head.date)) throw AccountProblem("Fällig vor dem Belegdatum — das kann nicht stimmen.")
        return db.transaction { c ->
            val supplierId = ensureSupplier(c, supplier)
            val existing = id?.let { find(c, it) }
            val documentId = when {
                existing == null -> UUID.fromString(Ids.new())
                existing.hasDocument -> existing.id
                else -> UUID.fromString(Ids.new())
            }
            if (number.isNotEmpty() && c.queryOne(
                    "SELECT 1 FROM purchase_documents WHERE id <> ? AND lower(supplier_name) = lower(?) AND lower(number) = lower(?)", documentId, supplier, number
                ) { true } == true
            ) throw AccountProblem("Beleg „$number“ von $supplier ist schon erfasst — Dublette?")
            val paid = when {
                head.payment == Payment.OPEN -> null
                else -> head.paidAt ?: head.date
            }
            if (existing?.hasDocument == true) {
                c.execute(
                    "UPDATE purchase_documents SET supplier_id = ?, supplier_name = ?, number = ?, document_date = ?, due_date = ?, gross = ?, vat = ?, payment = ?, paid_at = ?, note = ?, file_key = COALESCE(?, file_key) WHERE id = ?",
                    supplierId, supplier, number, Date.valueOf(head.date), head.dueDate?.let(Date::valueOf), head.gross?.let(::money), money(head.vat), head.payment.name, paid?.let(Date::valueOf), head.note.trim().take(500), fileKey, documentId
                )
            } else {
                c.execute(
                    "INSERT INTO purchase_documents (id, delivery_id, supplier_id, supplier_name, number, document_date, due_date, gross, vat, payment, paid_at, file_key, note, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    documentId, existing?.deliveryId, supplierId, supplier, number, Date.valueOf(head.date), head.dueDate?.let(Date::valueOf), head.gross?.let(::money), money(head.vat), head.payment.name, paid?.let(Date::valueOf), fileKey, head.note.trim().take(500), by.displayName
                )
            }
            AuditLog.record(c, by.id, by.displayName, if (existing?.hasDocument == true) "purchase.update" else "purchase.create", "$supplier ${number}".trim(), head.gross?.let(Money::format) ?: "")
            documentId
        }
    }

    fun markPaid(by: WebUser, id: UUID, payment: Payment, paidAt: LocalDate) {
        if (payment == Payment.OPEN) throw AccountProblem("Bezahlt womit?")
        db.transaction { c ->
            val doc = find(c, id)?.takeIf { it.hasDocument } ?: throw AccountProblem("Zuerst die Belegdaten erfassen, dann bezahlen.")
            c.execute("UPDATE purchase_documents SET payment = ?, paid_at = ? WHERE id = ?", payment.name, Date.valueOf(paidAt), doc.id)
            AuditLog.record(c, by.id, by.displayName, "purchase.paid", "${doc.supplier} ${doc.number}".trim(), "${payment.label}, $paidAt")
        }
    }

    fun addExpenseLine(by: WebUser, documentId: UUID, label: String, amount: Double, accountId: UUID) {
        val clean = label.trim().take(120).ifEmpty { throw AccountProblem("Wofür? Bitte eine Bezeichnung.") }
        val cents = Money.cents(amount)
        if (cents == 0.0) throw AccountProblem("Der Betrag fehlt.")
        db.transaction { c ->
            val doc = find(c, documentId)?.takeIf { it.hasDocument } ?: throw AccountProblem("Zuerst die Belegdaten erfassen.")
            c.queryOne("SELECT 1 FROM accounts WHERE id = ? AND active", accountId) { true } ?: throw AccountProblem("Dieses Konto gibt es nicht.")
            c.execute("INSERT INTO purchase_lines (id, document_id, label, amount, account_id, sort) VALUES (?, ?, ?, ?, ?, (SELECT COALESCE(MAX(sort), 0) + 1 FROM purchase_lines WHERE document_id = ?))",
                UUID.fromString(Ids.new()), doc.id, clean, money(cents), accountId, doc.id)
            AuditLog.record(c, by.id, by.displayName, "purchase.line", "${doc.supplier} ${doc.number}".trim(), "$clean ${Money.format(cents)}")
        }
    }

    fun removeExpenseLine(by: WebUser, lineId: UUID) = db.transaction { c ->
        val removed = c.queryOne("DELETE FROM purchase_lines WHERE id = ? RETURNING label", lineId) { it.getString("label") }
        if (removed != null) AuditLog.record(c, by.id, by.displayName, "purchase.line.remove", removed)
    }

    /**
     * Eine Lagerposition: der Wareneingang, wie ihn das Tablet bucht — als `stock_entries`-Zeile
     * mit `delivery_id`. Hat der Beleg noch keinen Wareneingang, entsteht er hier mit. Beides
     * synchronisiert, beides unter der Sperre.
     */
    fun addStockLine(by: WebUser, documentId: UUID, itemId: UUID, containerTypeId: UUID?, quantity: Double, cost: Double?, now: Instant = Instant.now()) {
        if (quantity <= 0 || quantity > 10_000) throw AccountProblem("Die Menge muss größer als null sein.")
        db.write { c ->
            val doc = find(c, documentId) ?: throw AccountProblem("Diesen Beleg gibt es nicht.")
            val item = c.queryOne("SELECT name, unit, tracking FROM stock_items WHERE id = ? AND NOT deleted", itemId) { Triple(it.getString("name"), it.getString("unit"), it.getString("tracking")) }
                ?: throw AccountProblem("Diesen Lagerartikel gibt es nicht mehr.")
            val container = containerTypeId?.let { id ->
                c.queryOne("SELECT label FROM container_types WHERE id = ? AND stock_item_id = ? AND NOT deleted", id, itemId) { it.getString("label") }
                    ?: throw AccountProblem("Dieses Gebinde gehört nicht zu ${item.first}.")
            }
            if (item.third == "CONTAINER" && container == null) throw AccountProblem("${item.first} wird in Gebinden geführt — bitte eines wählen.")
            val deliveryId = doc.deliveryId ?: UUID.fromString(Ids.new()).also { newId ->
                c.execute("INSERT INTO deliveries (id, supplier, receipt_total, note, occurred_at) VALUES (?, ?, ?, ?, ?)",
                    newId, doc.supplier, doc.gross?.let(::money), doc.note.ifEmpty { null }, Timestamp.from(doc.date.atStartOfDay(zone).toInstant().takeIf { it.isBefore(now) } ?: now))
                if (doc.hasDocument) c.execute("UPDATE purchase_documents SET delivery_id = ? WHERE id = ?", newId, doc.id)
            }
            val occurredAt = c.queryOne("SELECT occurred_at FROM deliveries WHERE id = ?", deliveryId) { it.getTimestamp("occurred_at").toInstant() } ?: now
            c.execute(
                "INSERT INTO stock_entries (id, stock_item_id, item_name, quantity, unit_label, total_cost, source, occurred_at, delivery_id, container_type_id) VALUES (?, ?, ?, ?, ?, ?, 'MANUAL', ?, ?, ?)",
                UUID.fromString(Ids.new()), itemId, item.first, quantity, container ?: item.second, cost?.let { money(Money.cents(it)) }, Timestamp.from(occurredAt), deliveryId, containerTypeId
            )
            AuditLog.record(c, by.id, by.displayName, "purchase.stock", "${doc.supplier} ${doc.number}".trim(), "${Money.formatPlain(quantity).removeSuffix(",00")} × ${container ?: item.second} ${item.first}")
        }
    }

    private fun ensureSupplier(c: Connection, name: String): UUID =
        c.queryOne("SELECT id FROM suppliers WHERE lower(name) = lower(?)", name) { it.getObject("id", UUID::class.java) }
            ?: UUID.fromString(Ids.new()).also { c.execute("INSERT INTO suppliers (id, name) VALUES (?, ?)", it, name) }

    fun updateSupplier(by: WebUser, id: UUID, contact: String, customerNumber: String) = db.transaction { c ->
        c.execute("UPDATE suppliers SET contact = ?, customer_number = ? WHERE id = ?", contact.trim().take(200), customerNumber.trim().take(60), id)
        AuditLog.record(c, by.id, by.displayName, "supplier.update", c.queryOne("SELECT name FROM suppliers WHERE id = ?", id) { it.getString("name") } ?: "")
    }

    private fun money(v: Double): BigDecimal = BigDecimal.valueOf(Money.cents(v))
}
