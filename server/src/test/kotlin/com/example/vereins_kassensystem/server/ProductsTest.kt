package com.example.vereins_kassensystem.server

import com.example.vereins_kassensystem.server.web.Accounts
import com.example.vereins_kassensystem.server.web.Role
import com.example.vereins_kassensystem.sync.ChangesResponse
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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Sortiment und Kategorien vom Schreibtisch (Konzept 4.1, Phase 2): dieselben Zeilen, die die Tablets führen. */
class ProductsTest {

    private val password = "ein-langes-passwort"

    private fun ApplicationTestBuilder.browser(): HttpClient = createClient { install(HttpCookies); followRedirects = false }
    private suspend fun HttpClient.form(path: String, vararg fields: Pair<String, String>): HttpResponse = submitForm(path, parameters { fields.forEach { (k, v) -> append(k, v) } })
    private fun csrfOf(html: String): String = assertNotNull(Regex("""name="_csrf" value="([^"]+)"""").find(html)).groupValues[1]
    private suspend fun HttpClient.signIn(login: String) = form("/verwaltung/anmelden", "login" to login, "passwort" to password)
    private suspend fun HttpClient.page(path: String): String { val r = get(path); assertEquals(HttpStatusCode.OK, r.status, path); return r.bodyAsText() }
    private fun location(r: HttpResponse): String = assertNotNull(r.headers[HttpHeaders.Location])

    @Test
    fun `a product with variants and recipe made at the desk becomes a tile on the tills`() = serverTest(insecureCookies = true) { ctx ->
        val device = ctx.pairDevice("Theke links")
        val keg = newId()
        ctx.push(device.token, insertOp("stock_items", buildJsonObject { put("id", keg); put("name", "Helles Fass"); put("unit", "l"); put("tracking", "CONTAINER") }))
        val since = ctx.client.get("/v1/sync/changes?since=0") { bearerAuth(device.token) }.body<ChangesResponse>().nextSince

        val accounts = Accounts(ctx.db)
        accounts.create("budenwart", "Paul Egger", Role.BUDENWART, password)
        accounts.create("senior", "Felix Moser", Role.VORSTAND, password)
        accounts.create("pruefer", "Anna Rhomberg", Role.PRUEFER, password)
        val wart = browser(); wart.signIn("budenwart")
        val page = wart.page("/verwaltung/sortiment")
        val csrf = csrfOf(page)

        val created = wart.form("/verwaltung/sortiment", "_csrf" to csrf, "name" to " Helles ", "preis" to "4,20", "kategorie" to "Getränke", "groesse" to "0,5")
        val product = assertNotNull(Regex("p=([0-9a-f-]{36})").find(location(created))).groupValues[1]
        assertContains(location(wart.form("/verwaltung/sortiment", "_csrf" to csrf, "name" to "helles", "preis" to "4", "kategorie" to "", "groesse" to "")), "fehler=", message = "kein zweites Helles")
        assertContains(location(wart.form("/verwaltung/sortiment", "_csrf" to csrf, "name" to "Radler", "preis" to "vier", "kategorie" to "", "groesse" to "")), "fehler=", message = "ein Preis ist eine Zahl")

        wart.form("/verwaltung/sortiment/$product/variante", "_csrf" to csrf, "name" to "0,3 l", "preis" to "3,20", "groesse" to "0,3")
        wart.form("/verwaltung/sortiment/$product/rezeptur", "_csrf" to csrf, "artikel" to keg, "menge" to "1")
        wart.form("/verwaltung/sortiment/$product", "_csrf" to csrf, "name" to "Helles", "preis" to "4,50", "kategorie" to "Getränke", "groesse" to "0,5")

        val detail = wart.page("/verwaltung/sortiment?p=$product")
        assertContains(detail, "Helles"); assertContains(detail, "0,3 l"); assertContains(detail, "3,20 €"); assertContains(detail, "Helles Fass")
        assertContains(detail, "ab 3,20 €", message = "mit Varianten zeigt die Liste den günstigsten Preis")

        // Das Tablet bekommt Produkt, Variante und Rezeptur; die letzte Produktzeile trägt den neuen Preis und die Varianten-Marke.
        val pulled = ctx.client.get("/v1/sync/changes?since=$since") { bearerAuth(device.token) }.body<ChangesResponse>()
        val products = pulled.changes.filter { it.entity == "products" }
        assertTrue(products.isNotEmpty())
        val last = products.last().row
        assertEquals("4.50", last["price"]!!.jsonPrimitive.content)
        assertEquals("true", last["has_variants"]!!.jsonPrimitive.content)
        assertEquals("0.5", last["serving_size"]!!.jsonPrimitive.content)
        assertEquals("0,3 l", pulled.changes.single { it.entity == "product_variants" }.row["name"]!!.jsonPrimitive.content)
        assertEquals(keg, pulled.changes.single { it.entity == "product_components" }.row["stock_item_id"]!!.jsonPrimitive.content)
        assertEquals(pulled.changes.map { it.seq }.sorted(), pulled.changes.map { it.seq })

        // Aus dem Sortiment nehmen: Produkt, Variante und Rezeptur kommen als Löschmarke an.
        val before = pulled.nextSince
        wart.form("/verwaltung/sortiment/$product/entfernen", "_csrf" to csrf)
        val gone = ctx.client.get("/v1/sync/changes?since=$before") { bearerAuth(device.token) }.body<ChangesResponse>()
        assertEquals(setOf("products", "product_variants", "product_components"), gone.changes.map { it.entity }.toSet())
        assertTrue(gone.changes.all { it.deleted })
        assertFalse(wart.page("/verwaltung/sortiment").contains("p=$product"))

        // Das Protokoll liest der Kassier; der Budenwart hat es nicht.
        accounts.create("lukas", "Lukas Hofer", Role.KASSIER, password)
        val kassier = browser(); kassier.signIn("lukas")
        val log = kassier.page("/verwaltung/protokoll")
        assertContains(log, "Produkt angelegt"); assertContains(log, "Preis vorher 4,20 €"); assertContains(log, "Produkt aus dem Sortiment genommen")

        // Der Senior liest, schreibt aber nicht; der Rechnungsprüfer sieht das Sortiment gar nicht.
        val senior = browser(); senior.signIn("senior")
        val readOnly = senior.page("/verwaltung/sortiment")
        assertFalse(readOnly.contains("Produkt anlegen"))
        assertEquals(HttpStatusCode.Forbidden, senior.form("/verwaltung/sortiment", "_csrf" to csrfOf(readOnly), "name" to "Spritzer", "preis" to "3", "kategorie" to "", "groesse" to "").status)
        val pruefer = browser(); pruefer.signIn("pruefer")
        assertEquals(HttpStatusCode.Forbidden, pruefer.get("/verwaltung/sortiment").status)
    }

    @Test
    fun `member categories carry their limit to the tills and a used one cannot be removed`() = serverTest(insecureCookies = true) { ctx ->
        val device = ctx.pairDevice("Theke links")
        val since = ctx.client.get("/v1/sync/changes?since=0") { bearerAuth(device.token) }.body<ChangesResponse>().nextSince
        Accounts(ctx.db).create("lukas", "Lukas Hofer", Role.KASSIER, password)
        val kassier = browser(); kassier.signIn("lukas")
        val page = kassier.page("/verwaltung/mitglieder/kategorien")
        val csrf = csrfOf(page)

        kassier.form("/verwaltung/mitglieder/kategorien", "_csrf" to csrf, "name" to "Bursch", "limit" to "−50")
        assertContains(location(kassier.form("/verwaltung/mitglieder/kategorien", "_csrf" to csrf, "name" to "Gast", "limit" to "20")), "fehler=", message = "ein Limit ist ein Minus oder 0")
        kassier.form("/verwaltung/mitglieder/kategorien", "_csrf" to csrf, "name" to "Gast", "limit" to "")
        val list = kassier.page("/verwaltung/mitglieder/kategorien")
        assertContains(list, "Bursch"); assertContains(list, "−50,00 €"); assertContains(list, "kein Anschreiben")

        val pulled = ctx.client.get("/v1/sync/changes?since=$since") { bearerAuth(device.token) }.body<ChangesResponse>()
        val bursch = pulled.changes.single { it.entity == "member_categories" && it.row["name"]!!.jsonPrimitive.content == "Bursch" }
        assertEquals("-50.00", bursch.row["negative_balance_limit"]!!.jsonPrimitive.content)
        val burschId = bursch.row["id"]!!.jsonPrimitive.content
        val gastId = pulled.changes.single { it.entity == "member_categories" && it.row["name"]!!.jsonPrimitive.content == "Gast" }.row["id"]!!.jsonPrimitive.content

        // Mit einem Mitglied darin lässt sich die Kategorie nicht entfernen; die leere schon.
        kassier.form("/verwaltung/mitglieder", "_csrf" to csrf, "name" to "David Leitner", "vulgo" to "", "kategorie" to burschId)
        assertContains(location(kassier.form("/verwaltung/mitglieder/kategorien/$burschId", "_csrf" to csrf, "entfernen" to "1")), "fehler=")
        assertContains(location(kassier.form("/verwaltung/mitglieder/kategorien/$gastId", "_csrf" to csrf, "entfernen" to "1")), "hinweis=")
        val after = kassier.page("/verwaltung/mitglieder/kategorien")
        assertContains(after, "kategorien/$burschId"); assertFalse(after.contains("kategorien/$gastId"), "die leere Kategorie ist weg")
        kassier.form("/verwaltung/mitglieder/kategorien/$burschId", "_csrf" to csrf, "name" to "Bursch", "limit" to "−80")
        assertContains(kassier.page("/verwaltung/mitglieder/kategorien"), "−80,00 €")
        assertContains(kassier.page("/verwaltung/protokoll"), "Limit vorher −50,00 €")
    }
}
