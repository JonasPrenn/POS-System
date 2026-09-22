package com.example.vereins_kassensystem.server.sync

import java.math.BigDecimal

enum class ColumnType { UUID, TEXT, MONEY, INT, DOUBLE, BOOL, TIMESTAMP }

/**
 * Eine fachliche Spalte. [default] gilt beim Einfügen, wenn der Client sie weglässt;
 * [allowed] beschränkt Textspalten auf die Werte, die auch das Schema per CHECK zulässt —
 * geprüft aber vorher, damit die Fehlermeldung die Operation benennt statt einer SQL-Zeile.
 */
class Column(
    val name: String,
    val type: ColumnType,
    val nullable: Boolean = false,
    val default: Any? = null,
    val allowed: Set<String>? = null,
) {
    val requiredOnInsert: Boolean get() = !nullable && default == null
}

/**
 * Wie eine Tabelle sich bei gleichzeitigen Änderungen verhält (Spezifikation 4.3).
 *
 * [APPEND_ONLY] wird nur eingefügt, nie geändert oder gelöscht — Korrekturen sind neue
 * Zeilen. [MASTER] folgt „letzter Schreibvorgang gewinnt", verglichen über
 * `base_updated_at`. [EVENT] ist [MASTER] plus der Anstichregel: Je Lagerartikel ist zu
 * einem Zeitpunkt ein Gebinde offen, der frühere Anstich gewinnt.
 */
enum class EntityKind { APPEND_ONLY, MASTER, EVENT }

class EntityDef(val table: String, val kind: EntityKind, val columns: List<Column>) {
    val byName: Map<String, Column> = columns.associateBy { it.name }
}

/**
 * Das Tabellenregister: die zwölf Tabellen der App, mit Spaltennamen wie im Serverschema.
 *
 * Jede Zeile, die über den Draht geht, wird gegen dieses Register geprüft — unbekannte
 * Felder sind ein Fehler, nicht Rauschen. So fällt ein `memberId` statt `member_id`
 * beim ersten Abgleich auf und nicht als leere Spalte drei Wochen später.
 */
object Entities {

    private val id = Column("id", ColumnType.UUID)
    private val ZERO = BigDecimal.ZERO.setScale(2)

