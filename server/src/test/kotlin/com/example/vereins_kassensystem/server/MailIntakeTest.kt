package com.example.vereins_kassensystem.server

import com.example.vereins_kassensystem.server.db.query
import com.example.vereins_kassensystem.server.web.Accounts
import com.example.vereins_kassensystem.server.web.IncomingMail
import com.example.vereins_kassensystem.server.web.MailAttachment
import com.example.vereins_kassensystem.server.web.Role
import com.example.vereins_kassensystem.sync.ChangesResponse
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/** Rechnungen per E-Mail (Konzept 4.4): unbekannte Absender warten, freigegebene werden gleich ein Beleg — samt gemerkten Positionen. */
class MailIntakeTest {

    private val password = "ein-langes-passwort"
    private fun ApplicationTestBuilder.browser(): HttpClient = createClient { install(HttpCookies); followRedirects = false }
    private fun csrfOf(html: String): String = assertNotNull(Regex("""name="_csrf" value="([^"]+)"""").find(html)).groupValues[1]
    private suspend fun HttpClient.page(path: String): String { val r = get(path); assertEquals(HttpStatusCode.OK, r.status, path); return r.bodyAsText() }
    private fun location(r: HttpResponse): String = assertNotNull(r.headers[HttpHeaders.Location])

    private fun pdfOf(text: String): ByteArray {
        val html = "<html><body style=\"font-family: Helvetica\">" + text.lines().joinToString("") { "<p>${it.replace("&", "&amp;").replace("<", "&lt;")}</p>" } + "</body></html>"
        val out = ByteArrayOutputStream()
        PdfRendererBuilder().useFastMode().withHtmlContent(html, null).toStream(out).run()
        return out.toByteArray()
    }

    private fun invoice(number: String, date: String) = """
        Getränke Brandl GmbH
        Rechnung Nr. $number
        Rechnungsdatum: $date
        2 Fass Mohrenbräu Helles 50 l 142,00 284,00
        5 Kiste Almdudler 0,5 l 20x 16,00 80,00
        Gesamtbetrag 364,00 EUR
        Zahlbar innerhalb 14 Tagen.
    """.trimIndent()

    private fun mail(id: String, from: String, subject: String, pdf: ByteArray?, at: Instant = Instant.now()) =
        IncomingMail(id, from, subject, at, listOfNotNull(pdf?.let { MailAttachment("rechnung.pdf", "application/pdf", it) }))

    @Test
    fun `mails wait in the inbox until a sender is trusted and then become documents with the learned lines booked`() = serverTest(insecureCookies = true) { ctx ->
        val device = ctx.pairDevice("Theke links")
        val bier = newId(); val keg = newId(); val limo = newId()
        ctx.push(
            device.token,
            insertOp("stock_items", buildJsonObject { put("id", bier); put("name", "Helles"); put("unit", "l"); put("tracking", "CONTAINER"); put("min_level", 20.0) }),
            insertOp("container_types", buildJsonObject { put("id", keg); put("stock_item_id", bier); put("label", "50 l Fass"); put("nominal_size", 50.0); put("initial_yield_estimate", 47.0) }),
            insertOp("stock_items", buildJsonObject { put("id", limo); put("name", "Almdudler"); put("unit", "Fl"); put("tracking", "SIMPLE"); put("min_level", 40.0) }),
        )
        val since = ctx.client.get("/v1/sync/changes?since=0") { bearerAuth(device.token) }.body<ChangesResponse>().nextSince
        Accounts(ctx.db).create("lukas", "Lukas Hofer", Role.KASSIER, password)
        val kassier = browser()
        kassier.submitForm("/verwaltung/anmelden", parameters { append("login", "lukas"); append("passwort", password) })

        // Ohne Postfach in den Einstellungen sagt der Abruf das — und bucht nichts.
        var page = kassier.page("/verwaltung/einkauf?post=1")
        val refused = kassier.submitForm("/verwaltung/einkauf/post/abrufen", parameters { append("_csrf", csrfOf(page)) })
        assertContains(location(refused), "fehler=", message = "kein Postfach eingerichtet")
        kassier.submitForm("/verwaltung/einstellungen", parameters { append("_csrf", csrfOf(page)); append("teil", "imap"); append("host", "imap.example.at"); append("port", "993"); append("benutzer", "kassier@example.at"); append("passwort", "geheim"); append("ordner", "INBOX"); append("aktiv", "1") })

        // Drei Mails: eine Rechnung von einem unbekannten Absender, eine ohne PDF, eine Werbung mit PDF.
        ctx.mailbox.pending += mail("<a1@brandl>", "rechnung@brandl.at", "Rechnung RE-2026-1187", pdfOf(invoice("RE-2026-1187", "12.09.2026")))
        ctx.mailbox.pending += mail("<a2@brandl>", "rechnung@brandl.at", "Lieferavis", null)
        ctx.mailbox.pending += mail("<a3@spam>", "news@irgendwer.example", "Angebot der Woche", pdfOf("Sonderangebot Grillfleisch\n1 kg 9,90 9,90\nGesamt 9,90"))
        val polled = kassier.submitForm("/verwaltung/einkauf/post/abrufen", parameters { append("_csrf", csrfOf(page)) })
        assertContains(location(polled), "hinweis=")
        page = kassier.page("/verwaltung/einkauf?post=1")
        assertContains(page, "Posteingang"); assertContains(page, "Rechnung RE-2026-1187"); assertContains(page, "kein PDF"); assertContains(page, "2 Mails warten")
        assertFalse(page.contains("RE-2026-1187 · "), "ohne Freigabe entsteht kein Beleg")
        val waiting = ctx.db.read { c -> c.query("SELECT id, sender FROM mail_intake WHERE status = 'NEW'") { it.getObject("id", UUID::class.java) to it.getString("sender") } }
        val brandl = waiting.first { it.second == "rechnung@brandl.at" }.first
        val spam = waiting.first { it.second != "rechnung@brandl.at" }.first

        // Die Werbung ablehnen, die Rechnung übernehmen und den Absender freigeben.
        kassier.submitForm("/verwaltung/einkauf/post/$spam", parameters { append("_csrf", csrfOf(page)); append("aktion", "ablehnen") })
        val accepted = kassier.submitForm("/verwaltung/einkauf/post/$brandl", parameters { append("_csrf", csrfOf(page)); append("aktion", "uebernehmen"); append("freigeben", "1") })
        val doc = assertNotNull(Regex("b=([0-9a-f-]{36})").find(location(accepted)), location(accepted)).groupValues[1]
        val detail = kassier.page("/verwaltung/einkauf?b=$doc&lesen=1")
        assertContains(detail, "Getränke Brandl GmbH"); assertContains(detail, "RE-2026-1187"); assertContains(detail, "364,00")
        assertContains(detail, "per E-Mail von rechnung@brandl.at")
        assertContains(detail, "Aus der Rechnung gelesen", message = "ohne Gedächtnis wartet alles auf den Kassier")
        // Der Kassier bestätigt einmal: Fass → Helles 50 l, Kiste → 20 Almdudler.
        kassier.submitForm("/verwaltung/einkauf/$doc/positionen", parameters {
            append("_csrf", csrfOf(detail)); append("n", "2")
            append("key_0", "fass mohrenbräu helles 50 l"); append("orig_0", "2,00"); append("text_0", "Fass Mohrenbräu Helles 50 l"); append("wahl_0", "item:$bier:$keg"); append("menge_0", "2"); append("betrag_0", "284,00")
            append("key_1", "kiste almdudler 0 5 l 20x"); append("orig_1", "5,00"); append("text_1", "Kiste Almdudler 0,5 l 20x"); append("wahl_1", "item:$limo"); append("menge_1", "100"); append("betrag_1", "80,00")
        })
        assertContains(kassier.page("/verwaltung/einkauf?post=1"), "abgelehnt")

        // Die nächste Rechnung desselben Absenders: wird beim Abruf von selbst ein Beleg, beide Zeilen gebucht, nichts wartet.
        ctx.mailbox.pending += mail("<b1@brandl>", "rechnung@brandl.at", "Rechnung RE-2026-1250", pdfOf(invoice("RE-2026-1250", "19.09.2026")))
        ctx.mailbox.pending += mail("<a1@brandl>", "rechnung@brandl.at", "Rechnung RE-2026-1187", pdfOf(invoice("RE-2026-1187", "12.09.2026"))) // dieselbe Message-ID noch einmal
        kassier.submitForm("/verwaltung/einkauf/post/abrufen", parameters { append("_csrf", csrfOf(page)) })
        page = kassier.page("/verwaltung/einkauf?post=1")
        assertContains(page, "RE-2026-1250")
        assertContains(page, "2 von 2 Positionen automatisch gebucht")
        assertFalse(page.contains("Mails warten"), "nichts wartet mehr")
        val pulled = ctx.client.get("/v1/sync/changes?since=$since") { bearerAuth(device.token) }.body<ChangesResponse>()
        assertEquals(4, pulled.changes.count { it.entity == "stock_entries" }, "zwei Belege, je Fass und Kisten — nichts doppelt")
        assertContains(kassier.page("/verwaltung/protokoll"), "Posteingang (automatisch)")

        // Ein zweites Lesen der Datei schlägt die gebuchten Zeilen nicht noch einmal vor.
        val second = ctx.db.read { c -> c.query("SELECT document_id FROM mail_intake WHERE status = 'DONE' AND document_id IS NOT NULL") { it.getObject("document_id", UUID::class.java).toString() } }.first { it != doc }
        val reread = kassier.page("/verwaltung/einkauf?b=$second&lesen=1")
        assertContains(reread, "2 Zeilen sind schon übernommen")
        assertFalse(reread.contains("name=\"wahl_0\""))

        // Eine Rechnung mit derselben Nummer noch einmal per Mail: kein zweiter Beleg, der Eintrag sagt warum.
        ctx.mailbox.pending += mail("<c1@brandl>", "rechnung@brandl.at", "Rechnung RE-2026-1250 (Kopie)", pdfOf(invoice("RE-2026-1250", "19.09.2026")))
        kassier.submitForm("/verwaltung/einkauf/post/abrufen", parameters { append("_csrf", csrfOf(page)) })
        page = kassier.page("/verwaltung/einkauf?post=1")
        assertContains(page, "nicht angelegt"); assertContains(page, "Dublette")

        // Absender sperren: seine Mails warten wieder.
        kassier.submitForm("/verwaltung/einkauf/post/${UUID(0, 0)}", parameters { append("_csrf", csrfOf(page)); append("aktion", "sperren"); append("absender", "rechnung@brandl.at") })
        ctx.mailbox.pending += mail("<d1@brandl>", "rechnung@brandl.at", "Rechnung RE-2026-1300", pdfOf(invoice("RE-2026-1300", "20.09.2026")))
        kassier.submitForm("/verwaltung/einkauf/post/abrufen", parameters { append("_csrf", csrfOf(page)) })
        assertContains(kassier.page("/verwaltung/einkauf?post=1"), "1 Mail wartet")

        // Ein Postfach, das nicht antwortet: der Fehler steht auf der Seite, nichts geht verloren.
        ctx.mailbox.failWith = "Connection refused"
        val failed = kassier.submitForm("/verwaltung/einkauf/post/abrufen", parameters { append("_csrf", csrfOf(page)) })
        assertContains(location(failed), "Connection")
        assertContains(kassier.page("/verwaltung/einstellungen"), "Connection refused")
    }
}
