package com.example.vereins_kassensystem.server

import com.example.vereins_kassensystem.data.Ledger
import com.example.vereins_kassensystem.server.web.Accounts
import com.example.vereins_kassensystem.server.web.BankAccount
import com.example.vereins_kassensystem.server.web.BankImport
import com.example.vereins_kassensystem.server.web.Role
import com.example.vereins_kassensystem.server.web.StatementPdf
import com.example.vereins_kassensystem.server.web.VereinSettings
import com.example.vereins_kassensystem.sync.ChangesResponse
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
import io.ktor.client.statement.readRawBytes
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Die Abrechnung (Konzept 4.2): vom Deckel im Minus bis zur Zahlung, die auf das Tablet zurückkommt. */
class StatementsTest {

    private val password = "ein-langes-passwort"
    private val zone = ZoneId.of("Europe/Vienna")

    private fun ApplicationTestBuilder.browser(): HttpClient = createClient { install(HttpCookies); followRedirects = false }
    private suspend fun HttpClient.form(path: String, vararg fields: Pair<String, String>): HttpResponse = submitForm(path, parameters { fields.forEach { (k, v) -> append(k, v) } })
    private fun csrfOf(html: String): String = assertNotNull(Regex("""name="_csrf" value="([^"]+)"""").find(html)).groupValues[1]
    private suspend fun HttpClient.page(path: String): String {
        val response = get(path)
        assertEquals(HttpStatusCode.OK, response.status, "$path: ${response.headers[HttpHeaders.Location]}")
        return response.bodyAsText()
    }
    private fun location(r: HttpResponse) = assertNotNull(r.headers[HttpHeaders.Location])

    private fun at(day: LocalDate, hour: Int) = day.atTime(hour, 0).atZone(zone).toInstant().toString()

    @Test
    fun `iban check and the epc payload`() {
        assertTrue(VereinSettings.ibanValid("AT611904300234573201"))
        assertFalse(VereinSettings.ibanValid("AT611904300234573202"))
        assertFalse(VereinSettings.ibanValid("DE00"))
        val payload = StatementPdf.epcPayload(BankAccount("K.Ö.St.V. Beispiel", "AT611904300234573201", ""), 98.0, "VD-202608-0003")
        assertEquals(listOf("BCD", "002", "1", "SCT", "", "K.Ö.St.V. Beispiel", "AT611904300234573201", "EUR98.00", "", "VD-202608-0003", "", ""), payload.split("\n"))
    }

    @Test
    fun `bank statements are read as camt and as csv`() {
        val camt = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02"><BkToCstmrStmt><Stmt>
              <Ntry><Amt Ccy="EUR">98.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><BookgDt><Dt>2026-09-04</Dt></BookgDt>
                <NtryDtls><TxDtls><RltdPties><Dbtr><Nm>Georg Lechner</Nm></Dbtr></RltdPties><RmtInf><Ustrd>Bierrechnung VD-202608-0003</Ustrd></RmtInf></TxDtls></NtryDtls></Ntry>
              <Ntry><Amt Ccy="EUR">212.80</Amt><CdtDbtInd>DBIT</CdtDbtInd><BookgDt><Dt>2026-09-05</Dt></BookgDt>
                <NtryDtls><TxDtls><RltdPties><Cdtr><Nm>Stadtwerke</Nm></Cdtr></RltdPties><RmtInf><Ustrd>Strom 2026-09</Ustrd></RmtInf></TxDtls></NtryDtls></Ntry>
            </Stmt></BkToCstmrStmt></Document>
        """.trimIndent()
        val rows = BankImport.parse(camt.toByteArray(), "auszug.xml")
        assertEquals(2, rows.size)
        assertEquals(98.0, rows[0].amount); assertEquals("Georg Lechner", rows[0].counterparty); assertContains(rows[0].reference, "VD-202608-0003")
        assertEquals(-212.8, rows[1].amount); assertEquals(LocalDate.of(2026, 9, 5), rows[1].date)

        val csv = "Buchungsdatum;Betrag;Währung;Auftraggeber/Empfänger;Verwendungszweck\n04.09.2026;\"1.098,00\";EUR;\"Lechner, Georg\";Bierrechnung VD-202608-0003\n05.09.2026;-212,80;EUR;Stadtwerke;Strom\n"
        val fromCsv = BankImport.parse(csv.toByteArray(Charsets.ISO_8859_1), "umsaetze.csv")
        assertEquals(2, fromCsv.size)
        assertEquals(1098.0, fromCsv[0].amount); assertEquals("Lechner, Georg", fromCsv[0].counterparty)
        assertEquals(fromCsv[0].fingerprint, BankImport.parse(csv.toByteArray(), "x.csv")[0].fingerprint, "derselbe Umsatz, derselbe Fingerabdruck")
    }

    @Test
    fun `a run bills who is below the threshold and the payment comes back to the till`() = serverTest(insecureCookies = true) { ctx ->
        val device = ctx.pairDevice("Theke links")
        val ah = newId(); val georg = newId(); val lukas = newId(); val helles = newId()
        val july = LocalDate.of(2026, 7, 20); val aug = LocalDate.of(2026, 8, 12)
        fun sale(member: String, name: String, price: String, qty: Int, day: LocalDate, type: String = Ledger.MEMBER_BALANCE, ref: String = helles, product: String = "Helles 0,5") = insertOp("transactions", buildJsonObject {
            put("id", newId()); put("transaction_group_id", newId()); put("member_id", member); put("member_name", name); put("product_ref", ref); put("product_name", product)
            put("price", price); put("quantity", qty); put("payment_type", type); put("occurred_at", at(day, 20))
        })
        ctx.push(
            device.token,
            insertOp("member_categories", buildJsonObject { put("id", ah); put("name", "Alter Herr"); put("negative_balance_limit", "-150.00") }),
            insertOp("members", buildJsonObject { put("id", georg); put("name", "Dr. Georg Lechner"); put("nickname", "Nestor"); put("category_id", ah) }),
            insertOp("members", buildJsonObject { put("id", lukas); put("name", "Lukas Hofer"); put("category_id", ah) }),
            insertOp("products", buildJsonObject { put("id", helles); put("name", "Helles 0,5"); put("price", "4.20"); put("category", "Getränke") }),
            sale(georg, "Dr. Georg Lechner", "4.20", 10, july),                                   // −42,00 im Juli: der Anfangsstand
            sale(georg, "Dr. Georg Lechner", "4.20", 10, aug),                                    // −42,00 im August
            sale(georg, "Dr. Georg Lechner", "14.00", 1, aug, type = "CASH", ref = Ledger.TOPUP_REF, product = "Guthabenaufladung"), // +14,00 bar → −70,00
            sale(lukas, "Lukas Hofer", "20.00", 1, aug, type = "CASH", ref = Ledger.TOPUP_REF, product = "Guthabenaufladung"),       // +20,00: im Plus
        )
        val since = ctx.client.get("/v1/sync/changes?since=0") { bearerAuth(device.token) }.body<ChangesResponse>().nextSince

        val accounts = Accounts(ctx.db)
        accounts.create("lukas", "Lukas Hofer", Role.KASSIER, password)
        val kassier = browser()
        kassier.form("/verwaltung/anmelden", "login" to "lukas", "passwort" to password)
        val settings = kassier.page("/verwaltung/einstellungen")
        kassier.form("/verwaltung/einstellungen", "_csrf" to csrfOf(settings), "teil" to "bank", "inhaber" to "K.Ö.St.V. Beispiel", "iban" to "AT61 1904 3002 3457 3201", "bic" to "", "text" to "Fragen an den Kassier.")
        kassier.form("/verwaltung/einstellungen", "_csrf" to csrfOf(settings), "teil" to "smtp", "host" to "smtp.example.at", "port" to "587", "benutzer" to "kassier", "passwort" to "geheim", "absender" to "kassier@example.at", "starttls" to "1")
        assertContains(kassier.page("/verwaltung/einstellungen"), "Passwort (leer: bleibt)")
        // Georg bekommt ein Profil mit E-Mail und Einwilligung, Lukas nicht.
        val members = kassier.page("/verwaltung/mitglieder?m=$georg")
        kassier.form("/verwaltung/mitglieder/$georg/profil", "_csrf" to csrfOf(members), "nummer" to "0003", "email" to "g.lechner@example.at", "anschrift" to "Beispielgasse 1\n6800 Feldkirch", "einwilligung" to "1", "notizen" to "")

        // Vorschau und Lauf zum 31. August: Georg mit −70, Lukas nicht (im Plus).
        val page = kassier.page("/verwaltung/abrechnung?stichtag=2026-08-31&schwelle=-5")
        assertContains(page, "Dr. Georg Lechner v. Nestor")
        assertFalse(page.contains("Lukas Hofer</td>"))
        val created = kassier.form("/verwaltung/abrechnung/lauf", "_csrf" to csrfOf(page), "name" to "Bierrechnung August 2026", "von" to "2026-08-01", "stichtag" to "2026-08-31", "ziel" to "2026-09-14", "schwelle" to "-5,00", "zusatz" to "", "zusatzbetrag" to "", "alle" to "0")
        val run = assertNotNull(Regex("lauf=([0-9a-f-]{36})").find(location(created))).groupValues[1]
        val list = kassier.page("/verwaltung/abrechnung?lauf=$run")
        assertContains(list, "1 Abrechnung")
        val number = assertNotNull(Regex("VD-202608-\\d{4}").find(list)).value
        assertContains(list, "70,00 €")

        // Das PDF: eine Datei, die mit %PDF beginnt, und der Auszug rechnet vom Anfangsstand aus.
        val statementId = Regex("""/verwaltung/abrechnung/([0-9a-f-]{36})\.pdf""").find(list)!!.groupValues[1]
        val pdf = kassier.get("/verwaltung/abrechnung/$statementId.pdf")
        assertEquals(HttpStatusCode.OK, pdf.status)
        assertEquals("%PDF", String(pdf.readRawBytes().take(4).toByteArray()))

        // Versand: Georg per E-Mail (Anhang dabei), niemand sonst.
        kassier.form("/verwaltung/abrechnung/lauf/$run/senden", "_csrf" to csrfOf(list))
        assertEquals(1, ctx.outbox.sent.size)
        val mail = ctx.outbox.sent.single()
        assertEquals("g.lechner@example.at", mail.to)
        assertContains(mail.subject, number)
        assertContains(mail.text, "70,00 €"); assertContains(mail.text, "AT61 1904 3002 3457 3201"); assertContains(mail.text, number)
        assertEquals("$number.pdf", mail.attachmentName)
        assertContains(kassier.page("/verwaltung/abrechnung?lauf=$run"), "E-Mail ")
        assertContains(location(kassier.get("/verwaltung/abrechnung/lauf/$run.pdf")), "Nichts", message = "alle per E-Mail — die Druckmappe ist leer")

        // Der Kontoauszug der Bank: die Überweisung mit der Nummer im Text bezahlt die Abrechnung, die Belastung wird abgelegt.
        val camt = """<?xml version="1.0" encoding="UTF-8"?><Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.08"><BkToCstmrStmt><Stmt>
            <Ntry><Amt Ccy="EUR">70.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><BookgDt><Dt>2026-09-04</Dt></BookgDt><NtryDtls><TxDtls><RltdPties><Dbtr><Nm>Georg Lechner</Nm></Dbtr></RltdPties><RmtInf><Ustrd>Bierrechnung $number</Ustrd></RmtInf></TxDtls></NtryDtls></Ntry>
            <Ntry><Amt Ccy="EUR">25.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><BookgDt><Dt>2026-09-05</Dt></BookgDt><NtryDtls><TxDtls><RltdPties><Dbtr><Nm>Unbekannt</Nm></Dbtr></RltdPties><RmtInf><Ustrd>Spende</Ustrd></RmtInf></TxDtls></NtryDtls></Ntry>
            </Stmt></BkToCstmrStmt></Document>"""
        val imported = kassier.submitFormWithBinaryData("/verwaltung/abrechnung/bank", formData {
            append("_csrf", csrfOf(list)); append("datei", camt.toByteArray(), Headers.build { append(HttpHeaders.ContentType, "application/xml"); append(HttpHeaders.ContentDisposition, "filename=\"auszug.xml\"") })
        })
        assertContains(location(imported), "hinweis=")
        val after = kassier.page("/verwaltung/abrechnung?lauf=$run")
        assertContains(after, "bezahlt 04.09.")
        assertContains(after, "Spende", message = "die Gutschrift ohne Nummer wartet auf Zuordnung")
        // Ein zweites Einlesen bucht nichts doppelt.
        kassier.submitFormWithBinaryData("/verwaltung/abrechnung/bank", formData { append("_csrf", csrfOf(list)); append("datei", camt.toByteArray(), Headers.build { append(HttpHeaders.ContentType, "application/xml"); append(HttpHeaders.ContentDisposition, "filename=\"auszug.xml\"") }) })

        // Beim Tablet kommt die Zahlung als Aufladung an — genau einmal — und Georg steht auf 0.
        val pulled = ctx.client.get("/v1/sync/changes?since=$since") { bearerAuth(device.token) }.body<ChangesResponse>()
        val topUps = pulled.changes.filter { it.entity == "transactions" }
        assertEquals(1, topUps.size)
        assertEquals("70.00", topUps[0].row["price"]!!.jsonPrimitive.content)
        assertEquals("BANK", topUps[0].row["payment_type"]!!.jsonPrimitive.content)
        assertContains(topUps[0].row["note"]!!.jsonPrimitive.content, number)
        assertContains(kassier.page("/verwaltung/mitglieder?m=$georg"), "0,00 €")
        assertContains(kassier.page("/verwaltung/protokoll"), "Zahlung eingegangen")
    }
}
