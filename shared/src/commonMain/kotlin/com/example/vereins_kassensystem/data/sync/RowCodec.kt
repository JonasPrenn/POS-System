package com.example.vereins_kassensystem.data.sync

import com.example.vereins_kassensystem.data.entity.CashMovement
import com.example.vereins_kassensystem.data.entity.CashMovementKind
import com.example.vereins_kassensystem.data.entity.CashSession
import com.example.vereins_kassensystem.data.entity.ContainerCloseReason
import com.example.vereins_kassensystem.data.entity.ContainerTypeRow
import com.example.vereins_kassensystem.data.entity.Delivery
import com.example.vereins_kassensystem.data.entity.MemberCategory
import com.example.vereins_kassensystem.data.entity.MemberRow
import com.example.vereins_kassensystem.data.entity.Product
import com.example.vereins_kassensystem.data.entity.ProductComponent
import com.example.vereins_kassensystem.data.entity.ProductVariant
import com.example.vereins_kassensystem.data.entity.StockDraw
import com.example.vereins_kassensystem.data.entity.StockEntry
import com.example.vereins_kassensystem.data.entity.StockEntrySource
import com.example.vereins_kassensystem.data.entity.StockItemRow
import com.example.vereins_kassensystem.data.entity.StockTracking
import com.example.vereins_kassensystem.data.entity.SyncMeta
import com.example.vereins_kassensystem.data.entity.TappedContainerRow
import com.example.vereins_kassensystem.data.entity.Transaction
import com.example.vereins_kassensystem.ui.format.Money
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.time.Instant

/** Eine Zeile vom Server, mit der die App nichts anfangen kann — ein Programmfehler, kein Betriebsfall. */
class RowFormatException(message: String) : Exception(message)

/** Die Tabellen des Abgleichs, mit den Namen des Serverschemas. */
object SyncTables {
    const val CATEGORIES = "member_categories"
    const val MEMBERS = "members"
    const val PRODUCTS = "products"
    const val VARIANTS = "product_variants"
    const val STOCK_ITEMS = "stock_items"
    const val CONTAINER_TYPES = "container_types"
    const val COMPONENTS = "product_components"
    const val DELIVERIES = "deliveries"
    const val STOCK_ENTRIES = "stock_entries"
    const val TAPPED = "tapped_containers"
    const val TRANSACTIONS = "transactions"
    const val STOCK_DRAWS = "stock_draws"
    const val CASH_SESSIONS = "cash_sessions"
    const val CASH_MOVEMENTS = "cash_movements"
}

/**
 * Zeilen zwischen Room und dem Drahtformat (Spezifikation 5.4 und `server/sync/Entities.kt`).
 *
 * Lokal heißen die Spalten wie die Kotlin-Felder und Zeit ist eine Zahl; auf dem Draht
 * heißen sie wie im Serverschema, Geld ist eine Zeichenkette mit zwei Stellen und Zeit ein
 * ISO-Zeitpunkt. Was nur auf dem Gerät gilt — der Pfad eines Belegfotos — geht nicht mit.
 *
 * Jede Tabelle steht hier genau zweimal, einmal je Richtung. Ein vergessenes Feld fällt
 * im `RowCodecTest` auf: Er schickt jede Zeile einmal im Kreis und vergleicht.
 */
object RowCodec {

    // ------------------------------------------------------------ zum Server

    fun encode(row: MemberCategory): JsonObject = buildJsonObject {
        put("id", row.id)
        put("name", row.name)
        put("negative_balance_limit", Money.wire(row.negativeBalanceLimit))
    }

    fun encode(row: MemberRow): JsonObject = buildJsonObject {
        put("id", row.id)
        put("name", row.name)
        put("nickname", row.nickname)
        put("category_id", row.categoryId)
    }

    fun encode(row: Product): JsonObject = buildJsonObject {
        put("id", row.id)
        put("name", row.name)
        put("price", Money.wire(row.price))
        put("category", row.category)
        put("image_url", row.imageUrl)
        put("has_variants", row.hasVariants)
        put("serving_size", row.servingSize)
    }

    fun encode(row: ProductVariant): JsonObject = buildJsonObject {
        put("id", row.id)
        put("product_id", row.productId)
        put("name", row.name)
        put("price", Money.wire(row.price))
        put("serving_size", row.servingSize)
    }