    val all: List<EntityDef> = listOf(
        EntityDef("member_categories", EntityKind.MASTER, listOf(
            id,
            Column("name", ColumnType.TEXT),
            Column("negative_balance_limit", ColumnType.MONEY, default = ZERO),
        )),
        EntityDef("members", EntityKind.MASTER, listOf(
            id,
            Column("name", ColumnType.TEXT),
            Column("nickname", ColumnType.TEXT, default = ""),
            Column("category_id", ColumnType.UUID, nullable = true),
            Column("blocked_reason", ColumnType.TEXT, nullable = true),
            Column("last_used_timestamp", ColumnType.TIMESTAMP, nullable = true),
        )),
        EntityDef("products", EntityKind.MASTER, listOf(
            id,
            Column("name", ColumnType.TEXT),
            Column("price", ColumnType.MONEY),
            Column("category", ColumnType.TEXT, default = ""),
            Column("image_url", ColumnType.TEXT, nullable = true),
            Column("has_variants", ColumnType.BOOL, default = false),
            Column("serving_size", ColumnType.DOUBLE, default = 1.0),
        )),
        EntityDef("product_variants", EntityKind.MASTER, listOf(
            id,
            Column("product_id", ColumnType.UUID),
            Column("name", ColumnType.TEXT),
            Column("price", ColumnType.MONEY),
            Column("serving_size", ColumnType.DOUBLE, nullable = true),
        )),
        EntityDef("stock_items", EntityKind.MASTER, listOf(
            id,
            Column("name", ColumnType.TEXT),
            Column("unit", ColumnType.TEXT, default = "Stk"),
            Column("tracking", ColumnType.TEXT, allowed = setOf("SIMPLE", "CONTAINER")),
            Column("min_level", ColumnType.DOUBLE, default = 0.0),
        )),
        EntityDef("container_types", EntityKind.MASTER, listOf(
            id,
            Column("stock_item_id", ColumnType.UUID),
            Column("label", ColumnType.TEXT),
            Column("nominal_size", ColumnType.DOUBLE),
            Column("initial_yield_estimate", ColumnType.DOUBLE),
        )),
        EntityDef("product_components", EntityKind.MASTER, listOf(
            id,
            Column("product_id", ColumnType.UUID),
            Column("stock_item_id", ColumnType.UUID),
            Column("quantity_per_unit", ColumnType.DOUBLE),
        )),
        EntityDef("transactions", EntityKind.APPEND_ONLY, listOf(
            id,
            Column("transaction_group_id", ColumnType.UUID),
            Column("member_id", ColumnType.UUID, nullable = true),
            Column("member_name", ColumnType.TEXT, nullable = true),
            Column("product_ref", ColumnType.UUID),
            Column("product_name", ColumnType.TEXT),
            Column("product_category", ColumnType.TEXT, default = ""),
            Column("price", ColumnType.MONEY),
            Column("quantity", ColumnType.INT),
            Column("discount_amount", ColumnType.MONEY, default = ZERO),
            Column("payment_type", ColumnType.TEXT),
            Column("occurred_at", ColumnType.TIMESTAMP),
            Column("is_refund", ColumnType.BOOL, default = false),
            Column("note", ColumnType.TEXT, nullable = true),
        )),
        // Anfügend im Sinne der Spezifikation, aber der Belegschlüssel kommt erst nach dem
        // Foto-Upload dazu und ein Beleg darf gelöscht werden — also MASTER-Regeln.
        EntityDef("deliveries", EntityKind.MASTER, listOf(
            id,
            Column("supplier", ColumnType.TEXT, default = ""),
            Column("receipt_total", ColumnType.MONEY, nullable = true),
            Column("photo_key", ColumnType.TEXT, nullable = true),
            Column("note", ColumnType.TEXT, nullable = true),
            Column("occurred_at", ColumnType.TIMESTAMP),
        )),
        EntityDef("stock_entries", EntityKind.APPEND_ONLY, listOf(
            id,
            Column("stock_item_id", ColumnType.UUID),
            Column("item_name", ColumnType.TEXT),
            Column("quantity", ColumnType.DOUBLE),
            Column("unit_label", ColumnType.TEXT),
            Column("total_cost", ColumnType.MONEY, nullable = true),
            Column("note", ColumnType.TEXT, nullable = true),
            Column("source", ColumnType.TEXT, allowed = setOf("MANUAL", "SCAN", "CORRECTION")),
            Column("occurred_at", ColumnType.TIMESTAMP),
            Column("delivery_id", ColumnType.UUID, nullable = true),
            // Welche Gebindegröße ankam; leer bei Stückware. Siehe V2__lagerabgaenge.sql.
            Column("container_type_id", ColumnType.UUID, nullable = true),
        )),
        EntityDef("tapped_containers", EntityKind.EVENT, listOf(
            id,
            Column("container_type_id", ColumnType.UUID),
            Column("opened_at", ColumnType.TIMESTAMP),
            Column("closed_at", ColumnType.TIMESTAMP, nullable = true),
            Column("close_reason", ColumnType.TEXT, nullable = true, allowed = setOf("EMPTIED", "SPOILED")),
            Column("discarded_volume", ColumnType.DOUBLE, default = 0.0),
            Column("note", ColumnType.TEXT, nullable = true),
        )),
        // Was ein Verkauf dem Keller entnommen hat — anfügend wie transactions.
        EntityDef("stock_draws", EntityKind.APPEND_ONLY, listOf(
            id,
            Column("stock_item_id", ColumnType.UUID),
            Column("transaction_id", ColumnType.UUID, nullable = true),
            Column("volume", ColumnType.DOUBLE),
            Column("occurred_at", ColumnType.TIMESTAMP),
            Column("note", ColumnType.TEXT, nullable = true),
        )),
        // Kasse (Konzept 4.5): Die Schicht gehört dem Gerät, das sie öffnet; geschlossen wird
        // sie per Update — Stammdatenregel, ohne dass je ein zweites Gerät schriebe.
        EntityDef("cash_sessions", EntityKind.MASTER, listOf(
            id,
            Column("device_label", ColumnType.TEXT, default = ""),
            Column("opened_at", ColumnType.TIMESTAMP),
            Column("opened_by", ColumnType.TEXT, default = ""),
            Column("opening_count", ColumnType.MONEY),
            Column("closed_at", ColumnType.TIMESTAMP, nullable = true),
            Column("closed_by", ColumnType.TEXT, nullable = true),
            Column("closing_count", ColumnType.MONEY, nullable = true),
            Column("note", ColumnType.TEXT, nullable = true),
        )),
        EntityDef("cash_movements", EntityKind.APPEND_ONLY, listOf(
            id,
            Column("session_id", ColumnType.UUID),
            Column("kind", ColumnType.TEXT, allowed = setOf("WITHDRAWAL", "DEPOSIT")),
            Column("amount", ColumnType.MONEY),
            Column("reason", ColumnType.TEXT, default = ""),
            Column("by_name", ColumnType.TEXT, default = ""),
            Column("occurred_at", ColumnType.TIMESTAMP),
        )),
    )

    val byName: Map<String, EntityDef> = all.associateBy { it.table }

    /** Vom Server geführt; kommen sie in einer Zeile vom Client, werden sie übergangen. */
    val serverOwned = setOf("server_seq", "updated_at", "deleted", "deleted_at", "origin_device")

    /** Abgeleitete Werte (2.2, 2.3). Ein Schreibversuch darauf ist ein Programmfehler: 422. */
    val derivedColumns = setOf("balance", "simple_quantity", "full_count", "drawn")
    val derivedEntities = setOf("member_balances")
}
