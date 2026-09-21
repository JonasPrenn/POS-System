package com.example.vereins_kassensystem.server

import com.example.vereins_kassensystem.data.Ledger
import com.example.vereins_kassensystem.server.http.BalanceResponse
import com.example.vereins_kassensystem.server.http.ChangesResponse
import com.example.vereins_kassensystem.server.http.ErrorResponse
import com.example.vereins_kassensystem.server.http.PushOperation
import com.example.vereins_kassensystem.server.http.PushRequest
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Das Synchronisationsprotokoll gegen echtes PostgreSQL — die Abnahmekriterien A2 bis A6
 * aus Kapitel 8, soweit sie sich ohne zweites Gerät nachstellen lassen.
 */
class SyncTest {

    private fun category(id: String) = buildJsonObject {
        put("id", id); put("name", "Aktive"); put("negative_balance_limit", "-20.00")
    }

    private fun member(id: String, categoryId: String?) = buildJsonObject {
        put("id", id); put("name", "M. Bauer"); put("category_id", categoryId)
    }

    private fun product(id: String, price: String = "4.20") = buildJsonObject {
        put("id", id); put("name", "Weissbier 0,5l"); put("price", price); put("category", "Getränke")
        put("serving_size", 0.5)
    }

    private fun transaction(
        id: String, memberId: String?, productRef: String, price: String, quantity: Int,
        paymentType: String, discount: String = "0.00", refund: Boolean = false,
    ) = buildJsonObject {
        put("id", id); put("transaction_group_id", newId()); put("member_id", memberId)
        put("member_name", "M. Bauer"); put("product_ref", productRef); put("product_name", "Weissbier 0,5l")
        put("price", price); put("quantity", quantity); put("discount_amount", discount)
        put("payment_type", paymentType); put("occurred_at", "2026-09-15T18:22:09Z"); put("is_refund", refund)
    }

    @Test
    fun `a push applies in order and a pull returns the rows in sequence order`() = serverTest { ctx ->
        val device = ctx.pairDevice()
        val categoryId = newId(); val memberId = newId(); val productId = newId(); val saleId = newId()

        val response = ctx.push(
            device.token,
            insertOp("member_categories", category(categoryId)),
            insertOp("members", member(memberId, categoryId)),
            insertOp("products", product(productId)),
            insertOp("transactions", transaction(saleId, memberId, productId, "4.20", 1, "MEMBER_BALANCE")),
        )
        assertEquals(listOf("applied", "applied", "applied", "applied"), response.results.map { it.status })
        val seqs = response.results.map { assertNotNull(it.seq) }
        assertEquals(seqs.sorted(), seqs, "Sequenznummern steigen in Operationsreihenfolge")
        assertEquals(seqs.last(), response.nextSince)

        val pulled = ctx.client.get("/v1/sync/changes?since=0") { bearerAuth(device.token) }.body<ChangesResponse>()
        assertEquals(listOf("member_categories", "members", "products", "transactions"), pulled.changes.map { it.entity })
        assertEquals(seqs, pulled.changes.map { it.seq })
        assertEquals(false, pulled.hasMore)
        assertEquals(seqs.last(), pulled.nextSince)

        val sale = pulled.changes.last().row
        assertEquals("4.20", sale["price"]!!.jsonPrimitive.content, "Geld bleibt eine Zeichenkette mit zwei Stellen")
        assertEquals("2026-09-15T18:22:09.000Z", sale["occurred_at"]!!.jsonPrimitive.content)
        assertEquals("0.00", sale["discount_amount"]!!.jsonPrimitive.content, "Vorgabewert wurde gesetzt")
        assertNotNull(sale["updated_at"])
    }