    fun encode(row: StockItemRow): JsonObject = buildJsonObject {
        put("id", row.id)
        put("name", row.name)
        put("unit", row.unit)
        put("tracking", row.tracking.name)
        put("min_level", row.minLevel)
    }

    fun encode(row: ContainerTypeRow): JsonObject = buildJsonObject {
        put("id", row.id)
        put("stock_item_id", row.stockItemId)
        put("label", row.label)
        put("nominal_size", row.nominalSize)
        put("initial_yield_estimate", row.initialYieldEstimate)
    }

    fun encode(row: ProductComponent): JsonObject = buildJsonObject {
        put("id", row.id)
        put("product_id", row.productId)
        put("stock_item_id", row.stockItemId)
        put("quantity_per_unit", row.quantityPerUnit)
    }

    fun encode(row: Delivery): JsonObject = buildJsonObject {
        put("id", row.id)
        put("supplier", row.supplier)
        putMoney("receipt_total", row.receiptTotal)
        put("photo_key", row.photoKey)
        put("note", row.note)
        put("occurred_at", iso(row.timestamp))
    }

    fun encode(row: StockEntry): JsonObject = buildJsonObject {
        put("id", row.id)
        put("stock_item_id", row.stockItemId)
        put("item_name", row.itemName)
        put("quantity", row.quantity)
        put("unit_label", row.unitLabel)
        putMoney("total_cost", row.totalCost)
        put("note", row.note)
        put("source", row.source.name)
        put("occurred_at", iso(row.timestamp))
        put("delivery_id", row.deliveryId)
        put("container_type_id", row.containerTypeId)
    }

    fun encode(row: TappedContainerRow): JsonObject = buildJsonObject {
        put("id", row.id)
        put("container_type_id", row.containerTypeId)
        put("opened_at", iso(row.openedAt))
        put("closed_at", row.closedAt?.let(::iso))
        put("close_reason", row.closeReason?.name)
        put("discarded_volume", row.discardedVolume)
        put("note", row.note)
    }

    fun encode(row: CashSession): JsonObject = buildJsonObject {
        put("id", row.id)
        put("device_label", row.deviceLabel)
        put("opened_at", iso(row.openedAt))
        put("opened_by", row.openedBy)
        put("opening_count", Money.wire(row.openingCount))
        put("closed_at", row.closedAt?.let(::iso))
        put("closed_by", row.closedBy)
        put("closing_count", row.closingCount?.let(Money::wire))
        put("note", row.note)
    }

    fun encode(row: CashMovement): JsonObject = buildJsonObject {
        put("id", row.id)
        put("session_id", row.sessionId)
        put("kind", row.kind.name)
        put("amount", Money.wire(row.amount))
        put("reason", row.reason)
        put("by_name", row.byName)
        put("occurred_at", iso(row.timestamp))
    }

    fun encode(row: Transaction): JsonObject = buildJsonObject {
        put("id", row.id)
        put("transaction_group_id", row.transactionGroupId)
        put("member_id", row.memberId)
        put("member_name", row.memberName)
        put("product_ref", row.productId)
        put("product_name", row.productName)
        put("product_category", row.productCategory)
        put("price", Money.wire(row.price))
        put("quantity", row.quantity)
        put("discount_amount", Money.wire(row.discountAmount))
        put("payment_type", row.paymentType)
        put("occurred_at", iso(row.timestamp))
        put("is_refund", row.isRefund)
        put("note", row.note)
    }

    fun encode(row: StockDraw): JsonObject = buildJsonObject {
        put("id", row.id)
        put("stock_item_id", row.stockItemId)
        put("transaction_id", row.transactionId)
        put("volume", row.volume)
        put("occurred_at", iso(row.timestamp))
        put("note", row.note)
    }

    // ------------------------------------------------------------- vom Server

    fun decodeCategory(row: JsonObject, deleted: Boolean) = MemberCategory(
        id = row.text("id"),
        name = row.text("name"),
        negativeBalanceLimit = row.money("negative_balance_limit"),
        sync = row.meta(deleted)
    )

    fun decodeMember(row: JsonObject, deleted: Boolean) = MemberRow(
        id = row.text("id"),
        name = row.text("name"),
        // Fehlt bei einem älteren Server; dann gibt es eben keinen.
        nickname = row.textOrNull("nickname") ?: "",
        categoryId = row.textOrNull("category_id"),
        sync = row.meta(deleted)
    )

