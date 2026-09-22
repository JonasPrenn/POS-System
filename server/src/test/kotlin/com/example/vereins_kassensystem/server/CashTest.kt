package com.example.vereins_kassensystem.server

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.client.request.bearerAuth
import io.ktor.client.call.body
import com.example.vereins_kassensystem.sync.ChangesResponse
import com.example.vereins_kassensystem.data.Ledger
import com.example.vereins_kassensystem.server.web.Accounts
import com.example.vereins_kassensystem.server.web.Role
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Das Kassenbuch (Konzept 4.5) aus dem, was zwei Tablets melden. */
class CashTest {

    @Test
    fun `two tills report their shifts and the book keeps each drawer apart`() = serverTest(insecureCookies = true) { ctx ->
        val links = ctx.pairDevice("Theke links"); val garten = ctx.pairDevice("iPad Garten", "ios")
        val helles = newId(); val start = Instant.now().minus(3, ChronoUnit.HOURS)
        fun sale(price: String, qty: Int, type: String, at: Instant, ref: String = helles, product: String = "Helles 0,5", refund: Boolean = false) = insertOp("transactions", buildJsonObject {
            put("id", newId()); put("transaction_group_id", newId()); put("member_id", null as String?); put("product_ref", ref); put("product_name", product)
            put("price", price); put("quantity", qty); put("payment_type", type); put("occurred_at", at.toString()); put("is_refund", refund)
        })
        ctx.push(links.token, insertOp("products", buildJsonObject { put("id", helles); put("name", "Helles 0,5"); put("price", "4.20"); put("category", "Getränke") }))

        // Theke links: Schicht mit 150 Wechselgeld, verkauft 8,40 bar und 4,20 Karte, Aufladung 20 bar, 50 zur Bank, geschlossen mit 126,40 (2 Euro zu wenig).
        val session = newId()
        ctx.push(
            links.token,
            insertOp("cash_sessions", buildJsonObject { put("id", session); put("device_label", "Theke links"); put("opened_at", start.toString()); put("opened_by", "Matthias"); put("opening_count", "150.00") }),
            sale("4.20", 2, "CASH", start.plusSeconds(600)),
            sale("4.20", 1, "CARD", start.plusSeconds(700)),
            sale("20.00", 1, "CASH", start.plusSeconds(800), ref = Ledger.TOPUP_REF, product = "Guthabenaufladung"),
            sale("4.20", 1, "CASH", start.plusSeconds(900), refund = true),
            insertOp("cash_movements", buildJsonObject { put("id", newId()); put("session_id", session); put("kind", "WITHDRAWAL"); put("amount", "50.00"); put("reason", "zur Bank"); put("by_name", "Matthias"); put("occurred_at", start.plusSeconds(1000).toString()) }),
        )
        // Das iPad verkauft währenddessen bar — in seine eigene Lade, ohne Schicht. Danach beginnt dort ein Bardienst ohne Barkasse.
        ctx.push(garten.token, sale("4.20", 3, "CASH", start.plusSeconds(650)))
        ctx.push(garten.token, insertOp("cash_sessions", buildJsonObject { put("id", newId()); put("device_label", "iPad Garten"); put("opened_at", start.plusSeconds(3000).toString()); put("opened_by", "Anna Berger"); put("opening_count", "0.00"); put("cashless", true) }))
        val closedAt = start.plusSeconds(7200)
        ctx.push(links.token, updateOp("cash_sessions", buildJsonObject { put("id", session); put("closed_at", closedAt.toString()); put("closed_by", "Matthias"); put("closing_count", "122.20"); put("note", "Wechselgeld verzählt") }))

        Accounts(ctx.db).create("lukas", "Lukas Hofer", Role.KASSIER, "ein-langes-passwort")
        val browser = createClient { install(HttpCookies); followRedirects = false }
        browser.submitForm("/verwaltung/anmelden", parameters { append("login", "lukas"); append("passwort", "ein-langes-passwort") })
        val page = browser.get("/verwaltung/kasse")
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        // Tagesbericht je Gerät: links 8,40 + 20 Aufladung zählt nicht als Umsatz − 4,20 Storno = 4,20 bar, 4,20 Karte; Garten 12,60 bar.
        assertContains(html, "Theke links"); assertContains(html, "iPad Garten")
        assertContains(html, "12,60 €")
        // Kassenbuch: Soll 150 + (8,40 + 20 − 4,20) − 50 = 124,20; gezählt 122,20 → Differenz −2,00. Die 12,60 vom iPad tauchen in dieser Lade nicht auf.
        assertContains(html, "Schicht geöffnet, Wechselgeld gezählt")
        assertContains(html, "+24,20 €", message = "Bareinnahmen der Schicht")
        assertContains(html, "−2,00 €", message = "Differenz")
        assertContains(html, "Wechselgeld verzählt")
        assertContains(html, "Entnahme: zur Bank")
        assertFalse(html.contains("+12,60 €"), "die Lade des iPads ist eine andere")
        // Der Bardienst ohne Barkasse steht als solcher da — und nicht im Kassenbuch, er hat keine Lade.
        assertContains(html, "Bardienst · iPad Garten"); assertContains(html, "ohne Barkasse"); assertContains(html, "Anna Berger")
        assertEquals(1, Regex("Schicht geöffnet, Wechselgeld gezählt").findAll(html).count(), "nur die Schicht mit Lade steht im Kassenbuch")
        assertEquals(true, ctx.client.get("/v1/sync/changes?since=0") { bearerAuth(links.token) }.body<ChangesResponse>().changes.first { it.entity == "cash_sessions" && it.row["opened_by"]!!.jsonPrimitive.content == "Anna Berger" }.row["cashless"]!!.jsonPrimitive.booleanOrNull)

        val csv = browser.get("/verwaltung/kasse/kassenbuch.csv?von=${closedAt.atZone(ctx.zone()).toLocalDate().minusDays(1)}&bis=${closedAt.atZone(ctx.zone()).toLocalDate()}")
        assertEquals(HttpStatusCode.OK, csv.status)
        assertContains(csv.bodyAsText(), "\"Bareinnahmen der Schicht\"")
        assertContains(csv.bodyAsText(), "\"24,20\"")
    }
}

private fun TestContext.zone() = java.time.ZoneId.of("Europe/Vienna")
