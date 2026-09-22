package com.example.vereins_kassensystem

import com.example.vereins_kassensystem.data.Ledger
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
import com.example.vereins_kassensystem.data.sync.RowCodec
import com.example.vereins_kassensystem.data.sync.RowFormatException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Jede Tabelle einmal im Kreis: Zeile → Drahtformat → Zeile. Ein Feld, das in einer der
 * beiden Richtungen fehlt, kommt hier nicht heil zurück.
 */
class RowCodecTest {

    private val time = 1_789_000_123_456
    private val stamp = "2026-09-10T00:28:43.456Z"

    /** Was der Server aus der Zeile macht: dieselben Felder, dazu seine Sync-Spalten. */
    private fun JsonObject.fromServer(deleted: Boolean = false) = JsonObject(
        this + mapOf("updated_at" to JsonPrimitive(stamp)) +
            if (deleted) mapOf("deleted_at" to JsonPrimitive(stamp)) else emptyMap()
    )

    private val pulled = SyncMeta(deleted = false, deletedAt = null, serverUpdatedAt = stamp)

    @Test
    fun `master data survives the round trip`() {
        val category = MemberCategory(name = "Aktive", negativeBalanceLimit = -20.0)
        assertEquals(category.copy(sync = pulled), RowCodec.decodeCategory(RowCodec.encode(category).fromServer(), false))

        val member = MemberRow(name = "Maria Bauer", nickname = "Minerva", categoryId = category.id, blockedReason = "Abrechnung offen")
        assertEquals(member.copy(sync = pulled), RowCodec.decodeMember(RowCodec.encode(member).fromServer(), false))
        // Keine Sperre reist als null oder als leerer Text — beides heißt: nicht gesperrt.
        assertNull(RowCodec.decodeMember(RowCodec.encode(member.copy(blockedReason = null)).fromServer(), false).blockedReason)
        assertNull(RowCodec.decodeMember(JsonObject(RowCodec.encode(member).fromServer() + ("blocked_reason" to kotlinx.serialization.json.JsonPrimitive(""))), false).blockedReason)
        // Ein Server, der den Couleurnamen noch nicht kennt, schickt keinen: dann gibt es keinen.
        assertEquals("", RowCodec.decodeMember(JsonObject(RowCodec.encode(member).fromServer() - "nickname"), false).nickname)
        assertNull(RowCodec.decodeMember(RowCodec.encode(member.copy(categoryId = null)).fromServer(), false).categoryId)

        val product = Product(name = "Radler", price = 3.8, category = "Getränke", imageUrl = null, hasVariants = true, servingSize = 0.5)
        assertEquals(product.copy(sync = pulled), RowCodec.decodeProduct(RowCodec.encode(product).fromServer(), false))

        val variant = ProductVariant(productId = product.id, name = "0,3l", price = 2.9, servingSize = 0.33)
        assertEquals(variant.copy(sync = pulled), RowCodec.decodeVariant(RowCodec.encode(variant).fromServer(), false))
        assertNull(RowCodec.decodeVariant(RowCodec.encode(variant.copy(servingSize = null)).fromServer(), false).servingSize)

        val item = StockItemRow(name = "Bier", unit = "l", tracking = StockTracking.CONTAINER, minLevel = 30.0)
        assertEquals(item.copy(sync = pulled), RowCodec.decodeStockItem(RowCodec.encode(item).fromServer(), false))

        val type = ContainerTypeRow(stockItemId = item.id, label = "50 l Fass", nominalSize = 50.0, initialYieldEstimate = 49.0)
        assertEquals(type.copy(sync = pulled), RowCodec.decodeContainerType(RowCodec.encode(type).fromServer(), false))

        val component = ProductComponent(productId = product.id, stockItemId = item.id, quantityPerUnit = 0.5)
        assertEquals(component.copy(sync = pulled), RowCodec.decodeComponent(RowCodec.encode(component).fromServer(), false))
    }

