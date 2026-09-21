package com.example.vereins_kassensystem.server

import com.example.vereins_kassensystem.data.Ledger
import com.example.vereins_kassensystem.server.web.Accounts
import com.example.vereins_kassensystem.server.web.Role
import com.example.vereins_kassensystem.sync.RegisterRequest
import com.example.vereins_kassensystem.sync.RegisterResponse
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Die Web-Verwaltung, Phase 1: Zugang, Rollen, und dass die Seiten zeigen, was die Theken
 * gebucht haben. Geprüft wird über HTTP und gegen echtes PostgreSQL, wie der Rest.
 */
class WebTest {

    private val password = "ein-langes-passwort"

    /** Ein Browser: merkt sich Cookies, folgt keiner Umleitung von selbst. */
    private fun ApplicationTestBuilder.browser(): HttpClient = createClient {
        install(HttpCookies)
        followRedirects = false
    }

    private suspend fun HttpClient.form(path: String, vararg fields: Pair<String, String>): HttpResponse =
        submitForm(path, parameters { fields.forEach { (k, v) -> append(k, v) } })

    private fun csrfOf(html: String): String =
        assertNotNull(Regex("""name="_csrf" value="([^"]+)"""").find(html), "kein CSRF-Feld in der Seite").groupValues[1]

    private suspend fun HttpClient.setUpAdmin() {
        val response = form(
            "/verwaltung/einrichten", "schluessel" to ADMIN_TOKEN, "name" to "Lukas Hofer", "login" to "lukas",
            "passwort" to password, "passwort2" to password
        )
        assertEquals(HttpStatusCode.Found, response.status, response.bodyAsText())
    }

    private suspend fun HttpClient.signIn(login: String = "lukas"): HttpResponse =
        form("/verwaltung/anmelden", "login" to login, "passwort" to password)

    private suspend fun HttpClient.page(path: String): String {
        val response = get(path)
        assertEquals(HttpStatusCode.OK, response.status, "$path: ${response.headers[HttpHeaders.Location]}")
        return response.bodyAsText()
    }

    // --------------------------------------------------------------- Zugang

    @Test
    fun `the first administrator needs the server key and the door closes behind them`() = serverTest(insecureCookies = true) { _ ->
        val browser = browser()
        assertEquals("/verwaltung/einrichten", browser.get("/verwaltung").headers[HttpHeaders.Location])

        val wrong = browser.form("/verwaltung/einrichten", "schluessel" to "geraten", "name" to "X", "login" to "xyz", "passwort" to password, "passwort2" to password)
        assertContains(wrong.bodyAsText(), "Verwaltungsschlüssel stimmt nicht")
        val short = browser.form("/verwaltung/einrichten", "schluessel" to ADMIN_TOKEN, "name" to "X", "login" to "xyz", "passwort" to "kurz", "passwort2" to "kurz")
        assertContains(short.bodyAsText(), "mindestens 10 Zeichen")

        browser.setUpAdmin()
        assertEquals("/verwaltung/anmelden", browser.get("/verwaltung/einrichten").headers[HttpHeaders.Location], "nach dem ersten Benutzer ist die Einrichtung zu")
        val again = browser.form("/verwaltung/einrichten", "schluessel" to ADMIN_TOKEN, "name" to "Zweiter", "login" to "zweiter", "passwort" to password, "passwort2" to password)
        assertEquals("/verwaltung/anmelden", again.headers[HttpHeaders.Location])
    }

    @Test
    fun `signing in gives a hardened cookie and a wrong password gives nothing`() = serverTest { ctx ->
        Accounts(ctx.db).create("lukas", "Lukas Hofer", Role.ADMIN, password)
        val browser = browser()

        val wrong = browser.form("/verwaltung/anmelden", "login" to "lukas", "passwort" to "falsch-falsch")
        assertNull(wrong.headers[HttpHeaders.SetCookie])
        assertContains(wrong.bodyAsText(), "stimmen nicht")
        val nobody = browser.form("/verwaltung/anmelden", "login" to "niemand", "passwort" to password)
        assertNull(nobody.headers[HttpHeaders.SetCookie], "ein unbekannter Name sieht aus wie ein falsches Passwort")

        val ok = browser.signIn()
        assertEquals("/verwaltung", ok.headers[HttpHeaders.Location])
        val cookie = assertNotNull(ok.headers[HttpHeaders.SetCookie])
        for (flag in listOf("HttpOnly", "Secure", "SameSite=Lax", "Path=/verwaltung")) assertContains(cookie, flag)
        assertFalse(cookie.contains(password))
    }

    @Test
    fun `pages carry a strict policy and forms need the session secret`() = serverTest(insecureCookies = true) { _ ->
        val browser = browser()
        browser.setUpAdmin()
        assertEquals("/verwaltung/anmelden?weiter=%2Fverwaltung%2Fmitglieder", browser.get("/verwaltung/mitglieder").headers[HttpHeaders.Location])
        assertEquals("/verwaltung/mitglieder", browser.form("/verwaltung/anmelden", "login" to "lukas", "passwort" to password, "weiter" to "/verwaltung/mitglieder").headers[HttpHeaders.Location])

        val response = browser.get("/verwaltung")
        val policy = assertNotNull(response.headers["Content-Security-Policy"])
        assertContains(policy, "default-src 'none'")
        assertFalse(policy.contains("unsafe-inline"))
        val html = response.bodyAsText()
        assertFalse(html.contains(" style="), "keine Inline-Styles, sonst greift die Policy")
        assertFalse(html.contains("<script"), "die Oberfläche kommt ohne Skripte aus")

        assertEquals(HttpStatusCode.Forbidden, browser.form("/verwaltung/abmelden").status, "ohne das Geheimnis der Sitzung passiert nichts")
        assertEquals(HttpStatusCode.OK, browser.get("/verwaltung").status)
        assertEquals("/verwaltung/anmelden", browser.form("/verwaltung/abmelden", "_csrf" to csrfOf(html)).headers[HttpHeaders.Location])
        assertEquals(HttpStatusCode.Found, browser.get("/verwaltung").status, "abgemeldet")

        // Die Anmeldung ist kein offener Weiterleiter.
        assertEquals("/verwaltung", browser.form("/verwaltung/anmelden", "login" to "lukas", "passwort" to password, "weiter" to "https://boese.example/verwaltung/").headers[HttpHeaders.Location])
    }

    @Test
    fun `a role sees its areas and nothing else`() = serverTest(insecureCookies = true) { ctx ->
        val accounts = Accounts(ctx.db)
        accounts.create("lukas", "Lukas Hofer", Role.ADMIN, password)
        accounts.create("wart", "Paul Wimmer", Role.BUDENWART, password)
        accounts.create("pruefer", "Dr. Georg Lechner", Role.PRUEFER, password, validUntil = LocalDate.now().minusDays(1))

        val wart = browser()
        assertEquals("/verwaltung/lager", wart.signIn("wart").headers[HttpHeaders.Location], "der Budenwart landet im Lager")
        assertEquals("/verwaltung/lager", wart.get("/verwaltung").headers[HttpHeaders.Location])
        val lager = wart.page("/verwaltung/lager")
        assertFalse(lager.contains("/verwaltung/mitglieder"), "keine Deckel im Menü des Budenwarts")
        val denied = wart.get("/verwaltung/mitglieder")
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        assertContains(denied.bodyAsText(), "gehört nicht zur Rolle")
        assertEquals(HttpStatusCode.Forbidden, wart.form("/verwaltung/geraete/code", "_csrf" to csrfOf(lager)).status)

        assertNull(browser().signIn("pruefer").headers[HttpHeaders.SetCookie], "der Zugang des Prüfers ist abgelaufen")

        // Der letzte Administrator kann sich nicht selbst aussperren; mit einem zweiten geht es.
        val admin = browser()
        admin.signIn()
        val users = admin.page("/verwaltung/benutzer")
        val own = accounts.list().first { it.login == "lukas" }.id
        val stuck = admin.form("/verwaltung/benutzer/$own", "_csrf" to csrfOf(users), "rolle" to "KASSIER", "aktiv" to "1")
        assertContains(assertNotNull(stuck.headers[HttpHeaders.Location]), "fehler=")
        assertEquals(Role.ADMIN, accounts.find(own)!!.role)
        admin.form("/verwaltung/benutzer", "_csrf" to csrfOf(users), "name" to "Matthias Gruber", "login" to "matthias", "rolle" to "ADMIN", "passwort" to password)
        admin.form("/verwaltung/benutzer/$own", "_csrf" to csrfOf(users), "rolle" to "KASSIER", "aktiv" to "1")
        assertEquals(Role.KASSIER, accounts.find(own)!!.role)
        assertEquals(HttpStatusCode.Found, admin.get("/verwaltung").status, "wer geändert wird, meldet sich neu an")
    }

    // ---------------------------------------------------------------- Seiten

    private fun sale(memberId: String?, name: String?, productRef: String, product: String, price: String, quantity: Int, type: String, group: String = newId(), refund: Boolean = false): JsonObject =
        buildJsonObject {
            put("id", newId()); put("transaction_group_id", group); put("member_id", memberId); put("member_name", name)
            put("product_ref", productRef); put("product_name", product); put("price", price); put("quantity", quantity)
            put("payment_type", type); put("occurred_at", Instant.now().toString()); put("is_refund", refund)
        }

    @Test
    fun `overview members and reports show what the tills booked`() = serverTest(insecureCookies = true) { ctx ->
        val device = ctx.pairDevice("Theke links")
        val fuchs = newId(); val paul = newId(); val florian = newId(); val helles = newId()
        ctx.push(
            device.token,
            insertOp("member_categories", buildJsonObject { put("id", fuchs); put("name", "Fuchs"); put("negative_balance_limit", "-30.00") }),
            insertOp("members", buildJsonObject { put("id", paul); put("name", "Paul Wimmer"); put("category_id", fuchs) }),
            insertOp("members", buildJsonObject { put("id", florian); put("name", "Florian <b>Steiner</b>"); put("category_id", fuchs) }),
            insertOp("products", buildJsonObject { put("id", helles); put("name", "Helles 0,5"); put("price", "4.20"); put("category", "Getränke") }),
            insertOp("transactions", sale(paul, "Paul Wimmer", Ledger.TOPUP_REF, "Guthaben", "20.00", 1, "CASH")),
            insertOp("transactions", sale(paul, "Paul Wimmer", helles, "Helles 0,5", "4.20", 2, Ledger.MEMBER_BALANCE)),
            insertOp("transactions", sale(florian, "Florian Steiner", helles, "Helles 0,5", "4.20", 8, Ledger.MEMBER_BALANCE)),
            insertOp("transactions", sale(null, null, helles, "Helles 0,5", "4.20", 1, "CASH")),
            insertOp("transactions", sale(null, null, helles, "Helles 0,5", "4.20", 2, "CARD")),
            insertOp("transactions", sale(null, null, helles, "Helles 0,5", "4.20", 1, "CARD", refund = true)),
        )
        val browser = browser()
        browser.setUpAdmin(); browser.signIn()

        val overview = browser.page("/verwaltung")
        // Deckel 8,40 + 33,60, bar 4,20, Karte 8,40 − 4,20 Storno. Die Aufladung ist kein Umsatz.
        assertContains(overview, "50,40 €")
        assertContains(overview, "−33,60 €", message = "Außenstände: Florian")
        assertContains(overview, "11,60 €", message = "Guthaben: Paul, 20,00 − 8,40")
        assertContains(overview, "1 über dem Limit")
        assertContains(overview, "Theke links")
        assertContains(overview, "2 × Helles 0,5")

        val members = browser.page("/verwaltung/mitglieder")
        assertContains(members, "Florian &lt;b&gt;Steiner&lt;/b&gt;", message = "ein Name bleibt ein Name, auch wenn er wie HTML aussieht")
        assertFalse(members.contains("<b>Steiner"))
        val onlyMinus = browser.page("/verwaltung/mitglieder?f=minus")
        assertFalse(onlyMinus.contains("m=$paul"), "Paul ist im Plus")
        assertContains(onlyMinus, "m=$florian")
        assertFalse(browser.page("/verwaltung/mitglieder?q=wimm").contains("m=$florian"))

        val statement = browser.page("/verwaltung/mitglieder?m=$paul")
        assertContains(statement, "has-sel")
        assertContains(statement, "+20,00 €")
        assertContains(statement, "−8,40 €")
        assertContains(statement, "Der Stand ist die Summe der Buchungen")

        val reports = browser.page("/verwaltung/berichte")
        assertContains(reports, "50,40 €")
        assertContains(reports, "20,00 €", message = "Aufladungen")
        // Bar 4,20 plus Karte 8,40 − 4,20 Storno; der Deckel zählt für diese Schwelle nicht.
        assertContains(reports, "8,40 €</span> von 7.500,00 €", message = "bar und Karte, am Schwellenwert gemessen")
    }

    @Test
    fun `stock is derived on the server the way the tablet derives it`() = serverTest(insecureCookies = true) { ctx ->
        val device = ctx.pairDevice()
        val wurst = newId(); val bier = newId(); val keg = newId(); val now = Instant.now()
        ctx.push(
            device.token,
            insertOp("stock_items", buildJsonObject { put("id", wurst); put("name", "Bratwurst"); put("unit", "Stk"); put("tracking", "SIMPLE"); put("min_level", 40.0) }),
            insertOp("stock_items", buildJsonObject { put("id", bier); put("name", "Helles"); put("unit", "l"); put("tracking", "CONTAINER"); put("min_level", 20.0) }),
            insertOp("container_types", buildJsonObject { put("id", keg); put("stock_item_id", bier); put("label", "50 l Fass"); put("nominal_size", 50.0); put("initial_yield_estimate", 47.0) }),
            insertOp("stock_entries", buildJsonObject { put("id", newId()); put("stock_item_id", wurst); put("item_name", "Bratwurst"); put("quantity", 30.0); put("unit_label", "Stk"); put("total_cost", "39.00"); put("source", "MANUAL"); put("occurred_at", now.minusSeconds(7200).toString()) }),
            insertOp("stock_entries", buildJsonObject { put("id", newId()); put("stock_item_id", bier); put("container_type_id", keg); put("item_name", "Helles"); put("quantity", 2.0); put("unit_label", "50 l Fass"); put("total_cost", "284.00"); put("source", "MANUAL"); put("occurred_at", now.minusSeconds(7200).toString()) }),
            insertOp("tapped_containers", buildJsonObject { put("id", newId()); put("container_type_id", keg); put("opened_at", now.minusSeconds(3600).toString()) }),
            insertOp("stock_draws", buildJsonObject { put("id", newId()); put("stock_item_id", wurst); put("volume", 4.0); put("occurred_at", now.minusSeconds(1800).toString()) }),
            insertOp("stock_draws", buildJsonObject { put("id", newId()); put("stock_item_id", bier); put("volume", 12.0); put("occurred_at", now.minusSeconds(1800).toString()) }),
        )
        val browser = browser()
        browser.setUpAdmin(); browser.signIn()

        val stock = browser.page("/verwaltung/lager")
        assertContains(stock, "26 Stk", message = "30 Eingang − 4 Abgang")
        assertContains(stock, "fehlen 14 Stk")
        // Ein volles Fass zu 47 l Ertrag plus der Rest des angestochenen: 47 + (47 − 12).
        assertContains(stock, "ca. 82 l")
        assertContains(stock, "12 l gezapft · noch ca. 35 l")
        assertContains(stock, "1× 50 l Fass")
        // 26 Stk zu 1,30 € und 82 l zu 2,84 €.
        assertContains(stock, "266,68 €")
    }

    @Test
    fun `a device is paired and revoked from the web and the log remembers who did it`() = serverTest(insecureCookies = true) { ctx ->
        val browser = browser()
        browser.setUpAdmin(); browser.signIn()
        val before = browser.page("/verwaltung/geraete")
        assertContains(before, "Noch kein Code erzeugt")

        assertEquals("/verwaltung/geraete", browser.form("/verwaltung/geraete/code", "_csrf" to csrfOf(before)).headers[HttpHeaders.Location])
        val shown = browser.page("/verwaltung/geraete")
        val code = assertNotNull(Regex("<strong>([A-Z2-9]{4}-[A-Z2-9]{4})</strong>").find(shown), "der Code steht einmal da").groupValues[1]
        assertFalse(browser.page("/verwaltung/geraete").contains(code), "und nur einmal")

        val registered = ctx.client.post("/v1/devices/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(code, "iPad Garten", "ios"))
        }
        assertEquals(HttpStatusCode.Created, registered.status, registered.bodyAsText())
        val token = registered.body<RegisterResponse>().token
        assertEquals(HttpStatusCode.OK, ctx.client.get("/v1/sync/changes?since=0") { bearerAuth(token) }.status)

        val listed = browser.page("/verwaltung/geraete")
        assertContains(listed, "iPad Garten")
        assertContains(listed, "Gerät gekoppelt")
        val id = Regex("""/verwaltung/geraete/([0-9a-f-]{36})/sperren""").find(listed)!!.groupValues[1]
        browser.form("/verwaltung/geraete/$id/sperren", "_csrf" to csrfOf(listed), "grund" to "im Garten vergessen")
        assertEquals(HttpStatusCode.Unauthorized, ctx.client.get("/v1/sync/changes?since=0") { bearerAuth(token) }.status, "gesperrt heißt gesperrt")

        val log = browser.page("/verwaltung/protokoll")
        assertContains(log, "Gerät gesperrt")
        assertContains(log, "im Garten vergessen")
        assertContains(log, "Lukas Hofer")
    }

    @Test
    fun `the club colour arrives as a stylesheet and not as inline style`() = serverTest(insecureCookies = true) { _ ->
        val browser = browser()
        browser.setUpAdmin(); browser.signIn()
        assertContains(browser.page("/verwaltung/assets/verein.css"), "#146B4C")
        assertTrue(browser.get("/verwaltung/assets/app.css").bodyAsText().contains("--primary"))

        val settings = browser.page("/verwaltung/einstellungen")
        browser.form("/verwaltung/einstellungen", "_csrf" to csrfOf(settings), "name" to "K.Ö.St.V. Beispiel", "farbe" to "#F9A825", "monat" to "10")
        val css = browser.page("/verwaltung/assets/verein.css")
        assertContains(css, "--accent: #F9A825")
        assertContains(css, "--on-accent: #16190F", message = "auf Gold liest sich dunkle Schrift besser")
        assertContains(browser.page("/verwaltung"), "K.Ö.St.V. Beispiel")
        assertContains(browser.page("/verwaltung/berichte"), "Rechnungsjahr")
        val refused = browser.form("/verwaltung/einstellungen", "_csrf" to csrfOf(settings), "name" to "x", "farbe" to "red; background: url(x)", "monat" to "1")
        assertContains(assertNotNull(refused.headers[HttpHeaders.Location]), "fehler=")
    }
}
