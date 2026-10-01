package com.example.vereins_kassensystem.server

import com.example.vereins_kassensystem.server.payments.Checkout
import com.example.vereins_kassensystem.server.payments.CheckoutRequest
import com.example.vereins_kassensystem.server.payments.PaymentAccount
import com.example.vereins_kassensystem.server.payments.PaymentProblem
import com.example.vereins_kassensystem.server.payments.SumUp
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Der SumUp-Client gegen einen nachgebauten SumUp-Server: Stimmt, was hinausgeht, mit der
 * Dokumentation überein (developer.sumup.com, Checkouts), und wird verstanden, was zurückkommt —
 * die Beispiele dort, Wort für Wort.
 */
class SumUpClientTest {

    private class Seen(val method: String, val path: String, val auth: String?, val body: String)

    /** Antwortet je Pfad mit Status und Text; merkt sich jede Anfrage. */
    private fun <T> sumUp(answer: (method: String, path: String) -> Pair<Int, String>, block: (SumUp, List<Seen>) -> T): T {
        val seen = java.util.concurrent.CopyOnWriteArrayList<Seen>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.toString()
            seen += Seen(exchange.requestMethod, path, exchange.requestHeaders.getFirst("Authorization"), exchange.requestBody.readBytes().decodeToString())
            val (status, text) = answer(exchange.requestMethod, path)
            val bytes = text.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            return block(SumUp(base = "http://127.0.0.1:${server.address.port}"), seen)
        } finally {
            server.stop(0)
        }
    }

    private val account = PaymentAccount("sup_sk_beispiel", "MH4H92C7")

    @Test
    fun `a checkout is created with the hosted page and read back as SumUp documents it`() {
        val reference = UUID.fromString("f00a8f74-b05d-4605-bd73-2a901bae5802")
        sumUp({ method, path ->
            when {
                method == "POST" && path == "/v0.1/checkouts" -> 201 to """{"checkout_reference":"$reference","amount":20.0,"currency":"EUR","merchant_code":"MH4H92C7","description":"Deckel aufladen: Maria Bauer","id":"88fcf8de-304d-4820-8f1c-ec880290eb92","status":"PENDING","date":"2020-02-29T10:56:56+00:00","hosted_checkout_url":"https://checkout.sumup.com/pay/8f9316a3-cda9-42a9-9771-54d534315676","transactions":[]}"""
                method == "GET" && path == "/v0.1/checkouts/88fcf8de-304d-4820-8f1c-ec880290eb92" -> 200 to """{"checkout_reference":"$reference","amount":20,"currency":"EUR","merchant_code":"MH4H92C7","description":"Deckel aufladen","id":"88fcf8de-304d-4820-8f1c-ec880290eb92","status":"PAID","date":"2020-02-29T10:56:56+00:00","transaction_code":"TEENSK4W2K","transaction_id":"410fc44a-5956-44e1-b5cc-19c6f8d727a4","transactions":[{"id":"410fc44a-5956-44e1-b5cc-19c6f8d727a4","status":"SUCCESSFUL","amount":20,"currency":"EUR","payment_type":"ECOM"}]}"""
                method == "GET" && path == "/v0.1/merchants/MH4H92C7/payment-methods?amount=10&currency=EUR" -> 200 to """{"available_payment_methods":[{"id":"apple_pay"},{"id":"google_pay"}]}"""
                else -> 404 to "{}"
            }
        }) { client, seen ->
            val created = client.create(account, CheckoutRequest(reference, BigDecimal("20.00"), "Deckel aufladen: Maria Bauer", "https://deckel.example.at/v1/online/sumup/x", "https://deckel.example.at/konto/clunia/zahlung/y"))
            assertEquals("88fcf8de-304d-4820-8f1c-ec880290eb92", created.id)
            assertEquals(Checkout.Status.PENDING, created.status)
            assertEquals("https://checkout.sumup.com/pay/8f9316a3-cda9-42a9-9771-54d534315676", created.payUrl)

            val sent = seen.single()
            assertEquals("Bearer sup_sk_beispiel", sent.auth)
            val body = Json.parseToJsonElement(sent.body).jsonObject
            assertEquals(reference.toString(), body["checkout_reference"]!!.jsonPrimitive.content)
            assertEquals(0, BigDecimal("20.00").compareTo(body["amount"]!!.jsonPrimitive.content.toBigDecimal()), "der Betrag als Zahl in Euro")
            assertTrue(body["amount"]!!.jsonPrimitive.isString.not(), "eine Zahl, kein Text")
            assertEquals("EUR", body["currency"]!!.jsonPrimitive.content)
            assertEquals("MH4H92C7", body["merchant_code"]!!.jsonPrimitive.content)
            assertEquals("https://deckel.example.at/v1/online/sumup/x", body["return_url"]!!.jsonPrimitive.content, "return_url ist bei SumUp die Benachrichtigung")
            assertEquals("https://deckel.example.at/konto/clunia/zahlung/y", body["redirect_url"]!!.jsonPrimitive.content, "redirect_url die Rückkehr des Mitglieds")
            assertTrue(body["hosted_checkout"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)

            val paid = client.fetch(account, created.id)
            assertEquals(Checkout.Status.PAID, paid.status)
            assertEquals(0, BigDecimal("20").compareTo(paid.amount))
            assertEquals(reference.toString(), paid.reference)
            assertEquals("TEENSK4W2K", paid.transactionCode)
            assertEquals(listOf("apple_pay", "google_pay"), client.methods(account))
        }
    }

    @Test
    fun `refusals and outages come back in words, and only outages ask for another try`() {
        fun problem(status: Int, body: String): PaymentProblem = sumUp({ _, _ -> status to body }) { client, _ ->
            assertFailsWith<PaymentProblem> { client.fetch(account, "x") }
        }
        assertContains(problem(401, """{"detail":"Unauthorized.","status":401,"title":"Unauthorized","type":"https://developer.sumup.com/problem/unauthorized"}""").message!!, "API-Schlüssel")
        val notAllowed = problem(403, """{"error_message":"checkout_payments_not_allowed","error_code":"FORBIDDEN","status_code":"403"}""")
        assertContains(notAllowed.message!!, "noch keine Online-Zahlungen"); assertTrue(!notAllowed.retry)
        assertContains(problem(400, """{"message":"Validation error","error_code":"MISSING","param":"merchant_code"}""").message!!, "MISSING")
        assertTrue(problem(503, "").retry)

        // Ein unbekannter Status ist nicht bezahlt.
        val odd = sumUp({ _, _ -> 200 to """{"id":"c1","checkout_reference":"r","status":"SOMETHING_NEW","amount":5,"currency":"EUR","merchant_code":"MH4H92C7"}""" }) { client, _ -> client.fetch(account, "c1") }
        assertEquals(Checkout.Status.PENDING, odd.status); assertNull(odd.payUrl)

        // Niemand da: später noch einmal.
        val gone = SumUp(base = "http://127.0.0.1:1")
        assertTrue(assertFailsWith<PaymentProblem> { gone.fetch(account, "c1") }.retry)
    }
}
