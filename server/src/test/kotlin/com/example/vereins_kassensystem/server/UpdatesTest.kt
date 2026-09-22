package com.example.vereins_kassensystem.server

import com.example.vereins_kassensystem.server.web.Accounts
import com.example.vereins_kassensystem.server.web.Role
import com.example.vereins_kassensystem.server.web.Updates
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Updates aus dem Repo: Dienst und Updater reden über ein Verzeichnis, die Verwaltung zeigt und bittet. */
class UpdatesTest {

    private val password = "ein-langes-passwort"
    private fun ApplicationTestBuilder.browser(): HttpClient = createClient { install(HttpCookies); followRedirects = false }
    private suspend fun HttpClient.form(path: String, vararg fields: Pair<String, String>): HttpResponse = submitForm(path, parameters { fields.forEach { (k, v) -> append(k, v) } })
    private fun csrfOf(html: String): String = assertNotNull(Regex("""name="_csrf" value="([^"]+)"""").find(html)).groupValues[1]
    private suspend fun HttpClient.page(path: String): String { val r = get(path); assertEquals(HttpStatusCode.OK, r.status, path); return r.bodyAsText() }
    private fun location(r: HttpResponse) = assertNotNull(r.headers[HttpHeaders.Location])

    @Test
    fun `the exchange directory carries settings, requests and the updater's status`() {
        val dir = Files.createTempDirectory("vd-updates")
        val updates = Updates(dir, "abc123def456", "2026-09-22T10:00:00+02:00")
        assertTrue(updates.available)
        assertEquals(Updates.Mode.CHECK, updates.settings().modeOrDefault, "ohne Datei die Vorgabe")
        assertFalse(updates.updateAvailable(), "ohne Status kein Update")

        updates.saveSettings(Updates.Mode.AUTO, 30)
        assertEquals("""{"mode":"AUTO","intervalMinutes":30}""", Files.readString(dir.resolve("settings.json")))
        updates.request("install")
        assertEquals("install\n", Files.readString(dir.resolve("request")))

        // Was der Updater schreibt — auch mit Feldern, die diese Fassung nicht kennt.
        Files.writeString(dir.resolve("status.json"), """{"state":"idle","message":"Zuletzt gesucht","latest":"abc123def456","latestDate":"2026-09-22T10:00:00+02:00","behind":"0","neu":"x"}""")
        assertFalse(updates.updateAvailable(), "derselbe Stand")
        Files.writeString(dir.resolve("status.json"), """{"state":"idle","message":"","latest":"fedcba987654","latestDate":"2026-09-23T10:00:00+02:00","latestMessage":"Kasse: …","behind":"3"}""")
        assertTrue(updates.updateAvailable()); assertEquals(3, updates.status().behindCount)
        assertTrue(Updates.sameCommit("abc123d", "abc123def456")); assertFalse(Updates.sameCommit("abc", ""))

        val none = Updates(null, Updates.UNKNOWN, "")
        assertFalse(none.available)
        assertEquals(Updates.Settings(), none.settings())
    }

    @Test
    fun `the settings page shows the running version, asks the updater, and keeps the administrator's choice`() {
        val dir = Files.createTempDirectory("vd-updates")
        serverTest(insecureCookies = true, updatesDir = dir) { ctx ->
            Accounts(ctx.db).create("admin", "Anna Admin", Role.ADMIN, password)
            Accounts(ctx.db).create("lukas", "Lukas Hofer", Role.KASSIER, password)
            val admin = browser()
            admin.form("/verwaltung/anmelden", "login" to "admin", "passwort" to password)
            var page = admin.page("/verwaltung/einstellungen")
            assertContains(page, "Updates"); assertContains(page, "Stand 1234567abcde"); assertContains(page, "Jetzt suchen")
            assertFalse(page.contains("Jetzt installieren"), "ohne Fund nichts zu installieren")

            // Jetzt suchen: die Bitte liegt im Verzeichnis, der Updater holt sie ab.
            admin.form("/verwaltung/einstellungen", "_csrf" to csrfOf(page), "teil" to "update", "aktion" to "pruefen")
            assertEquals("check\n", Files.readString(dir.resolve("request")))

            // Der Updater hat etwas gefunden: die Seite sagt es, und der Knopf zum Installieren ist da.
            Files.writeString(dir.resolve("status.json"), """{"state":"idle","message":"Zuletzt gesucht 2026-09-22T20:00:00Z","checkedAt":"2026-09-22T20:00:00Z","latest":"fedcba987654","latestDate":"2026-09-22T19:30:00+02:00","latestMessage":"Bardienst ohne Barkasse","behind":"2","branch":"main"}""")
            page = admin.page("/verwaltung/einstellungen")
            assertContains(page, "Bardienst ohne Barkasse"); assertContains(page, "Jetzt installieren"); assertContains(page, "2 Commits")
            val installed = admin.form("/verwaltung/einstellungen", "_csrf" to csrfOf(page), "teil" to "update", "aktion" to "installieren")
            assertContains(location(installed), "hinweis=")
            assertEquals("install\n", Files.readString(dir.resolve("request")))

            // Die Wahl des Administrators: von selbst suchen und einspielen, alle zwei Stunden.
            admin.form("/verwaltung/einstellungen", "_csrf" to csrfOf(page), "teil" to "update", "modus" to "AUTO", "abstand" to "120")
            assertEquals("""{"mode":"AUTO","intervalMinutes":120}""", Files.readString(dir.resolve("settings.json")))
            assertContains(admin.page("/verwaltung/einstellungen"), "checked")
            assertContains(admin.page("/verwaltung/protokoll"), "Updates")

            // Ein Fehlschlag des Updaters steht auf der Seite, mit dem Ende seines Protokolls.
            Files.writeString(dir.resolve("status.json"), """{"state":"failed","message":"Bauen fehlgeschlagen — der alte Stand laeuft weiter.","log":"e: Unresolved reference 'foo'","latest":"fedcba987654","behind":"2"}""")
            assertContains(admin.page("/verwaltung/einstellungen"), "Unresolved reference")

            // Der Kassier sieht die Einstellungen, aber nicht die Updates — das ist Betrieb.
            val kassier = browser()
            kassier.form("/verwaltung/anmelden", "login" to "lukas", "passwort" to password)
            val kassierPage = kassier.page("/verwaltung/einstellungen")
            assertFalse(kassierPage.contains("Jetzt suchen"))
            assertEquals(HttpStatusCode.Forbidden, kassier.form("/verwaltung/einstellungen", "_csrf" to csrfOf(kassierPage), "teil" to "update", "aktion" to "pruefen").status)
        }
    }
}