    @Test
    fun `movements survive the round trip`() {
        val sale = Transaction(
            transactionGroupId = "018f2b6c-7d1e-7a00-8000-000000000001", memberId = "018f2b6c-7d1e-7a00-8000-00000000000a",
            memberName = "Maria Bauer", productId = Ledger.TOPUP_REF, productName = "Guthabenaufladung",
            productCategory = "Aufladung", price = -0.5, quantity = 1, discountAmount = 0.0, paymentType = "CORRECTION",
            timestamp = time, isRefund = true, note = "Glas zerbrochen"
        )
        assertEquals(sale.copy(sync = pulled), RowCodec.decodeTransaction(RowCodec.encode(sale).fromServer(), false))

        val delivery = Delivery(supplier = "Getränke Huber", receiptTotal = 412.8, photoUri = "file:///bon.jpg", photoKey = "abc.jpg", note = null, timestamp = time)
        val wire = RowCodec.encode(delivery)
        assertTrue("photoUri" !in wire.keys && "photo_uri" !in wire.keys, "der Pfad gilt nur auf diesem Gerät")
        assertEquals(delivery.copy(sync = pulled), RowCodec.decodeDelivery(wire.fromServer(), false, photoUri = "file:///bon.jpg"))

        val entry = StockEntry(
            stockItemId = "018f2b6c-7d1e-7a00-8000-00000000000b", itemName = "Bier", quantity = -2.0, unitLabel = "50 l Fass",
            totalCost = null, note = "Inventur", source = StockEntrySource.CORRECTION, timestamp = time,
            deliveryId = delivery.id, containerTypeId = "018f2b6c-7d1e-7a00-8000-00000000000c"
        )
        assertEquals(entry.copy(sync = pulled), RowCodec.decodeStockEntry(RowCodec.encode(entry).fromServer(), false))

        val tapped = TappedContainerRow(
            containerTypeId = "018f2b6c-7d1e-7a00-8000-00000000000c", openedAt = time, closedAt = time + 60_000,
            closeReason = ContainerCloseReason.SPOILED, discardedVolume = 11.0, note = "stand in der Sonne"
        )
        assertEquals(tapped.copy(sync = pulled), RowCodec.decodeTapped(RowCodec.encode(tapped).fromServer(), false))
        val open = tapped.copy(closedAt = null, closeReason = null)
        assertEquals(open.copy(sync = pulled), RowCodec.decodeTapped(RowCodec.encode(open).fromServer(), false))

        val draw = StockDraw(stockItemId = entry.stockItemId, transactionId = sale.id, volume = 0.25, timestamp = time, note = null)
        assertEquals(draw.copy(sync = pulled), RowCodec.decodeStockDraw(RowCodec.encode(draw).fromServer(), false))
    }

    @Test
    fun `the wire carries money as text and time as iso under the server's names`() {
        val sale = Transaction(
            transactionGroupId = "g", memberId = null, memberName = null, productId = "p", productName = "Weißbier 0,5l",
            productCategory = "Getränke", price = 4.2, quantity = 2, discountAmount = 0.386, timestamp = time
        )
        val wire = RowCodec.encode(sale)
        assertEquals("4.20", wire["price"]!!.jsonPrimitive.content)
        assertTrue(wire["price"]!!.jsonPrimitive.isString, "Geld ist eine Zeichenkette, keine Gleitkommazahl")
        assertEquals("0.39", wire["discount_amount"]!!.jsonPrimitive.content, "auf Cent gerundet")
        assertEquals(stamp, wire["occurred_at"]!!.jsonPrimitive.content)
        assertEquals("p", wire["product_ref"]!!.jsonPrimitive.content, "productId heißt auf dem Server product_ref")
        assertEquals(time, RowCodec.millisOf("2026-09-10T00:28:43.456Z"))
        assertEquals(time - 456, RowCodec.millisOf("2026-09-10T00:28:43Z"))
    }

    @Test
    fun `a deleted row and a broken row are both told apart from a good one`() {
        val member = MemberRow(name = "Ex-Mitglied")
        val gone = RowCodec.decodeMember(RowCodec.encode(member).fromServer(deleted = true), deleted = true)
        assertTrue(gone.sync.deleted)
        assertEquals(time, gone.sync.deletedAt)

        assertFailsWith<RowFormatException> { RowCodec.decodeMember(buildJsonObject { put("id", member.id) }, false) }
        assertFailsWith<RowFormatException> {
            RowCodec.decodeProduct(buildJsonObject { put("id", "p"); put("name", "Bier"); put("price", "vier zwanzig") }, false)
        }
    }
}