    fun decodeProduct(row: JsonObject, deleted: Boolean) = Product(
        id = row.text("id"),
        name = row.text("name"),
        price = row.money("price"),
        category = row.textOrNull("category") ?: "",
        imageUrl = row.textOrNull("image_url"),
        hasVariants = row.flag("has_variants"),
        servingSize = row.numberOrNull("serving_size") ?: 1.0,
        sync = row.meta(deleted)
    )

    fun decodeVariant(row: JsonObject, deleted: Boolean) = ProductVariant(
        id = row.text("id"),
        productId = row.text("product_id"),
        name = row.text("name"),
        price = row.money("price"),
        servingSize = row.numberOrNull("serving_size"),
        sync = row.meta(deleted)
    )

    fun decodeStockItem(row: JsonObject, deleted: Boolean) = StockItemRow(
        id = row.text("id"),
        name = row.text("name"),
        unit = row.textOrNull("unit") ?: "Stk",
        tracking = StockTracking.entries.firstOrNull { it.name == row.textOrNull("tracking") } ?: StockTracking.SIMPLE,
        minLevel = row.numberOrNull("min_level") ?: 0.0,
        sync = row.meta(deleted)
    )

    fun decodeContainerType(row: JsonObject, deleted: Boolean) = ContainerTypeRow(
        id = row.text("id"),
        stockItemId = row.text("stock_item_id"),
        label = row.text("label"),
        nominalSize = row.number("nominal_size"),
        initialYieldEstimate = row.number("initial_yield_estimate"),
        sync = row.meta(deleted)
    )

    fun decodeComponent(row: JsonObject, deleted: Boolean) = ProductComponent(
        id = row.text("id"),
        productId = row.text("product_id"),
        stockItemId = row.text("stock_item_id"),
        quantityPerUnit = row.number("quantity_per_unit"),
        sync = row.meta(deleted)
    )

    /** [photoUri] ist der Pfad auf diesem Gerät; der Server kennt ihn nicht, also reicht ihn der Aufrufer durch. */
    fun decodeDelivery(row: JsonObject, deleted: Boolean, photoUri: String?) = Delivery(
        id = row.text("id"),
        supplier = row.textOrNull("supplier") ?: "",
        receiptTotal = row.moneyOrNull("receipt_total"),
        photoUri = photoUri,
        photoKey = row.textOrNull("photo_key"),
        note = row.textOrNull("note"),
        timestamp = row.millis("occurred_at"),
        sync = row.meta(deleted)
    )

    fun decodeStockEntry(row: JsonObject, deleted: Boolean) = StockEntry(
        id = row.text("id"),
        stockItemId = row.text("stock_item_id"),
        itemName = row.text("item_name"),
        quantity = row.number("quantity"),
        unitLabel = row.text("unit_label"),
        totalCost = row.moneyOrNull("total_cost"),
        note = row.textOrNull("note"),
        source = StockEntrySource.entries.firstOrNull { it.name == row.textOrNull("source") } ?: StockEntrySource.MANUAL,
        timestamp = row.millis("occurred_at"),
        deliveryId = row.textOrNull("delivery_id"),
        containerTypeId = row.textOrNull("container_type_id"),
        sync = row.meta(deleted)
    )

    fun decodeTapped(row: JsonObject, deleted: Boolean) = TappedContainerRow(
        id = row.text("id"),
        containerTypeId = row.text("container_type_id"),
        openedAt = row.millis("opened_at"),
        closedAt = row.millisOrNull("closed_at"),
        closeReason = row.textOrNull("close_reason")?.let { name -> ContainerCloseReason.entries.firstOrNull { it.name == name } },
        discardedVolume = row.numberOrNull("discarded_volume") ?: 0.0,
        note = row.textOrNull("note"),
        sync = row.meta(deleted)
    )

