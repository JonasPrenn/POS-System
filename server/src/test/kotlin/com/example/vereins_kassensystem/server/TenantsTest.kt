package com.example.vereins_kassensystem.server

import com.example.vereins_kassensystem.server.db.execute
import com.example.vereins_kassensystem.server.db.query
import com.example.vereins_kassensystem.server.db.queryOne
import com.example.vereins_kassensystem.server.devices.Argon2
import com.example.vereins_kassensystem.server.tenancy.Kuerzel
import com.example.vereins_kassensystem.server.tenancy.Tenant
import com.example.vereins_kassensystem.server.tenancy.TenantDirectory
import com.example.vereins_kassensystem.server.web.AccountProblem
import com.example.vereins_kassensystem.server.web.Accounts
import com.example.vereins_kassensystem.server.web.Role
import com.example.vereins_kassensystem.sync.ChangesResponse
import com.example.vereins_kassensystem.sync.RegisterRequest
import com.example.vereins_kassensystem.sync.RegisterResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mehrere Vereine auf einem Server: jeder mit eigener Datenbank, Geräten und Zugängen; die
 * Datenbank von vor den Vereinen ist der erste. Dazu die Systemverwaltung (Hauptadmin), die
 * Mindestversion der App, das Kürzel hinter dem @ und die Testanmeldung admin#benutzer@verein.
 */
class TenantsTest {

