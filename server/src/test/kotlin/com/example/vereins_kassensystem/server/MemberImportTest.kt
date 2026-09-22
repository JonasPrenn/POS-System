package com.example.vereins_kassensystem.server

import com.example.vereins_kassensystem.server.web.AccountProblem
import com.example.vereins_kassensystem.server.web.Accounts
import com.example.vereins_kassensystem.server.web.MemberCsv
import com.example.vereins_kassensystem.server.web.Role
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
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/** Mitglieder als CSV herein und heraus (Konzept 4.1): Vorschau, keine Doppelten, Profile ergänzt. */
class MemberImportTest {

    private val password = "ein-langes-passwort"
    private fun ApplicationTestBuilder.browser(): HttpClient = createClient { install(HttpCookies); followRedirects = false }
    private suspend fun HttpClient.form(path: String, vararg fields: Pair<String, String>): HttpResponse = submitForm(path, parameters { fields.forEach { (k, v) -> append(k, v) } })
    private fun csrfOf(html: String): String = assertNotNull(Regex("""name="_csrf" value="([^"]+)"""").find(html)).groupValues[1]
    private suspend fun HttpClient.page(path: String): String { val r = get(path); assertEquals(HttpStatusCode.OK, r.status, path); return r.bodyAsText() }
    private fun location(r: HttpResponse) = assertNotNull(r.headers[HttpHeaders.Location])
    private suspend fun HttpClient.upload(csrf: String, csv: ByteArray): HttpResponse = submitFormWithBinaryData("/verwaltung/mitglieder/import", formData {
        append("_csrf", csrf)
        append("datei", csv, Headers.build { append(HttpHeaders.ContentType, "text/csv"); append(HttpHeaders.ContentDisposition, "filename=\"mitglieder.csv\"") })
    })

    @Test
    fun `csv is read with any delimiter, quotes, a bom and windows encoding`() {
        val tablet = MemberCsv.parse("Name;Mitgliedergruppe\nAnna Berger;Aktive\n".toByteArray())
        assertEquals(';', tablet.delimiter); assertEquals("Anna Berger", tablet.rows.single().name); assertEquals("Aktive", tablet.rows.single().category); assertEquals(null, tablet.rows.single().consent)
        val excel = MemberCsv.parse("﻿Name,Couleurname,E-Mail,Anschrift,Einwilligung E-Mail\n\"Lechner, Georg\",Nestor,g@example.at,\"Beispielgasse 1\n6800 Feldkirch\",ja\n".toByteArray())
        assertEquals(',', excel.delimiter); assertEquals("Lechner, Georg", excel.rows.single().name); assertEquals("Beispielgasse 1\n6800 Feldkirch", excel.rows.single().address); assertEquals(true, excel.rows.single().consent)
        val windows = MemberCsv.parse("Name;Kategorie\r\nJörg Müller;Alte Herren\r\n".toByteArray(Charset.forName("windows-1252")))
        assertEquals("Windows-1252", windows.charset); assertEquals("Jörg Müller", windows.rows.single().name)
        assertFailsWith<AccountProblem> { MemberCsv.parse("Vorname;Nachname\nAnna;Berger\n".toByteArray()) }
        assertFailsWith<AccountProblem> { MemberCsv.parse(ByteArray(0)) }
    }

    @Test
    fun `the import previews, creates only the new ones with their categories and profiles, and never twice`() = serverTest(insecureCookies = true) { ctx ->
        val device = ctx.pairDevice("Theke links")
        val aktive = newId(); val jonas = newId()
        ctx.push(
            device.token,
            insertOp("member_categories", buildJsonObject { put("id", aktive); put("name", "Aktive"); put("negative_balance_limit", "-20.00") }),
            insertOp("members", buildJsonObject { put("id", jonas); put("name", "Jonas Prenn"); put("category_id", aktive) }),
        )
        val since = ctx.client.get("/v1/sync/changes?since=0") { bearerAuth(device.token) }.body<ChangesResponse>().nextSince
        Accounts(ctx.db).create("lukas", "Lukas Hofer", Role.KASSIER, password)
        Accounts(ctx.db).create("pruefer", "Paul Prüfer", Role.VORSTAND, password)
        val kassier = browser()
        kassier.form("/verwaltung/anmelden", "login" to "lukas", "passwort" to password)
        val page = kassier.page("/verwaltung/mitglieder")
        assertContains(page, "Importieren"); assertContains(page, "mitglieder.csv")

        val csv = """
            Name;Mitgliedergruppe;Couleurname;E-Mail;Einwilligung E-Mail
            Jonas Prenn;Aktive;Sokrates;jonas@example.at;ja
            Anna Berger;Aktive;;anna@example.at;ja
            Dr. Georg Lechner;Alte Herren;Nestor;;
            ;Aktive;;;
            Anna Berger;Aktive;;;
            Max Muster;Aktive;;keine-adresse;
        """.trimIndent() + "\n"
        // Die Vorschau: zwei neu, eine Kategorie neu, einer schon da (und wird ergänzt), drei übersprungen — angelegt ist noch nichts.
        val preview = kassier.upload(csrfOf(page), csv.toByteArray())
        assertEquals(HttpStatusCode.OK, preview.status)
        val html = preview.bodyAsText()
        assertContains(html, "2 Mitglieder neu"); assertContains(html, "1 Kategorie neu (Alte Herren"); assertContains(html, "1 gibt es schon"); assertContains(html, "3 übersprungen")
        assertContains(html, "ergänzt: Couleurname, E-Mail"); assertContains(html, "kein Name"); assertContains(html, "weiter oben schon einmal"); assertContains(html, "keine E-Mail-Adresse")
        assertContains(html, "2 Mitglieder anlegen und Profile ergänzen")
        assertFalse(kassier.page("/verwaltung/mitglieder").contains("Anna Berger"))

        // Anlegen: Mitglieder und Kategorie gehen zu den Tablets, die Profile bleiben am Server, Jonas bekommt Couleurname und E-Mail.
        val done = kassier.form("/verwaltung/mitglieder/import/anlegen", "_csrf" to csrfOf(page), "daten" to csv)
        assertContains(location(done), "2%20Mitglieder%20angelegt")
        val list = kassier.page("/verwaltung/mitglieder")
        assertContains(list, "Anna Berger"); assertContains(list, "Dr. Georg Lechner v. Nestor"); assertContains(list, "Jonas Prenn v. Sokrates")
        assertContains(kassier.page("/verwaltung/mitglieder/kategorien"), "Alte Herren")
        val pulled = ctx.client.get("/v1/sync/changes?since=$since") { bearerAuth(device.token) }.body<ChangesResponse>()
        assertEquals(2, pulled.changes.count { it.entity == "members" && it.row["name"].toString().contains("Berger") || it.entity == "members" && it.row["name"].toString().contains("Lechner") })
        assertEquals(1, pulled.changes.count { it.entity == "member_categories" })
        assertContains(kassier.page("/verwaltung/protokoll"), "Mitglieder importiert")

        // Noch einmal dieselbe Datei: nichts zu tun, nichts doppelt.
        val again = kassier.upload(csrfOf(page), csv.toByteArray()).bodyAsText()
        assertContains(again, "0 Mitglieder neu"); assertContains(again, "Nichts zu tun")
        kassier.form("/verwaltung/mitglieder/import/anlegen", "_csrf" to csrfOf(page), "daten" to csv)
        assertEquals(3, ctx.client.get("/v1/sync/changes?since=0") { bearerAuth(device.token) }.body<ChangesResponse>().changes.count { it.entity == "members" })

        // Hinaus: dieselben Spalten, mit dem, was am Profil steht — und nur für den Kassier.
        val export = kassier.get("/verwaltung/mitglieder.csv")
        assertEquals(HttpStatusCode.OK, export.status)
        val text = export.bodyAsText()
        assertContains(text, "Name;Mitgliedergruppe;Couleurname;Mitgliedsnummer;E-Mail;Anschrift;Einwilligung E-Mail")
        assertContains(text, "Anna Berger;Aktive;;;anna@example.at;;ja"); assertContains(text, "Jonas Prenn;Aktive;Sokrates;;jonas@example.at;;ja"); assertContains(text, "Dr. Georg Lechner;Alte Herren;Nestor;;;;nein")
        val vorstand = browser()
        vorstand.form("/verwaltung/anmelden", "login" to "pruefer", "passwort" to password)
        assertEquals(HttpStatusCode.Forbidden, vorstand.get("/verwaltung/mitglieder.csv").status)
        assertEquals(HttpStatusCode.Forbidden, vorstand.upload(csrfOf(vorstand.page("/verwaltung/mitglieder")), csv.toByteArray()).status)
    }
}