    @Test
    fun `paging walks the whole stream without gaps`() = serverTest { ctx ->
        val device = ctx.pairDevice()
        val ops = (1..5).map { insertOp("products", product(newId(), price = "$it.00")) }
        ctx.push(device.token, *ops.toTypedArray())

        val first = ctx.client.get("/v1/sync/changes?since=0&limit=2") { bearerAuth(device.token) }.body<ChangesResponse>()
        assertEquals(2, first.changes.size)
        assertEquals(true, first.hasMore)

        val second = ctx.client.get("/v1/sync/changes?since=${first.nextSince}&limit=2") { bearerAuth(device.token) }.body<ChangesResponse>()
        val third = ctx.client.get("/v1/sync/changes?since=${second.nextSince}&limit=2") { bearerAuth(device.token) }.body<ChangesResponse>()
        assertEquals(2, second.changes.size)
        assertEquals(1, third.changes.size)
        assertEquals(false, third.hasMore)

        val prices = (first.changes + second.changes + third.changes).map { it.row["price"]!!.jsonPrimitive.content }
        assertEquals(listOf("1.00", "2.00", "3.00", "4.00", "5.00"), prices)

        val empty = ctx.client.get("/v1/sync/changes?since=${third.nextSince}") { bearerAuth(device.token) }.body<ChangesResponse>()
        assertEquals(0, empty.changes.size)
        assertEquals(third.nextSince, empty.nextSince, "ohne Änderungen bleibt der Zeiger stehen")
    }

    @Test
    fun `a repeated push is answered like the first one and books nothing twice`() = serverTest { ctx ->
        // Abnahmekriterium A4: Antwort ging verloren, der Client wiederholt.
        val device = ctx.pairDevice()
        val memberId = newId()
        val op = insertOp("transactions", transaction(newId(), null, Ledger.MANUAL_REF, "3.00", 1, "CASH"))

        val first = ctx.push(device.token, insertOp("members", member(memberId, null)), op)
        val again = ctx.push(device.token, op)
        assertEquals(first.results[1].status, again.results.single().status)
        assertEquals(first.results[1].seq, again.results.single().seq)

        val count = ctx.db.read { c ->
            c.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM transactions").use { it.next(); it.getLong(1) } }
        }
        assertEquals(1L, count)
    }

    @Test
    fun `the derived balance matches the ledger rule from core`() = serverTest { ctx ->
        // Abnahmekriterium A3 und die Regel aus Ledger.kt gegen die SQL-Sicht.
        val theke = ctx.pairDevice("Theke")
        val ipad = ctx.pairDevice("iPad", "ios")
        val memberId = newId(); val productId = newId()

        data class Line(val ref: String, val type: String, val price: String, val qty: Int, val discount: String = "0.00", val refund: Boolean = false)
        val lines = listOf(
            Line(Ledger.TOPUP_REF, "CASH", "20.00", 1),
            Line(productId, "MEMBER_BALANCE", "4.00", 1),
            Line(productId, "MEMBER_BALANCE", "5.00", 1),
            Line(productId, "MEMBER_BALANCE", "2.50", 2, discount = "1.00"),
            Line(productId, "CASH", "4.00", 3),
            Line(Ledger.TIP_REF, "CARD", "1.00", 1),
            Line(productId, "MEMBER_BALANCE", "4.00", 1, refund = true),
            Line(Ledger.TOPUP_REF, "CORRECTION", "-0.50", 1),
        )
        ctx.push(theke.token, insertOp("members", member(memberId, null)), insertOp("products", product(productId)))
        lines.forEachIndexed { i, line ->
            val token = if (i % 2 == 0) theke.token else ipad.token
            ctx.push(token, insertOp("transactions", transaction(newId(), memberId, line.ref, line.price, line.qty, line.type, line.discount, line.refund)))
        }

        val expected = lines.sumOf {
            Ledger.balanceEffect(it.ref, it.type, it.price.toDouble(), it.qty, it.discount.toDouble(), it.refund)
        }
        val body = ctx.client.get("/v1/members/$memberId/balance") { bearerAuth(theke.token) }.body<BalanceResponse>()
        assertEquals(BigDecimal(expected).setScale(2, RoundingMode.HALF_UP).toPlainString(), body.balance)
        assertEquals("10.50", body.balance, "20 − 4 − 5 − (5 − 1) + 4 − 0,50")

        assertEquals(HttpStatusCode.NotFound, ctx.client.get("/v1/members/${newId()}/balance") { bearerAuth(theke.token) }.status)
    }