    private val password = "ein-langes-passwort"
    private fun ApplicationTestBuilder.browser(): HttpClient = createClient { install(HttpCookies); followRedirects = false }
    private suspend fun HttpClient.form(path: String, vararg fields: Pair<String, String>): HttpResponse = submitForm(path, parameters { fields.forEach { (k, v) -> append(k, v) } })
    private fun csrfOf(html: String): String = assertNotNull(Regex("""name="_csrf" value="([^"]+)"""").find(html), "kein CSRF-Feld").groupValues[1]
    private suspend fun HttpClient.page(path: String): String { val r = get(path); assertEquals(HttpStatusCode.OK, r.status, "$path → ${r.headers[HttpHeaders.Location]}"); return r.bodyAsText() }
    private fun location(r: HttpResponse) = assertNotNull(r.headers[HttpHeaders.Location], "keine Umleitung")
    private fun decoded(r: HttpResponse) = java.net.URLDecoder.decode(location(r), Charsets.UTF_8)
    private fun cookieValue(r: HttpResponse, name: String) = assertNotNull(r.headers.getAll(HttpHeaders.SetCookie)?.firstOrNull { it.startsWith("$name=") }, "kein Cookie $name").substringAfter("=").substringBefore(";")

    private suspend fun TestContext.pairWith(tenant: Tenant, label: String): RegisterResponse {
        val (code, _) = tenant.devices.createPairingCode()
        val response = client.post("/v1/devices/register") { contentType(ContentType.Application.Json); setBody(RegisterRequest(code, label, "android")) }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsTextSafe())
        return response.body()
    }

    private suspend fun TestContext.changes(token: String): ChangesResponse = client.get("/v1/sync/changes?since=0") { bearerAuth(token) }.body()

    private fun ChangesResponse.names(entity: String, field: String = "name") = changes.filter { it.entity == entity }.map { it.row[field]!!.jsonPrimitive.content }

    private fun Tenant.minVersionRow(): String? = db.transaction { c -> c.queryOne("SELECT value FROM device_settings WHERE key = 'min_app_version'") { it.getString(1) } }

    private suspend fun HttpClient.signInSystem(login: String = "jonas"): HttpResponse = form("/verwaltung/anmelden", "login" to "$login@system", "passwort" to password)

    /** Ein Hauptadmin und ein zweiter Verein mit Administrator „obmann“, angelegt über die Seite. */
    private suspend fun ApplicationTestBuilder.austria(ctx: TestContext): Tenant {
        ctx.directory.system!!.accounts.create("jonas", "Jonas Prenn", Role.ADMIN, password)
        val haupt = browser()
        haupt.signInSystem()
        val created = haupt.form(
            "/verwaltung/system", "_csrf" to csrfOf(haupt.page("/verwaltung/system")), "teil" to "verein",
            "name" to "AV Austria", "kuerzel" to "austria", "admin_name" to "Otto Obmann", "admin_login" to "obmann", "passwort" to password, "passwort2" to password,
        )
        assertContains(decoded(created), "Anmelden mit obmann@austria")
        return assertNotNull(ctx.directory.bySlug("austria"))
    }

    // ------------------------------------------------------------- Kürzel

    @Test
    fun `a Kürzel is short, plain and suggested from the name`() {
        assertEquals("clunia", Kuerzel.suggest("KMV Clunia Feldkirch"), "Abkürzungen wie KMV zählen nicht")
        assertEquals("koeln", Kuerzel.suggest("1. FC Köln"))
        assertEquals("musikverein", Kuerzel.suggest("Musikverein Harmonie e.V."))
        assertEquals("verein", Kuerzel.suggest(""))
        assertNull(Kuerzel.problem("ab")); assertNull(Kuerzel.problem("av-austria-1"))
        for (bad in listOf("a", "-ab", "ab-", "a_b", "Ab", "a b", "x".repeat(31))) assertNotNull(Kuerzel.problem(bad), bad)
        assertContains(assertNotNull(Kuerzel.problem("system")), "reserviert")
        assertEquals(103_000, TenantDirectory.codeOf("1.3.0")); assertEquals(102_010, TenantDirectory.codeOf("1.2.1"))
        for (bad in listOf("1.3", "1.100.0", "a.b.c", "1.3.0-beta", "")) assertNull(TenantDirectory.codeOf(bad), bad)
    }

    // ------------------------------------------------------ der erste Verein

    @Test
    fun `the first Verein keeps its logins and cookies and answers to its Kürzel too`() = serverTest(insecureCookies = true) { ctx ->
        Accounts(ctx.db).create("lukas", "Lukas Hofer", Role.KASSIER, password)
        assertEquals("verein", ctx.directory.default.info.slug, "ohne Vereinsnamen das neutrale Kürzel")
        assertTrue(ctx.directory.default.info.isDefault)

        val plain = browser().form("/verwaltung/anmelden", "login" to "lukas", "passwort" to password)
        assertEquals("/verwaltung", location(plain))
        assertFalse(cookieValue(plain, "vd_session").contains('.'), "das Cookie des ersten Vereins sieht aus wie vor den Vereinen")
        assertEquals("/verwaltung", location(browser().form("/verwaltung/anmelden", "login" to "Lukas@Verein", "passwort" to password)))

        // Ein Anmeldename von vor den Vereinen mit @ darin meldet sich weiter an — neu vergeben wird so einer nicht.
        ctx.db.transaction { c -> c.execute("INSERT INTO users (id, login, display_name, role, password_hash) VALUES (?, 'max@gmx.at', 'Max Muster', 'VORSTAND', ?)", UUID.randomUUID(), Argon2.hash(password.toByteArray())) }
        assertEquals("/verwaltung", location(browser().form("/verwaltung/anmelden", "login" to "max@gmx.at", "passwort" to password)))
        assertContains(assertFailsWith<AccountProblem> { Accounts(ctx.db).create("neu@gmx.at", "Neu", Role.VORSTAND, password) }.message!!, "kein @")
        assertFailsWith<AccountProblem> { Accounts(ctx.db).create("a#b", "Neu", Role.VORSTAND, password) }

        // Mit nur einem Verein bleibt die Anmeldeseite, wie sie war.
        assertFalse(browser().page("/verwaltung/anmelden").contains("kassier@clunia"))
    }

    // --------------------------------------------------------- Systemverwaltung

    @Test
    fun `the main administrator is set up with the server key and signs in with name at system`() = serverTest(insecureCookies = true) { ctx ->
        val browser = browser()
        assertEquals("/verwaltung/system/einrichten", location(browser.get("/verwaltung/system")))
        assertContains(browser.form("/verwaltung/system/einrichten", "schluessel" to "geraten", "name" to "Jonas", "login" to "jonas", "passwort" to password, "passwort2" to password).bodyAsText(), "Verwaltungsschlüssel stimmt nicht")
        val done = browser.form("/verwaltung/system/einrichten", "schluessel" to ADMIN_TOKEN, "name" to "Jonas Prenn", "login" to "jonas", "passwort" to password, "passwort2" to password)
        assertEquals("/verwaltung/anmelden?weiter=%2Fverwaltung%2Fsystem", location(done))
        assertEquals("/verwaltung/system", location(browser.get("/verwaltung/system/einrichten")), "danach ist die Einrichtung zu")
        assertContains(browser.page("/verwaltung/anmelden?weiter=%2Fverwaltung%2Fsystem"), "name@system")

        assertContains(browser.form("/verwaltung/anmelden", "login" to "jonas", "passwort" to password).bodyAsText(), "stimmen nicht", message = "ohne @system ist es der erste Verein — und da gibt es keinen jonas")
        val signedIn = browser.signInSystem()
        assertEquals("/verwaltung/system", location(signedIn))
        val cookie = assertNotNull(signedIn.headers.getAll(HttpHeaders.SetCookie)?.firstOrNull { it.startsWith("vd_system=") })
        assertContains(cookie, "Path=/verwaltung/system")
        val page = browser.page("/verwaltung/system")
        for (text in listOf("Systemverwaltung", "Vereine", "Mindestversion der App", "Hauptadmins", "@verein · erster Verein")) assertContains(page, text)
        assertFalse(page.contains(" style=")); assertFalse(page.contains("<script"))
        assertEquals("/verwaltung/einrichten", location(browser.get("/verwaltung")), "eine Sitzung im System ist keine in einem Verein")

        assertEquals("/verwaltung/anmelden", location(browser.form("/verwaltung/system/abmelden", "_csrf" to csrfOf(page))))
        assertEquals("/verwaltung/anmelden?weiter=%2Fverwaltung%2Fsystem", location(browser.get("/verwaltung/system")))
        assertContains(ctx.directory.system!!.audit.recent(10).map { it.action }, "login")
    }

    // ------------------------------------------------------------- Vereine

    @Test
    fun `a new Verein has its own database, devices and logins`() = serverTest(insecureCookies = true) { ctx ->
        Accounts(ctx.db).create("lukas", "Lukas Hofer", Role.ADMIN, password)
        val theke = ctx.pairDevice("Theke")
        val maria = newId()
        ctx.push(theke.token, insertOp("members", buildJsonObject { put("id", maria); put("name", "Maria Bauer") }))

        // Was beim Anlegen nicht geht, sagt die Seite.
        ctx.directory.system!!.accounts.create("jonas", "Jonas Prenn", Role.ADMIN, password)
        val haupt = browser()
        haupt.signInSystem()
        val csrf = csrfOf(haupt.page("/verwaltung/system"))
        fun fields(kuerzel: String, repeat: String = password) = arrayOf("_csrf" to csrf, "teil" to "verein", "name" to "AV Austria", "kuerzel" to kuerzel, "admin_name" to "Otto Obmann", "admin_login" to "obmann", "passwort" to password, "passwort2" to repeat)
        assertContains(decoded(haupt.form("/verwaltung/system", *fields("system"))), "reserviert")
        assertContains(decoded(haupt.form("/verwaltung/system", *fields("verein"))), "schon vergeben")
        assertContains(decoded(haupt.form("/verwaltung/system", *fields("A!"))), "2 bis 30 Zeichen")
        assertContains(decoded(haupt.form("/verwaltung/system", *fields("austria", repeat = "etwas-anderes"))), "verschieden")
        assertEquals(1, ctx.directory.all().size, "nichts halb angelegt")

        val created = haupt.form("/verwaltung/system", *fields("austria"))
        assertContains(decoded(created), "Anmelden mit obmann@austria")
        val austria = assertNotNull(ctx.directory.bySlug("austria"))
        assertTrue(austria.info.dbName.endsWith("_austria"), austria.info.dbName)
        assertContains(haupt.page("/verwaltung/system"), "AV Austria")
        assertContains(austria.web.audit.recent(5).map { it.actor }, "Jonas Prenn (Hauptadmin)")

        // Anmelden mit dem Kürzel; ohne ist es der erste Verein, und da gibt es keinen obmann.
        val obmann = browser()
        assertContains(obmann.form("/verwaltung/anmelden", "login" to "obmann", "passwort" to password).bodyAsText(), "stimmen nicht")
        val signedIn = obmann.form("/verwaltung/anmelden", "login" to "obmann@austria", "passwort" to password)
        assertEquals("/verwaltung", location(signedIn))
        assertTrue(cookieValue(signedIn, "vd_session").startsWith("${austria.id}."))
        val overview = obmann.page("/verwaltung")
        assertContains(overview, "AV Austria")
        assertFalse(obmann.page("/verwaltung/mitglieder").contains("Maria Bauer"), "die Mitglieder des ersten Vereins sieht hier niemand")
        assertContains(browser().page("/verwaltung/anmelden"), "kassier@clunia", message = "mit zwei Vereinen sagt die Anmeldung, wie")

        // Ein Tablet koppelt mit einem Code des neuen Vereins — und landet dort, nur dort.
        val tablet = ctx.pairWith(austria, "Bude")
        val peter = newId()
        ctx.push(tablet.token, insertOp("members", buildJsonObject { put("id", peter); put("name", "Peter Gruber") }))
        assertEquals(listOf("Peter Gruber"), ctx.changes(tablet.token).names("members"))
        assertEquals(listOf("Maria Bauer"), ctx.changes(theke.token).names("members"))
        assertEquals(1, austria.db.transaction { c -> c.query("SELECT 1 FROM members") { 1 } }.size)
        assertEquals(austria.id, ctx.directory.system.route(UUID.fromString(tablet.deviceId)))
        assertContains(obmann.page("/verwaltung/mitglieder"), "Peter Gruber")
        assertContains(obmann.page("/verwaltung/geraete"), "Bude")
        assertFalse(browser().also { it.form("/verwaltung/anmelden", "login" to "lukas", "passwort" to password) }.page("/verwaltung/geraete").contains("Bude"))

        // Ein Cookie für einen Verein, den es nicht gibt, ist keine Sitzung — auch nicht im ersten.
        val forged = createClient { followRedirects = false }
        val stolen = cookieValue(signedIn, "vd_session").substringAfter('.')
        assertEquals(HttpStatusCode.Found, forged.get("/verwaltung") { header(HttpHeaders.Cookie, "vd_session=${UUID.randomUUID()}.$stolen") }.status)
        assertEquals(HttpStatusCode.Found, forged.get("/verwaltung") { header(HttpHeaders.Cookie, "vd_session=$stolen") }.status, "das Token gilt nur in seinem Verein")
    }

    @Test
    fun `devices from before the Vereine are found and remembered`() = serverTest { ctx ->
        // Gekoppelt am ersten Verein vorbei am Verzeichnis — so, wie Geräte von vor den Vereinen dastehen: ohne Weg.
        val (code, _) = ctx.directory.default.devices.createPairingCode()
        val old = ctx.directory.default.devices.register(code, "Alte Theke", "android")
        assertNull(ctx.directory.system!!.route(old.deviceId))
        assertEquals(HttpStatusCode.OK, ctx.client.get("/v1/sync/changes?since=0") { bearerAuth(old.token) }.status)
        assertEquals(ctx.directory.default.id, ctx.directory.system!!.route(old.deviceId), "beim ersten Abgleich gefunden und gemerkt")
        assertEquals(HttpStatusCode.Unauthorized, ctx.client.get("/v1/sync/changes?since=0") { bearerAuth(old.token.dropLast(4) + "AAAA") }.status)
    }

    // ---------------------------------------------------------- Mindestversion

    @Test
    fun `the minimum app version reaches every Verein's tablets once it is set, and only then`() = serverTest(insecureCookies = true) { ctx ->
        val austria = austria(ctx)
        val tablet = ctx.pairWith(austria, "Bude")
        assertNull(ctx.directory.default.minVersionRow(), "0.0.0 schreibt nichts: ein leerer Verein bleibt leer")
        assertNull(austria.minVersionRow())

        val haupt = browser()
        haupt.signInSystem()
        val csrf = csrfOf(haupt.page("/verwaltung/system"))
        assertContains(decoded(haupt.form("/verwaltung/system", "_csrf" to csrf, "teil" to "mindestversion", "version" to "1.3")), "drei Zahlen")
        assertContains(decoded(haupt.form("/verwaltung/system", "_csrf" to csrf, "teil" to "mindestversion", "version" to "1.3.0")), "unter 1.3.0")
        assertEquals("1.3.0", ctx.directory.minAppVersion)
        assertEquals("1.3.0", ctx.directory.default.minVersionRow()); assertEquals("1.3.0", austria.minVersionRow())
        val seen = ctx.changes(tablet.token).changes.filter { it.entity == "device_settings" }.associate { it.row["key"]!!.jsonPrimitive.content to it.row["value"]!!.jsonPrimitive.content }
        assertEquals("1.3.0", seen["min_app_version"], "das Tablet bekommt sie mit dem Abgleich")
        assertContains(haupt.page("/verwaltung/system"), "ab 1.3.0")

        // Ein Verein, der jetzt dazukommt, hat sie von Anfang an.
        val later = ctx.directory.create("Später", "spaeter", "Erika Erste", "erika", password)
        assertEquals("1.3.0", later.minVersionRow())

        // Zurück auf alle: Wer die Zeile hat, bekommt 0.0.0; wer nie eine hatte, bekommt keine.
        haupt.form("/verwaltung/system", "_csrf" to csrf, "teil" to "mindestversion", "version" to "0.0.0")
        assertEquals("0.0.0", austria.minVersionRow()); assertEquals("0.0.0", later.minVersionRow())
        val fresh = ctx.directory.create("Ganz neu", "neu", "Nina Neu", "nina", password)
        assertNull(fresh.minVersionRow())
        assertContains(ctx.directory.system!!.audit.recent(10).map { it.action }, "minversion.set")
    }

    @Test
    fun `the minimum version survives a restart and is given to every Verein again`() = serverTest(insecureCookies = true) { ctx ->
        val austria = austria(ctx)
        ctx.directory.setMinAppVersion("1.4.0")
        // Was der Server beim Start tut: Die Zeile, die fehlt (etwa nach einem Zurückspielen), kommt wieder.
        austria.db.write { c -> c.execute("UPDATE device_settings SET value = '0.0.0' WHERE key = 'min_app_version'") }
        val again = TenantDirectory.open(ctx.directory.config, ctx.db, TestPostgres.databases(austria.info.dbName.removeSuffix("_austria")))
        try {
            assertEquals(listOf("verein", "austria"), again.all().map { it.info.slug })
            assertEquals("1.4.0", again.minAppVersion)
            assertEquals("1.4.0", again.bySlug("austria")!!.minVersionRow())
        } finally {
            again.close()
        }
    }

    // ---------------------------------------------------------- Testanmeldung

    @Test
    fun `an administrator signs in as someone else to see what their role sees`() = serverTest(insecureCookies = true) { ctx ->
        val austria = austria(ctx)
        austria.web.accounts.create("kassa", "Karl Kassa", Role.KASSIER, "das-passwort-vom-kassier")

        // Der Administrator des Vereins, mit seinem eigenen Passwort.
        val test = browser()
        val signedIn = test.form("/verwaltung/anmelden", "login" to "obmann#kassa@austria", "passwort" to password)
        assertEquals("/verwaltung", location(signedIn))
        val page = test.page("/verwaltung")
        assertContains(page, "Testanmeldung: Otto Obmann sieht die Verwaltung als Karl Kassa (Kassier)")
        assertFalse(page.contains("Benutzer und Rollen"), "die Navigation des Kassiers")
        assertEquals(HttpStatusCode.Forbidden, test.get("/verwaltung/benutzer").status)
        val entry = austria.web.audit.recent(5).first { it.action == "login.test" }
        assertEquals("Karl Kassa (über Otto Obmann)", entry.actor)
        assertNull(austria.web.accounts.findByLogin("kassa")!!.lastLoginAt, "angemeldet hat sich nicht der Kassier")

        // Was in der Sitzung geschieht, nennt beide.
        val members = test.page("/verwaltung/mitglieder")
        test.form("/verwaltung/mitglieder", "_csrf" to csrfOf(members), "name" to "Paul Probe", "vulgo" to "")
        assertContains(austria.web.audit.recent(5).filter { it.action == "member.create" }.map { it.actor }, "Karl Kassa (über Otto Obmann)")

        // Nur ein Administrator darf das — und nur mit seinem Passwort.
        assertContains(browser().form("/verwaltung/anmelden", "login" to "kassa#obmann@austria", "passwort" to "das-passwort-vom-kassier").bodyAsText(), "stimmen nicht")
        assertContains(browser().form("/verwaltung/anmelden", "login" to "obmann#kassa@austria", "passwort" to "das-passwort-vom-kassier").bodyAsText(), "stimmen nicht")
        assertContains(browser().form("/verwaltung/anmelden", "login" to "obmann#niemand@austria", "passwort" to password).bodyAsText(), "stimmen nicht")

        // Der Hauptadmin darf es in jedem Verein; das System merkt es sich auch.
        val haupt = browser()
        assertEquals("/verwaltung", location(haupt.form("/verwaltung/anmelden", "login" to "jonas#kassa@austria", "passwort" to password)))
        assertContains(haupt.page("/verwaltung"), "Testanmeldung: Jonas Prenn, Hauptadmin sieht die Verwaltung als Karl Kassa")
        val support = ctx.directory.system!!.audit.recent(5).first { it.action == "support.login" }
        assertEquals("kassa@austria", support.subject); assertEquals("Jonas Prenn", support.actor)
        assertContains(browser().form("/verwaltung/anmelden", "login" to "jonas#kassa@austria", "passwort" to "falsch-falsch-falsch").bodyAsText(), "stimmen nicht")

        // Ein Anmeldename von vor den Vereinen mit # darin bleibt ein Anmeldename.
        ctx.db.transaction { c -> c.execute("INSERT INTO users (id, login, display_name, role, password_hash) VALUES (?, 'alt#name', 'Alter Name', 'VORSTAND', ?)", UUID.randomUUID(), Argon2.hash(password.toByteArray())) }
        val legacy = browser()
        assertEquals("/verwaltung", location(legacy.form("/verwaltung/anmelden", "login" to "alt#name", "passwort" to password)))
        assertFalse(legacy.page("/verwaltung").contains("Testanmeldung"))
    }

    // ------------------------------------------------------------- Kürzel ändern

    @Test
    fun `a Verein chooses its own Kürzel, unique on the server`() = serverTest(insecureCookies = true) { ctx ->
        val austria = austria(ctx)
        austria.web.accounts.create("kassa", "Karl Kassa", Role.KASSIER, password)
        val obmann = browser()
        obmann.form("/verwaltung/anmelden", "login" to "obmann@austria", "passwort" to password)
        val settings = obmann.page("/verwaltung/einstellungen")
        assertContains(settings, "Kürzel des Vereins"); assertContains(settings, "obmann@austria")
        assertContains(obmann.page("/verwaltung/benutzer"), "Anmeldename@austria")

        assertContains(decoded(obmann.form("/verwaltung/einstellungen", "_csrf" to csrfOf(settings), "teil" to "kuerzel", "kuerzel" to "verein")), "schon vergeben")
        assertContains(decoded(obmann.form("/verwaltung/einstellungen", "_csrf" to csrfOf(settings), "teil" to "kuerzel", "kuerzel" to "system")), "reserviert")
        assertContains(decoded(obmann.form("/verwaltung/einstellungen", "_csrf" to csrfOf(settings), "teil" to "kuerzel", "kuerzel" to "AV-Austria")), "Gespeichert")
        assertEquals("av-austria", austria.info.slug)
        assertEquals("av-austria", ctx.directory.system!!.tenants().first { it.id == austria.id }.slug)
        assertEquals("/verwaltung", location(browser().form("/verwaltung/anmelden", "login" to "kassa@av-austria", "passwort" to password)))
        assertContains(browser().form("/verwaltung/anmelden", "login" to "kassa@austria", "passwort" to password).bodyAsText(), "stimmen nicht")
        assertContains(austria.web.audit.recent(5).map { it.detail }, "Kürzel: austria → av-austria")

        // Der Name aus den Einstellungen ist auch der in der Systemverwaltung.
        obmann.form("/verwaltung/einstellungen", "_csrf" to csrfOf(settings), "name" to "AV Austria Innsbruck", "farbe" to "#1565C0", "monat" to "1", "anschrift" to "")
        assertEquals("AV Austria Innsbruck", austria.info.name)

        // Das Kürzel ist Sache des Administrators.
        val kassa = browser()
        kassa.form("/verwaltung/anmelden", "login" to "kassa@av-austria", "passwort" to password)
        val kassaSettings = kassa.page("/verwaltung/einstellungen")
        assertFalse(kassaSettings.contains("Kürzel des Vereins"))
        assertEquals(HttpStatusCode.Forbidden, kassa.form("/verwaltung/einstellungen", "_csrf" to csrfOf(kassaSettings), "teil" to "kuerzel", "kuerzel" to "weg").status)
    }

    // ----------------------------------------------------- ohne Systemdatenbank

    @Test
    fun `without a system database the first Verein runs alone, as before`() {
        val dir = Files.createTempDirectory("vd-updates")
        serverTest(insecureCookies = true, updatesDir = dir, withSystem = false) { ctx ->
            assertNull(ctx.directory.system)
            assertContains(ctx.directory.systemProblem.orEmpty(), "permission denied")
            val unavailable = browser().get("/verwaltung/system")
            assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status)
            assertContains(unavailable.bodyAsText(), "permission denied")

            Accounts(ctx.db).create("admin", "Anna Admin", Role.ADMIN, password)
            val admin = browser()
            assertEquals("/verwaltung", location(admin.form("/verwaltung/anmelden", "login" to "admin", "passwort" to password)))
            val settings = admin.page("/verwaltung/einstellungen")
            assertContains(settings, "Jetzt suchen", message = "die Updates bleiben beim Administrator, solange es kein System gibt")
            assertFalse(settings.contains("Kürzel des Vereins"))
            admin.form("/verwaltung/einstellungen", "_csrf" to csrfOf(settings), "teil" to "update", "aktion" to "pruefen")
            assertEquals("check\n", Files.readString(dir.resolve("request")))

            val tablet = ctx.pairDevice("Theke")
            assertEquals(HttpStatusCode.OK, ctx.client.get("/v1/sync/changes?since=0") { bearerAuth(tablet.token) }.status)
            assertContains(browser().form("/verwaltung/anmelden", "login" to "admin@system", "passwort" to password).bodyAsText(), "stimmen nicht")
            assertFailsWith<AccountProblem> { ctx.directory.setMinAppVersion("1.3.0") }
        }
    }
}
