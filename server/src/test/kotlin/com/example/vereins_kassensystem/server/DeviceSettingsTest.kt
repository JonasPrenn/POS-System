package com.example.vereins_kassensystem.server

import com.example.vereins_kassensystem.server.web.Accounts
import com.example.vereins_kassensystem.server.web.Role
import com.example.vereins_kassensystem.sync.ChangesResponse
import com.example.vereins_kassensystem.sync.ErrorResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
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

/** Die Einstellungen der Tablets kommen aus der Verwaltung: als Zeilen, die nur der Server schreibt. */
class DeviceSettingsTest {

    private val password = "ein-langes-passwort"
    private fun ApplicationTestBuilder.browser(): HttpClient = createClient { install(HttpCookies); followRedirects = false }
    private suspend fun HttpClient.form(path: String, vararg fields: Pair<String, String>): HttpResponse = submitForm(path, parameters { fields.forEach { (k, v) -> append(k, v) } })
    private fun csrfOf(html: String): String = assertNotNull(Regex("""name="_csrf" value="([^"]+)"""").find(html)).groupValues[1]
    private suspend fun HttpClient.page(path: String): String { val r = get(path); assertEquals(HttpStatusCode.OK, r.status, path); return r.bodyAsText() }
    private fun ChangesResponse.settings() = changes.filter { it.entity == "device_settings" }.associate { it.row["key"]!!.jsonPrimitive.content to it.row["value"]!!.jsonPrimitive.content }

    @Test
    fun `what the web sets for the tablets arrives as synchronised rows and is never pushed back`() = serverTest(insecureCookies = true) { ctx ->
        val device = ctx.pairDevice("Theke links")
        assertTrue(ctx.client.get("/v1/sync/changes?since=0") { bearerAuth(device.token) }.body<ChangesResponse>().settings().isEmpty(), "ohne gespeicherte Einstellungen gibt es keine Zeilen")

        Accounts(ctx.db).create("lukas", "Lukas Hofer", Role.KASSIER, password)
        val kassier = browser()
        kassier.form("/verwaltung/anmelden", "login" to "lukas", "passwort" to password)
        val page = kassier.page("/verwaltung/einstellungen")
        kassier.form("/verwaltung/einstellungen", "_csrf" to csrfOf(page), "name" to "KMV Clunia Feldkirch", "farbe" to "#C62828", "monat" to "10", "anschrift" to "")
        kassier.form("/verwaltung/einstellungen", "_csrf" to csrfOf(page), "teil" to "tablets", "sumup" to "sup_afk_geheim", "sicherung" to "1")
        val pulled = ctx.client.get("/v1/sync/changes?since=0") { bearerAuth(device.token) }.body<ChangesResponse>()
        assertEquals(mapOf("club_name" to "KMV Clunia Feldkirch", "club_accent" to "#C62828", "sumup_affiliate_key" to "sup_afk_geheim", "tablet_auto_backup" to "1"), pulled.settings())
        val since = pulled.nextSince

        // Die Seite zeigt den Schlüssel nie, nur dass er da ist; leer abgeschickt bleibt er — und die Tablets bekommen keine neue Zeile.
        val settings = kassier.page("/verwaltung/einstellungen")
        assertContains(settings, "hinterlegt"); assertFalse(settings.contains("sup_afk_geheim"))
        kassier.form("/verwaltung/einstellungen", "_csrf" to csrfOf(page), "teil" to "tablets", "sumup" to "", "sicherung" to "1")
        kassier.form("/verwaltung/einstellungen", "_csrf" to csrfOf(page), "name" to "KMV Clunia Feldkirch", "farbe" to "#C62828", "monat" to "10", "anschrift" to "Bude 1")
        assertTrue(ctx.client.get("/v1/sync/changes?since=$since") { bearerAuth(device.token) }.body<ChangesResponse>().settings().isEmpty(), "nichts Neues für die Tablets, keine neue Sequenznummer")

        // Entfernen und Sicherung aus: dieselben Zeilen, neue Nummern, neue Werte.
        kassier.form("/verwaltung/einstellungen", "_csrf" to csrfOf(page), "teil" to "tablets", "sumup" to "", "sumup_entfernen" to "1")
        assertEquals(mapOf("sumup_affiliate_key" to "", "tablet_auto_backup" to "0"), ctx.client.get("/v1/sync/changes?since=$since") { bearerAuth(device.token) }.body<ChangesResponse>().settings())
        assertEquals(4, ctx.client.get("/v1/sync/changes?since=0") { bearerAuth(device.token) }.body<ChangesResponse>().settings().size, "je Schlüssel eine Zeile, nie zwei")

        // Ein Tablet darf diese Zeilen nicht schreiben.
        val response = ctx.pushRaw(device.token, insertOp("device_settings", buildJsonObject { put("id", newId()); put("key", "club_name"); put("value", "Fremd") }))
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertContains(response.body<ErrorResponse>().message, "setzt die Verwaltung")
        assertContains(kassier.page("/verwaltung/protokoll"), "SumUp")
    }
}
