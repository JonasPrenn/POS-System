package com.example.vereins_kassensystem.server

import com.example.vereins_kassensystem.data.Ledger
import com.example.vereins_kassensystem.server.db.execute
import com.example.vereins_kassensystem.server.web.Accounts
import com.example.vereins_kassensystem.server.web.Role
import com.example.vereins_kassensystem.server.web.euro
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Die Bücher (Konzept 4.6): Einnahmen-Ausgaben-Rechnung und Vermögensübersicht aus Buchungen und Belegen. */
class BooksTest {

    @Test
    fun `the year's books follow the money that actually moved and the assets follow the counts`() = serverTest(insecureCookies = true) { ctx ->
        val zone = ZoneId.of("Europe/Vienna")
        val today = LocalDate.now(zone)
        val device = ctx.pairDevice("Theke links")
        val helles = newId(); val maria = newId(); val at = Instant.now().minus(2, ChronoUnit.HOURS)
        fun booking(price: String, qty: Int, type: String, ref: String = helles, product: String = "Helles 0,5", member: String? = null, refund: Boolean = false) = insertOp("transactions", buildJsonObject {
            put("id", newId()); put("transaction_group_id", newId()); put("member_id", member); put("product_ref", ref); put("product_name", product)
            put("price", price); put("quantity", qty); put("payment_type", type); put("occurred_at", at.toString()); put("is_refund", refund)
        })
        ctx.push(
            device.token,
            insertOp("products", buildJsonObject { put("id", helles); put("name", "Helles 0,5"); put("price", "4.20"); put("category", "Getränke") }),
            insertOp("members", buildJsonObject { put("id", maria); put("name", "Maria Bauer") }),
            booking("4.20", 2, "CASH"),                                                           // 8,40 bar
            booking("4.20", 1, "CARD"),                                                           // 4,20 Karte
            booking("4.20", 1, "CASH", refund = true),                                            // −4,20 Storno
            booking("4.20", 1, Ledger.MEMBER_BALANCE, member = maria),                            // Deckel: Forderung, keine Einnahme
            booking("20.00", 1, "CASH", ref = Ledger.TOPUP_REF, product = "Guthabenaufladung", member = maria), // 20,00 Aufladung bar
            booking("1.00", 1, "CASH", ref = Ledger.TIP_REF, product = "Trinkgeld"),              // 1,00 Trinkgeld bar
            insertOp("cash_sessions", buildJsonObject { put("id", newId()); put("device_label", "Theke links"); put("opened_at", at.minusSeconds(3600).toString()); put("opened_by", "Matthias"); put("opening_count", "100.00"); put("closed_at", at.plusSeconds(3600).toString()); put("closed_by", "Matthias"); put("closing_count", "120.00") }),
        )
        // Zwei Belege: einer bar bezahlt (Reinigung als Zeile, der Rest Getränke), einer noch offen.
        ctx.db.transaction { c ->
            val paid = UUID.randomUUID(); val open = UUID.randomUUID()
            c.execute("INSERT INTO purchase_documents (id, supplier_name, number, document_date, gross, payment, paid_at) VALUES (?, 'Brandl', 'RE-1', ?, 100.00, 'CASH', ?)", paid, java.sql.Date.valueOf(today), java.sql.Date.valueOf(today))
            c.execute("INSERT INTO purchase_lines (id, document_id, label, amount, account_id, sort) VALUES (?, ?, 'Reinigung Zapfanlage', 30.00, '00000000-0000-0000-0001-000000000013', 0)", UUID.randomUUID(), paid)
            c.execute("INSERT INTO purchase_documents (id, supplier_name, number, document_date, gross, payment) VALUES (?, 'Brandl', 'RE-2', ?, 50.00, 'OPEN')", open, java.sql.Date.valueOf(today))
        }

        val accounts = Accounts(ctx.db)
        accounts.create("lukas", "Lukas Hofer", Role.KASSIER, "ein-langes-passwort")
        accounts.create("anna", "Anna Rhomberg", Role.PRUEFER, "ein-langes-passwort")
        val kassier = createClient { install(HttpCookies); followRedirects = false }
        kassier.submitForm("/verwaltung/anmelden", parameters { append("login", "lukas"); append("passwort", "ein-langes-passwort") })
        val page = kassier.get("/verwaltung/buecher")
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()

        // Einnahmen: 8,40 + 4,20 − 4,20 = 8,40 Budenerlöse, 20,00 Aufladung, 1,00 Trinkgeld; Ausgaben: 30 Reinigung + 70 Getränke.
        assertContains(html, "Budenerlöse"); assertContains(html, euro(8.4))
        assertContains(html, euro(20.0)); assertContains(html, "Trinkgeld"); assertContains(html, euro(1.0))
        assertContains(html, "Reinigung"); assertContains(html, euro(30.0)); assertContains(html, euro(70.0))
        assertContains(html, "Abgang"); assertContains(html, euro(-70.6), message = "29,40 − 100,00")
        assertContains(html, "ohne Zufluss: ${euro(4.2)}", message = "der Deckelverkauf ist eine Forderung")
        // Vermögen: Kassabestand aus der Zählung, Guthaben 20 − 4,20, offener Beleg 50.
        assertContains(html, euro(120.0)); assertContains(html, "−15,80"); assertContains(html, "−50,00")
        assertContains(html, "nicht eingetragen")

        val csrf = Regex("""name="_csrf" value="([^"]+)"""").find(html)!!.groupValues[1]
        kassier.submitForm("/verwaltung/buecher/bank", parameters { append("_csrf", csrf); append("jahr", today.year.toString()); append("betrag", "1250,40") })
        val after = kassier.get("/verwaltung/buecher").bodyAsText()
        assertContains(after, euro(1250.4)); assertFalse(after.contains("nicht eingetragen"))

        val ear = kassier.get("/verwaltung/buecher/ear.csv").bodyAsText()
        assertContains(ear, "\"E-BUDE\";\"Budenerlöse\";\"Budenbetrieb\";\"Einnahme\";\"8,40\"")
        assertContains(ear, "\"Überschuss\";\"\";\"\";\"-70,60\"")
        val journal = kassier.get("/verwaltung/buecher/journal.csv").bodyAsText()
        assertContains(journal, "Tageslosung bar"); assertContains(journal, "Reinigung Zapfanlage"); assertContains(journal, "\"-70,00\";\"Brandl RE-1\"")

        // Der Rechnungsprüfer liest die Bücher, trägt aber nichts ein.
        val pruefer = createClient { install(HttpCookies); followRedirects = false }
        pruefer.submitForm("/verwaltung/anmelden", parameters { append("login", "anna"); append("passwort", "ein-langes-passwort") })
        assertEquals(HttpStatusCode.OK, pruefer.get("/verwaltung/buecher").status)
        val prueferCsrf = Regex("""name="_csrf" value="([^"]+)"""").find(pruefer.get("/verwaltung/buecher").bodyAsText())!!.groupValues[1]
        assertEquals(HttpStatusCode.Forbidden, pruefer.submitForm("/verwaltung/buecher/bank", parameters { append("_csrf", prueferCsrf); append("jahr", today.year.toString()); append("betrag", "1") }).status)
    }
}
