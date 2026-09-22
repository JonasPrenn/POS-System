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
        val read = InvoiceReader.parse(invoiceText("RE-2026-1187", "12.09.2026"), listOf("Getränke Brandl GmbH"), today = LocalDate.of(2026, 9, 22))
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

    /** Das Layout einer Brauereirechnung, wie PDFBox es liefert: Artikelnummer voran, Menge vor den Beträgen, netto, Umbrüche, Lieferaufstellung. Kunde anonymisiert. */
    private val breweryText = """
        Musterverein Kundenkennzeichen: 110211
        zH Max Muster Rechnungsdatum: 10.12.2025
        Musterstraße 1 Rechnungsnummer: 25879
        6800 Feldkirch Kommissionsnummer: 3101
        RECHNUNG KOMMISSION Vereinsfest
        5.+6.12. 25879
        Liefertermine (Lieferdatum / Auftragsnummer / Handscheinnummer / Kundenbestellnummer)
        (04.12.2025/128751/X/X), (09.12.2025/128752/X/X)
        Artikel Bezeichnung Menge gratis Preis Rabatt RabVK Bierst. VK EP Gesamt
        10020 gold spezial Fass (20 Liter) 6 56,20 -20% 44,96 4,80 49,76 298,56
        10030 gold spezial Fass (30 Liter) 4 83,90 -20% 67,12 7,20 74,32 297,28
        50850 VOÜS soda PEM Container (20 4 19,00 -30% 13,30 13,30 53,20
        Liter)
        51012 VOÜS cola Kiste (12 Flaschen à 5 28,00 -20% 22,40 22,40 112,00
        1 Liter)
        51016 VOÜS tafelwasser prickelnd Kiste 5 12,80 -30% 8,96 8,96 44,80
        (12 Flaschen à 1 Liter)
        60500 kohlensäure (grau) Stück (1 1 5,30 5,30 5,30 5,30
        Kilogramm)
        70403 Zustellzone 1 2 64,17 64,17 64,17 128,34
        70502 leih - Kühlgerät 1-Hahn TK 1 33,30 33,30 33,30 33,30
        70530 leih - Glaskorb event 30x0,3 l 4 5,60 5,60 5,60 22,40
        70535 leih - glaskorb Festkrug 15x0,50 l 5 2,80 2,80 2,80 14,00
        70819 Vlies EW (400cm x 80cm) Stück 0 2 0,00 0,00 0 0,00
        (1 Stücke)
        801.3 kg G E S A M T A N Z A H L : 39 Warenwert ohne Abg. 951,58
        Biersteuer 57,60
        Warenwert inkl. Abgabe 1.009,18
        Gebinde Gel. Ret. Diff. Nettopreis Nettobetrag
        Fass 20 l (90004) 6 5 1 30,0000 30,00
        Fass 30 l (90005) 4 3 1 30,0000 30,00
        Kiste mit Flaschen 12x1,0 l (VOÜS) (90103) 10 2 8 5,4000 43,20
        Container 20 l (VOÜS) (90232) 4 1 3 30,0000 90,00
        Gebindewert 193,20
        Summen Netto Satz USt. Brutto
        Ware 1.009,18 20,00% 201,84 1.211,02
        Rechnung: 25879 vom 10.12.2025, Kundenkennzeichen: 110211, Seite 1 von 3
        ECHTE BIERKULTUR, DIE VERBINDET.
        Brauerei Frastanz eGen, Bahnhofstr. 22, A-6820 Frastanz, T +43 5522 51 701 - 0, bier@frastanzer.at
        UID Nr. ATU 36502600, Firmenbuch FN 62771k, Firmenbuchgericht Feldkirch
        Raiffeisenbank im Walgau, AT30 3745 8000 0111 0063, RVVGAT2B458
        Gebindesaldo (Pfandberechnung) 193,20 20,00% 38,64 231,84
        ENDBETRAG (alle Beträge in €) 1.202,38 240,48 1.442,86
        (Überweisung)
        Artikelklasse (inkl. Abgaben) exkl. USt USt.-Satz
        001 Bier 595,84 20,00
        002 Alkoholfrei 210,00 20,00
        006 Leihinventar 69,70 20,00
        Zahlungsbedingungen
        Zahlbar ohne Abzüge bis zum 15.12.2025
        Lieferaufstellung
        Artikel Bezeichnung Menge gratis Preis Rabatt RabVK Bierst. VK EP Gesamt
        Geliefert an: 161260 Musterverein, Musterstraße 1, 6800 Feldkirch
        10020 gold spezial Fass (20 Liter) 6 56,20 -20% 44,96 4,80 49,76 298,56
        10030 gold spezial Fass (30 Liter) 6 83,90 -20% 67,12 7,20 74,32 445,92
        90004 Fass 20 l (90004) 6 30,00 30,00 30,00 180,00
        10030 gold spezial Fass (30 Liter) -2 83,90 -20% 67,12 7,20 74,32 -148,64
        90005 Fass 30 l (90005) -2 30,00 30,00 30,00 -60,00
    """.trimIndent()

    @Test
    fun `a brewery invoice with article numbers net prices wrapped lines and a deposit balance is read completely`() {
        val read = InvoiceReader.parse(breweryText, emptyList(), ownName = "Musterverein Feldkirch", today = LocalDate.of(2026, 9, 22))
        assertEquals("Brauerei Frastanz eGen", read.supplier, "die Brauerei steht in der Fußzeile, der Verein oben ist der Empfänger")
        assertEquals("25879", read.number)
        assertEquals(LocalDate.of(2025, 12, 10), read.date)
        assertEquals(LocalDate.of(2025, 12, 15), read.dueDate, "„Zahlbar ohne Abzüge bis zum“")
        assertEquals(1442.86, assertNotNull(read.gross), 0.001, "der letzte Betrag der ENDBETRAG-Zeile, nicht der erste")
        assertEquals(240.48, assertNotNull(read.vat), 0.001)
        assertEquals(20.0, read.vatRate)
        assertTrue(read.linesAreNet)
        // Nur der erste Block: die Lieferaufstellung wiederholt alles, die Artikelklassen sind keine Positionen, der Nuller fällt weg.
        // Dazu die vier Gebinde aus der Pfandtabelle; der Gebindesaldo darunter ist dann keine eigene Zeile mehr.
        assertEquals(14, read.lines.size)
        val items = read.lines.filter { it.kind == InvoiceReader.Kind.ITEM }
        assertEquals(listOf("10020", "10030", "50850", "51012", "51016", "60500", "70403", "70502", "70530", "70535"), items.map { it.article })
        val pfand = read.lines.filter { it.kind == InvoiceReader.Kind.DEPOSIT }
        assertEquals(listOf("Fass 20 l", "Fass 30 l", "Kiste mit Flaschen 12x1,0 l (VOÜS)", "Container 20 l (VOÜS)"), pfand.map { it.description })
        assertEquals(listOf(6 to 5, 4 to 3, 10 to 2, 4 to 1), pfand.map { it.delivered to it.returned })
        assertEquals(listOf("90004", "90005", "90103", "90232"), pfand.map { it.article })
        assertEquals(36.0, assertNotNull(pfand[0].unitPrice), 0.001, "30,00 netto Pfand je Fass, brutto")
        assertEquals(231.84, pfand.sumOf { it.total }, 0.02, "der Gebindesaldo, aus den Zeilen")
        assertTrue(read.lines.none { it.kind == InvoiceReader.Kind.EXTRA })
        assertEquals("VOÜS soda PEM Container (20 Liter)", items[2].description, "der Umbruch ist wieder zusammengesetzt")
        assertEquals("VOÜS tafelwasser prickelnd Kiste (12 Flaschen à 1 Liter)", items[4].description)
        assertEquals("leih - glaskorb Festkrug 15x0,50 l", items[9].description, "„0,50“ hinter dem x ist kein Betrag")
        assertEquals(listOf(6.0, 4.0, 4.0, 5.0, 5.0, 1.0, 2.0, 1.0, 4.0, 5.0), items.map { it.quantity })
        assertEquals(298.56, assertNotNull(read.lines[0].net), 0.001); assertEquals(358.27, read.lines[0].total, 0.001)
        assertEquals(1442.86, read.lines.sumOf { it.total }, 0.02, "brutto hochgerechnet ergeben die Zeilen den Endbetrag")
        assertEquals("10020 gold spezial fass 20 liter", read.lines[0].key, "die Artikelnummer ist der Schlüssel fürs Gedächtnis")
        // Ein bekannter Lieferant gewinnt, auch anders geschrieben.
        assertEquals("Brauerei Frastanz", InvoiceReader.parse(breweryText, listOf("Brauerei Frastanz"), "Musterverein").supplier)
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

        // Bestätigen: Fass wie vorgeschlagen, die Kiste sind 20 Flaschen, das Pfand wird ein neues Gebinde.
        val applied = kassier.submitForm("/verwaltung/einkauf/$doc/positionen", parameters {
            append("_csrf", csrf); append("n", "3")
            append("key_0", "fass mohrenbräu helles 50 l"); append("orig_0", "2,00"); append("text_0", "Fass Mohrenbräu Helles 50 l"); append("wahl_0", "item:$bier:$keg"); append("menge_0", "2"); append("betrag_0", "284,00")
            append("key_1", "kiste almdudler 0 5 l 20x"); append("orig_1", "5,00"); append("text_1", "Kiste Almdudler 0,5 l 20x"); append("wahl_1", "item:$limo"); append("menge_1", "100"); append("betrag_1", "80,00")
            append("key_2", "fasspfand"); append("orig_2", "5,00"); append("text_2", "Fasspfand"); append("wahl_2", "pfand:neu"); append("menge_2", "5"); append("betrag_2", "150,00")
        })
        assertContains(location(applied), "3%20Positionen")
        val after = kassier.page("/verwaltung/einkauf?b=$doc")
        assertContains(after, "Alles zugeordnet", message = "284 + 80 + 150 = 514")
        assertContains(after, "Pfand und Leergut"); assertContains(after, "Pfand zu diesem Beleg")
        // Das Pfandgebinde ist da: 5 Fässer beim Lieferanten zu 30 € — und drei gehen ohne Beleg zurück.
        val lager = kassier.page("/verwaltung/lager")
        assertContains(lager, "Fasspfand"); assertContains(lager, "150,00 €")
        val kindId = assertNotNull(Regex("""<option value="([0-9a-f-]{36})">Fasspfand""").find(lager)).groupValues[1]
        kassier.submitForm("/verwaltung/lager/pfand", parameters { append("_csrf", csrfOf(lager)); append("gebinde", kindId); append("zurueck", "3"); append("geliefert", ""); append("tag", "2026-09-21"); append("notiz", "mit dem Fahrer") })
        val lagerAfter = kassier.page("/verwaltung/lager")
        assertContains(lagerAfter, "60,00 €", message = "2 Fässer × 30 €")
        assertContains(kassier.page("/verwaltung/buecher"), "Pfand beim Lieferanten")
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
        assertContains(again, "value=\"pfand:$kindId\" selected", message = "das Pfand liegt gemerkt auf dem Gebinde")
        assertTrue(Regex("""name="menge_1" value="100"""").containsMatchIn(again), "5 Kisten × 20 = 100 Flaschen, gemerkt")

        // Ein Foto liest niemand: Der Beleg wird gespeichert, die Seite sagt es — auch bei einer Datei, die größer ist als ein Formularfeld (früher ein 500).
        val photoBytes = ByteArray(300 * 1024).also { it[0] = 0x89.toByte(); it[1] = 0x50; it[2] = 0x4E; it[3] = 0x47 }
        val photo = kassier.submitFormWithBinaryData("/verwaltung/einkauf/beleg", formData {
            append("_csrf", csrf); append("lieferant", "Metzgerei Huber"); append("nummer", "77"); append("datum", "2026-09-20"); append("faellig", ""); append("brutto", "40"); append("ust", ""); append("zahlung", "CASH"); append("notiz", "")
            append("datei", photoBytes, Headers.build { append(HttpHeaders.ContentType, "image/png"); append(HttpHeaders.ContentDisposition, "filename=\"bon.png\"") })
        })
        assertEquals(HttpStatusCode.Found, photo.status, "300 KB Foto: kein 500")
        assertContains(location(photo), "Beleg%20gespeichert.")
    }
}
