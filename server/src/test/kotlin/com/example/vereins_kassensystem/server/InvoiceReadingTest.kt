package com.example.vereins_kassensystem.server

import com.example.vereins_kassensystem.server.web.Accounts
import com.example.vereins_kassensystem.server.web.InvoiceReader
import com.example.vereins_kassensystem.server.web.Role
import com.example.vereins_kassensystem.sync.ChangesResponse
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Eine Eingangsrechnung als PDF: gelesen, vorgeschlagen, bestätigt — und beim nächsten Beleg gemerkt (Konzept 4.4). */
class InvoiceReadingTest {

    private val password = "ein-langes-passwort"
    private fun ApplicationTestBuilder.browser(): HttpClient = createClient { install(HttpCookies); followRedirects = false }
    private fun csrfOf(html: String): String = assertNotNull(Regex("""name="_csrf" value="([^"]+)"""").find(html)).groupValues[1]
    private suspend fun HttpClient.page(path: String): String { val r = get(path); assertEquals(HttpStatusCode.OK, r.status, path); return r.bodyAsText() }
    private fun location(r: HttpResponse): String = assertNotNull(r.headers[HttpHeaders.Location])

    /** So sieht die Rechnung eines Getränkehändlers aus, als Text — genau das, was PDFBox aus dem PDF holt. */
    private fun invoiceText(number: String, date: String) = """
        Getränke Brandl GmbH
        Bahnhofstraße 12, 6800 Feldkirch
        Rechnung Nr. $number
        Rechnungsdatum: $date
        Kundennummer: 4711
        Menge Artikel Einzelpreis Gesamt
        2 Fass Mohrenbräu Helles 50 l 142,00 284,00
        5 Kiste Almdudler 0,5 l 20x 16,00 80,00
        5 Fasspfand 30,00 150,00
        Netto 428,33
        MwSt 20 % 85,67
        Gesamtbetrag 514,00 EUR
        Zahlbar bis 26.09.2026 ohne Abzug.
    """.trimIndent()

    private fun pdfOf(text: String): ByteArray {
        val html = "<html><body style=\"font-family: Helvetica\">" + text.lines().joinToString("") { "<p>${it.replace("&", "&amp;").replace("<", "&lt;")}</p>" } + "</body></html>"
        val out = ByteArrayOutputStream()
        PdfRendererBuilder().useFastMode().withHtmlContent(html, null).toStream(out).run()
        return out.toByteArray()
    }

    @Test
    fun `the reader finds head and lines in the text of a supplier invoice`() {
        val read = InvoiceReader.parse(invoiceText("RE-2026-1187", "12.09.2026"), listOf("Getränke Brandl GmbH"), LocalDate.of(2026, 9, 22))
        assertEquals("Getränke Brandl GmbH", read.supplier)
        assertEquals("RE-2026-1187", read.number)
        assertEquals(LocalDate.of(2026, 9, 12), read.date)
        assertEquals(LocalDate.of(2026, 9, 26), read.dueDate)
        assertEquals(514.0, assertNotNull(read.gross), 0.001)
        assertEquals(85.67, assertNotNull(read.vat), 0.001)
        assertEquals(listOf("Fass Mohrenbräu Helles 50 l", "Kiste Almdudler 0,5 l 20x", "Fasspfand"), read.lines.map { it.description })
        assertEquals(listOf(2.0, 5.0, 5.0), read.lines.map { it.quantity })
        assertEquals(listOf(284.0, 80.0, 150.0), read.lines.map { it.total })
        assertEquals(142.0, read.lines[0].unitPrice)
        // Ohne bekannten Lieferanten: die erste brauchbare Zeile ist der Absender.
        assertEquals("Getränke Brandl GmbH", InvoiceReader.parse(invoiceText("1", "12.09.2026"), emptyList()).supplier)
        // Ein Scan ohne Textebene ergibt nichts, und sagt es.
        assertEquals(false, InvoiceReader.parse("", emptyList()).hasText)
        assertNull(InvoiceReader.read("%PDF-1.4 kein echtes PDF".toByteArray(), emptyList()).supplier)
    }

    @Test
    fun `an uploaded invoice fills the head and its lines become stock once confirmed and by memory the next time`() = serverTest(insecureCookies = true) { ctx ->
        val device = ctx.pairDevice("Theke links")
        val bier = newId(); val keg = newId(); val small = newId(); val limo = newId()
        ctx.push(
            device.token,
            insertOp("stock_items", buildJsonObject { put("id", bier); put("name", "Helles"); put("unit", "l"); put("tracking", "CONTAINER"); put("min_level", 20.0) }),
            insertOp("container_types", buildJsonObject { put("id", small); put("stock_item_id", bier); put("label", "30 l Fass"); put("nominal_size", 30.0); put("initial_yield_estimate", 28.0) }),
            insertOp("container_types", buildJsonObject { put("id", keg); put("stock_item_id", bier); put("label", "50 l Fass"); put("nominal_size", 50.0); put("initial_yield_estimate", 47.0) }),
            insertOp("stock_items", buildJsonObject { put("id", limo); put("name", "Almdudler"); put("unit", "Fl"); put("tracking", "SIMPLE"); put("min_level", 40.0) }),
        )
        val since = ctx.client.get("/v1/sync/changes?since=0") { bearerAuth(device.token) }.body<ChangesResponse>().nextSince
        Accounts(ctx.db).create("lukas", "Lukas Hofer", Role.KASSIER, password)
        val kassier = browser()
        kassier.submitForm("/verwaltung/anmelden", parameters { append("login", "lukas"); append("passwort", password) })
        val form = kassier.page("/verwaltung/einkauf?neu=1")
        val csrf = csrfOf(form)

        // Nur die Datei, sonst nichts: Kopfdaten kommen aus dem PDF.
        val saved = kassier.submitFormWithBinaryData("/verwaltung/einkauf/beleg", formData {
            append("_csrf", csrf); append("lieferant", ""); append("nummer", ""); append("datum", ""); append("faellig", ""); append("brutto", ""); append("ust", ""); append("zahlung", "OPEN"); append("notiz", "")
            append("datei", pdfOf(invoiceText("RE-2026-1187", "12.09.2026")), Headers.build { append(HttpHeaders.ContentType, "application/pdf"); append(HttpHeaders.ContentDisposition, "filename=\"rechnung.pdf\"") })
        })
        val target = location(saved)
        val doc = assertNotNull(Regex("b=([0-9a-f-]{36})").find(target), target).groupValues[1]
        assertContains(target, "3%20Positionen", message = "der Hinweis nennt die erkannten Positionen")

        val detail = kassier.page("/verwaltung/einkauf?b=$doc&lesen=1")
        assertContains(detail, "Getränke Brandl GmbH"); assertContains(detail, "RE-2026-1187"); assertContains(detail, "514,00")
        assertContains(detail, "Aus der Rechnung gelesen"); assertContains(detail, "Fasspfand")
        // Der Vorschlag: „Helles 50 l“ in der Zeile findet den Artikel und das 50-l-Gebinde, nicht das 30-l.
        assertContains(detail, "value=\"item:$bier:$keg\" selected")
        assertContains(detail, "value=\"item:$limo\" selected", message = "„Almdudler“ steckt in der Zeile")

        // Bestätigen: Fass wie vorgeschlagen, die Kiste sind 20 Flaschen, das Pfand ist eine Zeile mit Konto.
        val pfand = "00000000-0000-0000-0001-000000000018"
        val applied = kassier.submitForm("/verwaltung/einkauf/$doc/positionen", parameters {
            append("_csrf", csrf); append("n", "3")
            append("key_0", "fass mohrenbräu helles 50 l"); append("orig_0", "2,00"); append("text_0", "Fass Mohrenbräu Helles 50 l"); append("wahl_0", "item:$bier:$keg"); append("menge_0", "2"); append("betrag_0", "284,00")
            append("key_1", "kiste almdudler 0 5 l 20x"); append("orig_1", "5,00"); append("text_1", "Kiste Almdudler 0,5 l 20x"); append("wahl_1", "item:$limo"); append("menge_1", "100"); append("betrag_1", "80,00")
            append("key_2", "fasspfand"); append("orig_2", "5,00"); append("text_2", "Fasspfand"); append("wahl_2", "acct:$pfand"); append("menge_2", ""); append("betrag_2", "150,00")
        })
        assertContains(location(applied), "3%20Positionen")
        val after = kassier.page("/verwaltung/einkauf?b=$doc")
        assertContains(after, "Alles zugeordnet", message = "284 + 80 + 150 = 514")
        assertContains(after, "Sonstiges")
        val pulled = ctx.client.get("/v1/sync/changes?since=$since") { bearerAuth(device.token) }.body<ChangesResponse>()
        val entries = pulled.changes.filter { it.entity == "stock_entries" }
        assertEquals(setOf("2.0" to keg, "100.0" to null), entries.map { it.row["quantity"]!!.jsonPrimitive.content to it.row["container_type_id"]?.let { c -> if (c is kotlinx.serialization.json.JsonNull) null else c.jsonPrimitive.content } }.toSet())

        // Der nächste Beleg desselben Lieferanten: dieselben Zeilen liegen von selbst richtig, mit 20 Flaschen je Kiste.
        val next = kassier.submitFormWithBinaryData("/verwaltung/einkauf/beleg", formData {
            append("_csrf", csrf); append("lieferant", ""); append("nummer", ""); append("datum", ""); append("faellig", ""); append("brutto", ""); append("ust", ""); append("zahlung", "OPEN"); append("notiz", "")
            append("datei", pdfOf(invoiceText("RE-2026-1203", "19.09.2026")), Headers.build { append(HttpHeaders.ContentType, "application/pdf"); append(HttpHeaders.ContentDisposition, "filename=\"rechnung2.pdf\"") })
        })
        val second = assertNotNull(Regex("b=([0-9a-f-]{36})").find(location(next))).groupValues[1]
        val again = kassier.page("/verwaltung/einkauf?b=$second&lesen=1")
        assertContains(again, "gemerkt vom letzten Beleg")
        assertContains(again, "value=\"acct:$pfand\" selected")
        assertTrue(Regex("""name="menge_1" value="100"""").containsMatchIn(again), "5 Kisten × 20 = 100 Flaschen, gemerkt")

        // Ein Foto liest niemand: Der Beleg wird gespeichert, die Seite sagt es.
        val photo = kassier.submitFormWithBinaryData("/verwaltung/einkauf/beleg", formData {
            append("_csrf", csrf); append("lieferant", "Metzgerei Huber"); append("nummer", "77"); append("datum", "2026-09-20"); append("faellig", ""); append("brutto", "40"); append("ust", ""); append("zahlung", "CASH"); append("notiz", "")
            append("datei", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), Headers.build { append(HttpHeaders.ContentType, "image/png"); append(HttpHeaders.ContentDisposition, "filename=\"bon.png\"") })
        })
        assertContains(location(photo), "Beleg%20gespeichert.")
    }
}
