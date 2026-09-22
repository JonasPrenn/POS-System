package com.example.vereins_kassensystem.server.web

import com.example.vereins_kassensystem.server.ServerConfig
import com.example.vereins_kassensystem.server.db.Database
import com.example.vereins_kassensystem.server.devices.DeviceStore
import com.example.vereins_kassensystem.server.devices.Tokens
import com.example.vereins_kassensystem.server.media.ReceiptStore
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.content.CachingOptions
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.html.respondHtml
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.html.HTML
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Was die Seiten brauchen, an einer Stelle. */
class Web(val config: ServerConfig, db: Database, val devices: DeviceStore, val receipts: ReceiptStore, val mailer: Mailer = SmtpMailer, val mailbox: Mailbox = ImapMailbox) {
    val accounts = Accounts(db) { LocalDate.now(config.zone) }
    val audit = AuditLog(db)
    val settings = VereinSettings(db)
    val reads = Reads(db, config.zone)
    val writes = Writes(db)
    val purchases = Purchases(db, config.zone)
    val statements = Statements(db, writes, config.zone)
    val products = Products(db)
    val books = Books(db, config.zone)
    val intake = MailIntake(db, receipts, purchases, settings, mailbox, config.zone)
    val cash = Cash(db, config.zone)
}

private const val COOKIE = "vd_session"

/**
 * Streng, weil es geht: Die Oberfläche kommt ohne Skripte und ohne Inline-Styles aus. Damit
 * bleibt auch ein Mitgliedsname, der wie HTML aussieht, nur ein Name.
 */
private const val CSP = "default-src 'none'; style-src 'self'; img-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"

private val SecurityHeaders = createRouteScopedPlugin("VerwaltungHeaders") {
    onCall { call ->
        call.response.header("Content-Security-Policy", CSP)
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "same-origin")
        call.response.header("X-Frame-Options", "DENY")
    }
}

fun Route.webRoutes(web: Web) {
    get("/") { call.respondRedirect(BASE) }
    route(BASE) {
        install(SecurityHeaders)
        assets(web)
        gate(web)
        mainPages(web)
        warePages(web)
        statementPages(web)
        cashPages(web)
        productPages(web)
        bookPages(web)
        systemPages(web)
    }
}

// ------------------------------------------------------------------ Zugang

internal suspend fun ApplicationCall.html(status: HttpStatusCode = HttpStatusCode.OK, block: HTML.() -> Unit) {
    response.header("Cache-Control", "no-store")
    respondHtml(status, block)
}

private fun ApplicationCall.sessionOf(web: Web): WebSession? =
    request.cookies[COOKIE]?.takeIf { it.isNotBlank() }?.let(web.accounts::session)

internal fun Web.contextOf(session: WebSession) = PageContext(session, settings.load(), config.zone, Instant.now())

/**
 * Jede Seite der Verwaltung geht hier durch: eingerichtet? angemeldet? darf die Rolle das?
 * Wer nicht darf, bekommt eine Seite, die das sagt — kein stilles Umleiten, bei dem man
 * rätselt, wo der Menüpunkt geblieben ist.
 */
internal suspend fun ApplicationCall.guarded(web: Web, area: Area?, block: suspend (PageContext) -> Unit) {
    if (!web.accounts.anyUser()) return respondRedirect("$BASE/einrichten")
    val session = sessionOf(web) ?: return respondRedirect("$BASE/anmelden?weiter=${request.uri.encodeURLParameter()}")
    val ctx = web.contextOf(session)
    if (area != null && !ctx.user.role.may(area)) return forbidden(ctx, "Dieser Bereich gehört nicht zur Rolle „${ctx.user.role.label}“.")
    block(ctx)
}

/** Wie [guarded], für Formulare: Ohne das Geheimnis der Sitzung wird nichts ausgeführt. */
internal suspend fun ApplicationCall.guardedPost(web: Web, area: Area?, block: suspend (PageContext, Parameters) -> Unit) = guarded(web, area) { ctx ->
    val form = receiveParameters()
    if (!Tokens.constantTimeEquals(form["_csrf"].orEmpty(), ctx.session.csrf)) {
        return@guarded forbidden(ctx, "Das Formular ist abgelaufen. Bitte die Seite neu laden und noch einmal versuchen.")
    }
    block(ctx, form)
}

internal suspend fun ApplicationCall.forbidden(ctx: PageContext, message: String) = html(HttpStatusCode.Forbidden) {
    shell(ctx, null, "Kein Zugriff", message) { }
}

internal fun uuidOrNull(text: String?): UUID? = text?.let { runCatching { UUID.fromString(it) }.getOrNull() }

/** Nur Ziele innerhalb der Verwaltung — sonst wäre die Anmeldung ein offener Weiterleiter. */
private fun safeTarget(target: String?, fallback: String): String =
    target?.takeIf { it.startsWith("$BASE/") && !it.startsWith("//") && "://" !in it && '\\' !in it } ?: fallback

