package com.example.vereins_kassensystem.server

import com.example.vereins_kassensystem.data.Ledger
import com.example.vereins_kassensystem.server.db.execute
import com.example.vereins_kassensystem.server.db.query
import com.example.vereins_kassensystem.server.db.queryOne
import com.example.vereins_kassensystem.server.payments.Checkout
import com.example.vereins_kassensystem.server.web.Accounts
import com.example.vereins_kassensystem.server.web.Role
import com.example.vereins_kassensystem.server.web.VereinSettings
import com.example.vereins_kassensystem.sync.ChangesResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Online aufladen: Mitglieder melden sich mit einem Link an, zahlen bei SumUp (hier nachgebaut),
 * und gebucht wird erst, wenn SumUp auf Nachfrage „bezahlt“ sagt — genau einmal, als gewöhnliche
 * Aufladung mit Karte, die die Tablets beim Abgleich bekommen.
 */
class PortalTest {

    private val password = "ein-langes-passwort"
    private fun ApplicationTestBuilder.browser(): HttpClient = createClient { install(HttpCookies); followRedirects = false }
    private suspend fun HttpClient.form(path: String, vararg fields: Pair<String, String>): HttpResponse = submitForm(path, parameters { fields.forEach { (k, v) -> append(k, v) } })
    private fun csrfOf(html: String): String = assertNotNull(Regex("""name="_csrf" value="([^"]+)"""").find(html), "kein CSRF-Feld").groupValues[1]
    private suspend fun HttpClient.page(path: String): String { val r = get(path); assertEquals(HttpStatusCode.OK, r.status, "$path → ${r.headers[HttpHeaders.Location]}"); return r.bodyAsText() }
    private fun location(r: HttpResponse) = assertNotNull(r.headers[HttpHeaders.Location], "keine Umleitung")
    private fun decoded(r: HttpResponse) = java.net.URLDecoder.decode(location(r), Charsets.UTF_8)

    private class Club(val maria: String, val peter: String, val theke: String)

    /** Zwei Mitglieder von der Theke; Maria mit Adresse im Profil, Peter mit einer anderen. */
    private suspend fun TestContext.club(): Club {
        val theke = pairDevice("Theke")
        val maria = newId(); val peter = newId()
        push(theke.token, insertOp("members", buildJsonObject { put("id", maria); put("name", "Maria Bauer") }), insertOp("members", buildJsonObject { put("id", peter); put("name", "Peter Gruber") }))
        db.transaction { c ->
            c.execute("INSERT INTO member_profiles (member_id, email) VALUES (?::uuid, 'maria@example.at')", maria)
            c.execute("INSERT INTO member_profiles (member_id, email) VALUES (?::uuid, 'peter@example.at')", peter)
        }
        return Club(maria, peter, theke.token)
    }

    private fun TestContext.switchOn() {
        val settings = VereinSettings(db)
        settings.saveSmtp("smtp.example.at", 587, "", null, "kassier@example.at", true)
        settings.saveOnline(true, FakePayments.KEY, false, "MC123", "10, 20, 50", 5, 200, listOf("apple_pay", "google_pay"))
    }

    /** Anmelden wie ein Mitglied: Adresse eintippen, Link aus der Mail, einmal tippen. */
    private suspend fun ApplicationTestBuilder.member(ctx: TestContext, email: String = "maria@example.at"): HttpClient {
        val phone = browser()
        val before = ctx.outbox.sent.size
        phone.form("/konto/verein/anmelden", "email" to email)
        val mail = ctx.outbox.sent.drop(before).single()
        val token = assertNotNull(Regex("""/konto/verein/link/([A-Za-z0-9_-]+)""").find(mail.text)).groupValues[1]
        assertEquals("/konto/verein", location(phone.form("/konto/verein/link/$token")))
        return phone
    }

    @Test
    fun `the treasurer switches online top-up on only after SumUp accepts the key`() = serverTest(insecureCookies = true) { ctx ->
        Accounts(ctx.db).create("kassier", "Karl Kassa", Role.KASSIER, password)
        val kassier = browser()
        kassier.form("/verwaltung/anmelden", "login" to "kassier", "passwort" to password)
        var page = kassier.page("/verwaltung/einstellungen")
        assertContains(page, "Online aufladen"); assertContains(page, "nicht eingerichtet"); assertContains(page, "http://localhost/konto/verein")
        fun fields(key: String, on: Boolean = true) = arrayOf("_csrf" to csrfOf(page), "teil" to "online", "schluessel" to key, "haendler" to "mc123", "betraege" to "10, 20, 50", "min" to "5", "max" to "200") + if (on) arrayOf("aktiv" to "1") else emptyArray()

        assertContains(decoded(kassier.form("/verwaltung/einstellungen", *fields(FakePayments.KEY))), "E-Mail-Versand", message = "ohne Versand keine Anmeldelinks")
        VereinSettings(ctx.db).saveSmtp("smtp.example.at", 587, "", null, "kassier@example.at", true)
        assertContains(decoded(kassier.form("/verwaltung/einstellungen", *fields("sup_sk_falsch"))), "kennt diesen API-Schlüssel nicht")
        assertFalse(VereinSettings(ctx.db).load().online.enabled)

        assertContains(decoded(kassier.form("/verwaltung/einstellungen", *fields(FakePayments.KEY))), "Gespeichert")
        val online = VereinSettings(ctx.db).load().online
        assertTrue(online.usable); assertEquals("MC123", online.merchantCode); assertEquals(listOf(10, 20, 50), online.presets)
        page = kassier.page("/verwaltung/einstellungen")
        assertContains(page, "eingeschaltet"); assertContains(page, "Karte, Apple Pay, Google Pay")
        assertContains(page, "EPS erscheint, sobald der SumUp-Support", message = "EPS ist vorbereitet: kommt, wenn SumUp es freischaltet")
        assertFalse(page.contains(FakePayments.KEY), "der Schlüssel steht nie in einer Seite")
        assertNull(ctx.db.transaction { c -> c.queryOne("SELECT 1 FROM device_settings WHERE value = ?", FakePayments.KEY) { true } }, "und nie auf einem Tablet")

        // Schaltet SumUp EPS frei, steht es nach dem nächsten Speichern auf der Bezahlseite.
        ctx.payments.methods = listOf("apple_pay", "google_pay", "eps")
        kassier.form("/verwaltung/einstellungen", "_csrf" to csrfOf(page), "teil" to "online", "haendler" to "MC123", "betraege" to "10, 20, 50", "min" to "5", "max" to "200", "aktiv" to "1")
        assertContains(kassier.page("/verwaltung/einstellungen"), "Karte, Apple Pay, Google Pay, EPS")
        assertContains(decoded(kassier.form("/verwaltung/einstellungen", "_csrf" to csrfOf(page), "teil" to "online", "haendler" to "MC123", "betraege" to "1000", "min" to "5", "max" to "200")), "zwischen 5 und 200")
    }

    @Test
    fun `a member signs in with a link from the mail and sees only their own tab`() = serverTest(insecureCookies = true) { ctx ->
        val club = ctx.club()
        ctx.switchOn()
        assertEquals("/konto/verein", location(browser().get("/konto")))
        val login = browser().get("/konto/verein")
        assertEquals(HttpStatusCode.OK, login.status)
        assertContains(assertNotNull(login.headers["Content-Security-Policy"]), "default-src 'none'")
        val loginPage = login.bodyAsText()
        assertFalse(loginPage.contains(" style=")); assertFalse(loginPage.contains("<script"))

        // Eine unbekannte Adresse bekommt dieselbe Antwort — und keine Mail.
        val unknown = browser().form("/konto/verein/anmelden", "email" to "niemand@example.at").bodyAsText()
        assertContains(unknown, "Wenn die Adresse beim Verein hinterlegt ist")
        assertTrue(ctx.outbox.sent.isEmpty())

        val phone = browser()
        phone.form("/konto/verein/anmelden", "email" to " Maria@Example.at ")
        val mail = ctx.outbox.sent.single()
        assertEquals("maria@example.at", mail.to.lowercase().trim())
        val link = assertNotNull(Regex("""http://localhost/konto/verein/link/([A-Za-z0-9_-]+)""").find(mail.text)).groupValues[1]
        // Öffnen allein meldet nicht an — ein Virenscanner, der den Link vorab abruft, verbraucht ihn nicht.
        assertContains(phone.page("/konto/verein/link/$link"), "Jetzt anmelden")
        assertContains(phone.page("/konto/verein/link/$link"), "Jetzt anmelden")
        val signedIn = phone.form("/konto/verein/link/$link")
        assertEquals("/konto/verein", location(signedIn))
        assertContains(assertNotNull(signedIn.headers[HttpHeaders.SetCookie]), "Path=/konto/verein")
        assertContains(decoded(browser().form("/konto/verein/link/$link")), "schon benutzt", message = "ein Link gilt einmal")

        val account = phone.page("/konto/verein")
        assertContains(account, "Maria Bauer"); assertFalse(account.contains("Peter Gruber"))
        assertContains(account, "Weiter zur Bezahlung"); assertContains(account, "Karte, Apple Pay, Google Pay")
        assertFalse(account.contains(" style=")); assertFalse(account.contains("<script"))

        // Drei Links je Viertelstunde, dann ist Ruhe.
        repeat(4) { browser().form("/konto/verein/anmelden", "email" to "peter@example.at") }
        assertEquals(3, ctx.outbox.sent.count { it.to == "peter@example.at" })

        // Ein anderer Verein auf demselben Server kennt diese Sitzung nicht.
        ctx.directory.system!!.accounts.create("jonas", "Jonas Prenn", Role.ADMIN, password)
        ctx.directory.create("AV Austria", "austria", "Otto Obmann", "obmann", password)
        assertContains(phone.page("/konto/austria"), "Link schicken")
        assertEquals(HttpStatusCode.NotFound, browser().get("/konto/gibtsnicht").status)
        assertTrue(club.peter.isNotEmpty())
    }

    @Test
    fun `a top-up paid at SumUp is booked exactly once and reaches the tablets`() = serverTest(insecureCookies = true) { ctx ->
        val club = ctx.club()
        ctx.switchOn()
        val phone = member(ctx)
        val account = phone.page("/konto/verein")

        val started = phone.form("/konto/verein/aufladen", "_csrf" to csrfOf(account), "mitglied" to club.maria, "betrag" to "20")
        val payPath = location(started)
        assertTrue(payPath.startsWith("/konto/verein/bezahlen/"), payPath)
        val topUpId = payPath.substringAfterLast('/')
        val pay = phone.page(payPath)
        assertContains(pay, "https://checkout.sumup.com/pay/chk-1"); assertContains(pay, "20,00")

        val request = ctx.payments.created.single()
        assertEquals(topUpId, request.reference.toString())
        assertEquals(0, BigDecimal("20.00").compareTo(request.amount))
        assertEquals("http://localhost/v1/online/sumup/${ctx.directory.default.id}", request.notifyUrl)
        assertEquals("http://localhost/konto/verein/zahlung/$topUpId", request.returnUrl)
        assertContains(request.description, "Maria Bauer")

        // Noch nicht bezahlt: Die Seite nach der Rückkehr sagt es, gebucht ist nichts.
        assertContains(phone.page("/konto/verein/zahlung/$topUpId"), "noch nicht bestätigt")
        assertEquals(0, ctx.topUps(club.maria))

        // SumUp meldet sich, der Server fragt nach — gebucht. Zweimal gemeldet, einmal gebucht.
        ctx.payments.pay("chk-1")
        repeat(2) { assertEquals(HttpStatusCode.NoContent, ctx.notify("chk-1").status) }
        assertEquals(1, ctx.topUps(club.maria))
        val row = assertNotNull(ctx.db.transaction { c ->
            c.queryOne("SELECT id::text, payment_type, price, note, product_ref::text FROM transactions WHERE member_id = ?::uuid", club.maria) {
                listOf(it.getString(1), it.getString(2), it.getBigDecimal(3).toPlainString(), it.getString(4), it.getString(5))
            }
        })
        assertEquals(listOf(topUpId, "CARD", "20.00", "Online-Aufladung", Ledger.TOPUP_REF), row)

        val result = phone.page("/konto/verein/zahlung/$topUpId")
        assertContains(result, "Danke! 20,00"); assertContains(result, "Neuer Stand")
        assertContains(phone.page("/konto/verein"), "20,00")

        // Die Tablets bekommen sie wie jede Aufladung.
        val changes = ctx.client.get("/v1/sync/changes?since=0") { bearerAuth(club.theke) }.body<ChangesResponse>()
        val booked = changes.changes.single { it.entity == "transactions" }
        assertEquals("CARD", booked.row["payment_type"]!!.jsonPrimitive.content)
        assertEquals(topUpId, booked.row["id"]!!.jsonPrimitive.content)

        assertContains(ctx.directory.default.web.audit.recent(5).map { it.actor }, "Online-Aufladung")
        Accounts(ctx.db).create("kassier", "Karl Kassa", Role.KASSIER, password)
        val kassier = browser(); kassier.form("/verwaltung/anmelden", "login" to "kassier", "passwort" to password)
        val settings = kassier.page("/verwaltung/einstellungen")
        assertContains(settings, "Maria Bauer"); assertContains(settings, "gebucht")
    }

    @Test
    fun `nothing is booked that SumUp does not confirm as this very payment`() = serverTest(insecureCookies = true) { ctx ->
        val club = ctx.club()
        ctx.switchOn()
        val phone = member(ctx)
        val csrf = csrfOf(phone.page("/konto/verein"))
        suspend fun start(amount: String, member: String = club.maria) = phone.form("/konto/verein/aufladen", "_csrf" to csrf, "mitglied" to member, "eigener" to amount)

        // Was gar nicht erst angelegt wird.
        assertContains(decoded(start("2")), "5,00")
        assertContains(decoded(start("500")), "200,00")
        assertContains(decoded(start("20", member = club.peter)), "gehört nicht zu dieser Anmeldung")
        assertContains(decoded(phone.form("/konto/verein/aufladen", "_csrf" to "geraten", "mitglied" to club.maria, "betrag" to "20")), "zu alt")
        assertTrue(ctx.payments.created.isEmpty())

        // Eine Meldung ohne Gegenstück, eine noch offene Zahlung: nichts.
        assertEquals(HttpStatusCode.NoContent, ctx.notify("chk-unbekannt").status)
        start("30")
        assertEquals(HttpStatusCode.NoContent, ctx.notify("chk-1").status)
        assertEquals(0, ctx.topUps(club.maria))

        // SumUp meldet einen anderen Betrag als angelegt: nicht gebucht, und die Verwaltung sieht es.
        ctx.payments.pay("chk-1", amount = BigDecimal("3.00"))
        ctx.notify("chk-1")
        assertEquals(0, ctx.topUps(club.maria))
        val odd = ctx.directory.default.web.portal.recent(1).single()
        assertEquals("FAILED", odd.status); assertContains(odd.detail, "Betrag")

        // SumUp ist kurz weg: Die Meldung bittet um Wiederholung, und nachgefragt wird später.
        start("40")
        ctx.payments.pay("chk-2")
        ctx.payments.down = true
        assertEquals(HttpStatusCode.ServiceUnavailable, ctx.notify("chk-2").status)
        assertEquals(0, ctx.topUps(club.maria))
        ctx.payments.down = false
        ctx.directory.default.web.portal.sweep()
        assertEquals(1, ctx.topUps(club.maria))

        // Abgebrochen bei SumUp: nichts gebucht, und die Seite sagt es.
        val third = location(start("15")).substringAfterLast('/')
        ctx.payments.pay("chk-3", status = Checkout.Status.FAILED)
        assertContains(phone.page("/konto/verein/zahlung/$third"), "nicht durchgegangen")
        assertEquals(1, ctx.topUps(club.maria))

        // Ohne Anmeldung nennt die Rückkehrseite weder Namen noch Stand.
        assertFalse(browser().page("/konto/verein/zahlung/$third").contains("Maria"))

        // SumUp lehnt schon das Anlegen ab: Das Mitglied liest, dass es gerade nicht geht; den Grund sieht der Kassier.
        ctx.payments.down = true
        assertContains(decoded(start("25")), "nicht erreichbar")
        ctx.payments.down = false
        VereinSettings(ctx.db).saveOnline(true, "sup_sk_falsch", false, "MC123", "10, 20, 50", 5, 200, null)
        val refused = decoded(start("25"))
        assertContains(refused, "an der Theke aufladen"); assertFalse(refused.contains("API-Schlüssel"))
        assertContains(ctx.directory.default.web.portal.recent(1).single().detail, "API-Schlüssel")
    }

    private suspend fun TestContext.notify(checkoutId: String): HttpResponse = client.post("/v1/online/sumup/${directory.default.id}") {
        contentType(ContentType.Application.Json)
        setBody("""{"event_type":"CHECKOUT_STATUS_CHANGED","id":"$checkoutId"}""")
    }

    private fun TestContext.topUps(memberId: String): Int =
        db.transaction { c -> c.query("SELECT 1 FROM transactions WHERE member_id = ?::uuid AND product_ref = ?::uuid", memberId, Ledger.TOPUP_REF) { 1 } }.size
}