    @Test
    fun `master data follows last write wins over base_updated_at`() = serverTest { ctx ->
        // Abnahmekriterium A5.
        val theke = ctx.pairDevice("Theke")
        val ipad = ctx.pairDevice("iPad", "ios")
        val productId = newId()
        ctx.push(theke.token, insertOp("products", product(productId, "4.20")))
        val base = ctx.client.get("/v1/sync/changes?since=0") { bearerAuth(ipad.token) }
            .body<ChangesResponse>().changes.single().row["updated_at"]!!.jsonPrimitive.content

        val theirs = ctx.push(theke.token, updateOp("products", buildJsonObject { put("id", productId); put("price", "4.50") }, base))
        assertEquals("applied", theirs.results.single().status)

        val stale = ctx.push(ipad.token, updateOp("products", buildJsonObject { put("id", productId); put("price", "4.40") }, base))
        assertEquals("ignored_stale", stale.results.single().status)
        assertEquals("4.50", stale.results.single().current!!["price"]!!.jsonPrimitive.content, "das iPad bekommt den Serverwert")

        val blind = ctx.push(ipad.token, updateOp("products", buildJsonObject { put("id", productId); put("price", "4.40") }))
        assertEquals("applied", blind.results.single().status, "ohne Basis gewinnt der letzte Schreibvorgang")

        val duplicate = ctx.push(ipad.token, insertOp("products", product(productId, "9.99")))
        assertEquals("ignored_stale", duplicate.results.single().status, "gleiche id ist dieselbe Zeile")
        assertEquals("4.40", duplicate.results.single().current!!["price"]!!.jsonPrimitive.content)
    }

    @Test
    fun `deleting is soft and shows up on the next pull`() = serverTest { ctx ->
        val device = ctx.pairDevice()
        val memberId = newId()
        ctx.push(device.token, insertOp("members", member(memberId, null)))
        val deleted = ctx.push(device.token, deleteOp("members", buildJsonObject { put("id", memberId) }))
        assertEquals("applied", deleted.results.single().status)

        val pulled = ctx.client.get("/v1/sync/changes?since=0") { bearerAuth(device.token) }.body<ChangesResponse>()
        val row = pulled.changes.single { it.entity == "members" }
        assertEquals(true, row.deleted)
        assertNotNull(row.row["deleted_at"])

        val again = ctx.push(device.token, deleteOp("members", buildJsonObject { put("id", memberId) }))
        assertEquals("applied", again.results.single().status, "nochmal löschen ist harmlos")
        assertEquals(HttpStatusCode.NotFound, ctx.client.get("/v1/members/$memberId/balance") { bearerAuth(device.token) }.status)
    }

    @Test
    fun `append-only tables and derived fields are refused as a whole`() = serverTest { ctx ->
        val device = ctx.pairDevice()
        val memberId = newId(); val saleId = newId()
        ctx.push(device.token,
            insertOp("members", member(memberId, null)),
            insertOp("transactions", transaction(saleId, memberId, Ledger.MANUAL_REF, "3.00", 1, "MEMBER_BALANCE")),
        )

        suspend fun expect422(vararg ops: PushOperation, contains: String) {
            val response = ctx.pushRaw(device.token, *ops)
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status, response.bodyAsTextSafe())
            val message = response.body<ErrorResponse>().message
            assertTrue(message.contains(contains), "'$message' sollte '$contains' enthalten")
        }

        expect422(updateOp("transactions", buildJsonObject { put("id", saleId); put("price", "1.00") }), contains = "nie geändert")
        expect422(deleteOp("transactions", buildJsonObject { put("id", saleId) }), contains = "nie gelöscht")
        expect422(updateOp("members", buildJsonObject { put("id", memberId); put("balance", "99.00") }), contains = "abgeleitet")
        expect422(insertOp("member_balances", buildJsonObject { put("member_id", memberId); put("balance", "99.00") }), contains = "abgeleitet")
        expect422(updateOp("members", buildJsonObject { put("id", memberId); put("memberName", "x") }), contains = "unbekanntes Feld")
        expect422(insertOp("products", buildJsonObject { put("id", newId()); put("name", "Bier"); put("price", "4.205") }), contains = "Nachkommastellen")
        expect422(insertOp("products", buildJsonObject { put("id", newId()); put("name", "Bier") }), contains = "'price' fehlt")
        expect422(insertOp("stock_items", buildJsonObject { put("id", newId()); put("name", "Bier"); put("tracking", "KEG") }), contains = "tracking")

