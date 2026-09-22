package com.example.vereins_kassensystem.server

import com.example.vereins_kassensystem.data.Ledger
import com.example.vereins_kassensystem.server.http.PairingCodeResponse
import com.example.vereins_kassensystem.sync.PushOperation
import com.example.vereins_kassensystem.sync.PushStatus
import com.example.vereins_kassensystem.sync.SyncClient
import com.example.vereins_kassensystem.sync.SyncHttpException
import com.example.vereins_kassensystem.sync.installSyncDefaults
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.http.ContentType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Der Client aus `:core` — derselbe Code, den das Tablet benutzt — gegen den echten Dienst.
 * Wenn sich das Drahtformat auf einer Seite ändert, fällt es hier auf und nicht am Tresen.
 */
class SyncClientTest {

    @Test
    fun `the client from core pairs, pushes, pulls and reads a balance`() = serverTest { ctx ->
        val http = createClient { installSyncDefaults() }
        val anonymous = SyncClient(http, baseUrl = "", token = { null })
        assertEquals("ok", anonymous.health().status)

        val code = ctx.client.post("/v1/admin/pairing-codes") { bearerAuth(ADMIN_TOKEN) }.body<PairingCodeResponse>().code
        val registration = anonymous.register(code, "iPad Garten", "ios")
        assertEquals(0L, registration.initialSince)
        val client = SyncClient(http, baseUrl = "", token = { registration.token })

        val memberId = newId(); val itemId = newId(); val kegId = newId(); val saleId = newId()
        val pushed = client.push(
            listOf(
                insertOp("members", buildJsonObject { put("id", memberId); put("name", "Maria Bauer") }),
                insertOp("stock_items", buildJsonObject { put("id", itemId); put("name", "Helles"); put("unit", "l"); put("tracking", "CONTAINER") }),
                insertOp("container_types", buildJsonObject {
                    put("id", kegId); put("stock_item_id", itemId); put("label", "50 l Fass")
                    put("nominal_size", 50.0); put("initial_yield_estimate", 48.0)
                }),
                // Wareneingang mit Gebindegröße, Verkauf auf den Deckel, und was er dem Keller entnahm.
                insertOp("stock_entries", buildJsonObject {
                    put("id", newId()); put("stock_item_id", itemId); put("item_name", "Helles"); put("quantity", 2.0)
                    put("unit_label", "50 l Fass"); put("source", "MANUAL"); put("occurred_at", "2026-09-15T16:00:00Z")
                    put("container_type_id", kegId)
                }),
                insertOp("transactions", buildJsonObject {
                    put("id", saleId); put("transaction_group_id", newId()); put("member_id", memberId)
                    put("member_name", "Maria Bauer"); put("product_ref", newId()); put("product_name", "Helles 0,5l")
                    put("price", "4.20"); put("quantity", 2); put("payment_type", Ledger.MEMBER_BALANCE)
                    put("occurred_at", "2026-09-15T18:22:09Z")
                }),
                insertOp("stock_draws", buildJsonObject {
                    put("id", newId()); put("stock_item_id", itemId); put("transaction_id", saleId)
                    put("volume", 1.0); put("occurred_at", "2026-09-15T18:22:09Z")
                }),
            )
        )
        assertEquals(List(6) { PushStatus.APPLIED }, pushed.results.map { it.status })

        val pulled = client.changes(since = 0)
        assertEquals(
            listOf("members", "stock_items", "container_types", "stock_entries", "transactions", "stock_draws"),
            pulled.changes.map { it.entity }
        )
        assertEquals(kegId, pulled.changes[3].row["container_type_id"]!!.jsonPrimitive.content)
        assertEquals("-8.40", client.balance(memberId).balance)
    }

    @Test
    fun `receipts and errors come through the client as the app expects them`() = serverTest { ctx ->
        val http = createClient { installSyncDefaults() }
        val anonymous = SyncClient(http, baseUrl = "", token = { null })
        val code = ctx.client.post("/v1/admin/pairing-codes") { bearerAuth(ADMIN_TOKEN) }.body<PairingCodeResponse>().code
        val token = anonymous.register(code, "Theke", "android").token
        val client = SyncClient(http, baseUrl = "", token = { token })

        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
        val key = client.uploadReceipt(jpeg, ContentType.Image.JPEG)
        assertContentEquals(jpeg, client.downloadReceipt(key))

        // 401: neu koppeln. 409: Code verbraucht. 422: Programmfehler. 404: unbekannt.
        assertEquals(401, assertFailsWith<SyncHttpException> { anonymous.changes(0) }.status)
        val used = assertFailsWith<SyncHttpException> { anonymous.register(code, "Zweites", "ios") }
        assertEquals(409, used.status)
        assertEquals("pairing_failed", used.code)
        val refused = assertFailsWith<SyncHttpException> {
            client.push(listOf(PushOperation(newId(), "stock_draws", "update", row = buildJsonObject { put("id", newId()); put("volume", 2.0) })))
        }
        assertEquals(422, refused.status)
        assertEquals(404, assertFailsWith<SyncHttpException> { client.balance(newId()) }.status)
    }
}