    fun decodeTransaction(row: JsonObject, deleted: Boolean) = Transaction(
        id = row.text("id"),
        transactionGroupId = row.text("transaction_group_id"),
        memberId = row.textOrNull("member_id"),
        memberName = row.textOrNull("member_name"),
        productId = row.text("product_ref"),
        productName = row.text("product_name"),
        productCategory = row.textOrNull("product_category") ?: "",
        price = row.money("price"),
        quantity = (row["quantity"] as? JsonPrimitive)?.intOrNull ?: throw RowFormatException("quantity fehlt oder ist keine ganze Zahl"),
        discountAmount = row.moneyOrNull("discount_amount") ?: 0.0,
        paymentType = row.text("payment_type"),
        timestamp = row.millis("occurred_at"),
        isRefund = row.flag("is_refund"),
        note = row.textOrNull("note"),
        sync = row.meta(deleted)
    )

    fun decodeCashSession(row: JsonObject, deleted: Boolean) = CashSession(
        id = row.text("id"),
        deviceLabel = row.textOrNull("device_label") ?: "",
        openedAt = row.millis("opened_at"),
        openedBy = row.textOrNull("opened_by") ?: "",
        openingCount = row.money("opening_count"),
        closedAt = row.millisOrNull("closed_at"),
        closedBy = row.textOrNull("closed_by"),
        closingCount = row.moneyOrNull("closing_count"),
        note = row.textOrNull("note"),
        sync = row.meta(deleted)
    )

    fun decodeCashMovement(row: JsonObject, deleted: Boolean) = CashMovement(
        id = row.text("id"),
        sessionId = row.text("session_id"),
        kind = row.textOrNull("kind")?.let { name -> CashMovementKind.entries.firstOrNull { it.name == name } } ?: CashMovementKind.WITHDRAWAL,
        amount = row.money("amount"),
        reason = row.textOrNull("reason") ?: "",
        byName = row.textOrNull("by_name") ?: "",
        timestamp = row.millis("occurred_at"),
        sync = row.meta(deleted)
    )

    fun decodeStockDraw(row: JsonObject, deleted: Boolean) = StockDraw(
        id = row.text("id"),
        stockItemId = row.text("stock_item_id"),
        transactionId = row.textOrNull("transaction_id"),
        volume = row.number("volume"),
        timestamp = row.millis("occurred_at"),
        note = row.textOrNull("note"),
        sync = row.meta(deleted)
    )

    // ---------------------------------------------------------------- Helfer

    /** "2026-09-15T18:22:09.123Z" */
    fun iso(epochMillis: Long): String = Instant.fromEpochMilliseconds(epochMillis).toString()

    fun millisOf(iso: String): Long = try {
        Instant.parse(iso).toEpochMilliseconds()
    } catch (e: IllegalArgumentException) {
        throw RowFormatException("'$iso' ist kein Zeitpunkt nach ISO 8601")
    }

    private fun JsonObjectBuilder.putMoney(key: String, amount: Double?) {
        if (amount == null) put(key, JsonNull) else put(key, Money.wire(amount))
    }

    private fun JsonObject.primitive(key: String): JsonPrimitive? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

    private fun JsonObject.textOrNull(key: String): String? = primitive(key)?.content

    private fun JsonObject.text(key: String): String =
        textOrNull(key) ?: throw RowFormatException("'$key' fehlt")

    private fun JsonObject.numberOrNull(key: String): Double? = primitive(key)?.doubleOrNull

    private fun JsonObject.number(key: String): Double =
        numberOrNull(key) ?: throw RowFormatException("'$key' fehlt oder ist keine Zahl")

    private fun JsonObject.moneyOrNull(key: String): Double? = textOrNull(key)?.let {
        Money.fromWire(it) ?: throw RowFormatException("'$key' ist kein Betrag: '$it'")
    }

    private fun JsonObject.money(key: String): Double =
        moneyOrNull(key) ?: throw RowFormatException("'$key' fehlt")

    private fun JsonObject.flag(key: String): Boolean = primitive(key)?.booleanOrNull ?: false

    private fun JsonObject.millisOrNull(key: String): Long? = textOrNull(key)?.let(::millisOf)

    private fun JsonObject.millis(key: String): Long =
        millisOrNull(key) ?: throw RowFormatException("'$key' fehlt")

    /** Die Sync-Spalten der Serverzeile; [deleted] steht im Drahtformat neben der Zeile, nicht in ihr. */
    private fun JsonObject.meta(deleted: Boolean) = SyncMeta(
        deleted = deleted,
        deletedAt = millisOrNull("deleted_at"),
        serverUpdatedAt = textOrNull("updated_at")
    )
}