        // Alles oder nichts: Die gültige erste Operation wurde mit der ungültigen zweiten verworfen.
        val productId = newId()
        expect422(
            insertOp("products", product(productId)),
            updateOp("transactions", buildJsonObject { put("id", saleId); put("price", "1.00") }),
            contains = "nie geändert",
        )
        val pulled = ctx.client.get("/v1/sync/changes?since=0") { bearerAuth(device.token) }.body<ChangesResponse>()
        assertNull(pulled.changes.firstOrNull { it.row["id"]?.jsonPrimitive?.content == productId })
    }

    @Test
    fun `only one vessel per stock item is open and the earlier tap wins`() = serverTest { ctx ->
        // Abnahmekriterium A6.
        val theke = ctx.pairDevice("Theke")
        val ipad = ctx.pairDevice("iPad", "ios")
        val itemId = newId(); val keg50 = newId(); val keg30 = newId()
        ctx.push(theke.token,
            insertOp("stock_items", buildJsonObject { put("id", itemId); put("name", "Helles"); put("unit", "l"); put("tracking", "CONTAINER") }),
            insertOp("container_types", buildJsonObject { put("id", keg50); put("stock_item_id", itemId); put("label", "50 l Fass"); put("nominal_size", 50.0); put("initial_yield_estimate", 48.0) }),
            insertOp("container_types", buildJsonObject { put("id", keg30); put("stock_item_id", itemId); put("label", "30 l Fass"); put("nominal_size", 30.0); put("initial_yield_estimate", 28.5) }),
        )
        fun tapped(id: String, type: String, openedAt: String) = buildJsonObject {
            put("id", id); put("container_type_id", type); put("opened_at", openedAt)
        }

        val a = newId(); val b = newId(); val c = newId()
        assertEquals("applied", ctx.push(theke.token, insertOp("tapped_containers", tapped(a, keg50, "2026-09-15T18:00:00Z"))).results.single().status)

        // Später angestochen, anderes Gebinde, derselbe Artikel: verworfen.
        val later = ctx.push(ipad.token, insertOp("tapped_containers", tapped(b, keg30, "2026-09-15T18:05:00Z"))).results.single()
        assertEquals("ignored_stale", later.status)
        assertEquals(a, later.current!!["id"]!!.jsonPrimitive.content)

        // Früher angestochen (das iPad war offline): gewinnt, A wird verworfen.
        val earlier = ctx.push(ipad.token, insertOp("tapped_containers", tapped(c, keg50, "2026-09-15T17:55:00Z"))).results.single()
        assertEquals("applied", earlier.status)
        val pulled = ctx.client.get("/v1/sync/changes?since=0") { bearerAuth(theke.token) }.body<ChangesResponse>()
        val byId = pulled.changes.filter { it.entity == "tapped_containers" }.associateBy { it.row["id"]!!.jsonPrimitive.content }
        assertEquals(true, byId.getValue(a).deleted)
        assertEquals(false, byId.getValue(c).deleted)
        assertNull(byId[b])

        // Schließen ist eine gewöhnliche Änderung; danach darf wieder angestochen werden.
        val closed = ctx.push(ipad.token, updateOp("tapped_containers", buildJsonObject {
            put("id", c); put("closed_at", "2026-09-15T22:00:00Z"); put("close_reason", "EMPTIED")
        }))
        assertEquals("applied", closed.results.single().status)
        assertEquals("applied", ctx.push(theke.token, insertOp("tapped_containers", tapped(newId(), keg30, "2026-09-15T22:01:00Z"))).results.single().status)
    }

    @Test
    fun `the device id in the body must match the token`() = serverTest { ctx ->
        val device = ctx.pairDevice()
        val response = ctx.client.post("/v1/sync/push") {
            bearerAuth(device.token)
            contentType(ContentType.Application.Json)
            setBody(PushRequest(deviceId = newId(), operations = emptyList()))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    }
}