private fun Route.gate(web: Web) {
    get("/einrichten") {
        if (web.accounts.anyUser()) return@get call.respondRedirect("$BASE/anmelden")
        call.html { setupPage(null) }
    }
    rateLimit(RateLimitName("login")) {
        post("/einrichten") {
            if (web.accounts.anyUser()) return@post call.respondRedirect("$BASE/anmelden")
            val form = call.receiveParameters()
            val problem = when {
                !Tokens.constantTimeEquals(form["schluessel"].orEmpty().trim(), web.config.pairingAdminToken) ->
                    "Der Verwaltungsschlüssel stimmt nicht. Er steht als PAIRING_ADMIN_TOKEN in der .env des Servers."
                form["passwort"] != form["passwort2"] -> "Die beiden Passwörter sind verschieden."
                else -> try {
                    val user = web.accounts.create(form["login"].orEmpty(), form["name"].orEmpty(), Role.ADMIN, form["passwort"].orEmpty())
                    web.audit.record(user, "user.create", user.displayName, "Ersteinrichtung, Rolle ${Role.ADMIN.label}")
                    null
                } catch (e: AccountProblem) {
                    e.message
                }
            }
            if (problem != null) return@post call.html { setupPage(problem, form["name"].orEmpty(), form["login"].orEmpty()) }
            call.respondRedirect("$BASE/anmelden?neu=1")
        }

        post("/anmelden") {
            val form = call.receiveParameters()
            val login = form["login"].orEmpty()
            val result = web.accounts.login(login, form["passwort"].orEmpty())
            if (result == null) {
                web.audit.record(null, "login.failed", login.take(80))
                return@post call.html { loginPage("Anmeldename oder Passwort stimmen nicht — oder der Zugang ist abgelaufen.", login, form["weiter"]) }
            }
            val (token, session) = result
            web.audit.record(session.user, "login", session.user.displayName)
            call.response.cookies.append(
                Cookie(
                    name = COOKIE, value = token, encoding = CookieEncoding.RAW, path = BASE, httpOnly = true, secure = !web.config.insecureCookies,
                    maxAge = Accounts.MAX_AGE.seconds.toInt(), extensions = mapOf("SameSite" to "Lax")
                )
            )
            call.respondRedirect(safeTarget(form["weiter"], homeOf(session.user.role)))
        }
    }
    get("/anmelden") {
        if (!web.accounts.anyUser()) return@get call.respondRedirect("$BASE/einrichten")
        if (call.sessionOf(web) != null) return@get call.respondRedirect(BASE)
        val notice = if (call.request.queryParameters["neu"] == "1") "Eingerichtet. Jetzt mit dem neuen Zugang anmelden." else null
        call.html { loginPage(null, "", call.request.queryParameters["weiter"], notice) }
    }
    post("/abmelden") {
        call.guardedPost(web, null) { _, _ ->
            call.request.cookies[COOKIE]?.let(web.accounts::logout)
            call.response.cookies.append(Cookie(name = COOKIE, value = "", path = BASE, httpOnly = true, maxAge = 0))
            call.respondRedirect("$BASE/anmelden")
        }
    }
}

// ------------------------------------------------------------------ Dateien

private fun Route.assets(web: Web) {
    val css = Stylesheet.css
    val oneHour = CachingOptions(CacheControl.MaxAge(3600))

    get("/assets/app.css") {
        call.response.header("Cache-Control", oneHour.cacheControl.toString())
        call.respondText(css, ContentType.Text.CSS)
    }
    // Die Vereinsfarbe als eigenes Blatt: So bleibt die Content-Security-Policy ohne 'unsafe-inline'.
    get("/assets/verein.css") {
        val accent = web.settings.load().accent
        call.response.header("Cache-Control", "no-cache")
        call.respondText(":root { --accent: $accent; --on-accent: ${VereinSettings.readableOn(accent)}; }\n", ContentType.Text.CSS)
    }
    get("/assets/icon.svg") {
        call.response.header("Cache-Control", oneHour.cacheControl.toString())
        call.respondText(
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><rect width="24" height="24" rx="6" fill="#146B4C"/><g fill="none" stroke="#fff" stroke-width="1.6" stroke-linecap="round"><path d="M7.5 8v8M10.5 8v8M13.5 8v8M16.5 8v8M6 14.8l12-5.6"/></g></svg>""",
            ContentType.Image.SVG
        )
    }
    // Belegfotos: dieselben Dateien wie für die Geräte, hier hinter der Anmeldung statt hinter dem Gerätetoken.
    get("/einkauf/foto/{key}") { call.respondRedirect("$BASE/einkauf/datei/${call.parameters["key"].orEmpty()}") }
    get("/einkauf/foto-alt/{key}") {
        call.guarded(web, Area.PURCHASES) {
            val key = call.parameters["key"].orEmpty()
            val stored = web.receipts.find(key)?.takeIf { web.reads.photoKeyExists(key) }
                ?: return@guarded call.respondText("Kein Foto unter diesem Schlüssel.", status = HttpStatusCode.NotFound)
            call.response.header("Cache-Control", "private, max-age=3600")
            call.respond(LocalFileContent(stored.path.toFile(), stored.contentType))
        }
    }
}
