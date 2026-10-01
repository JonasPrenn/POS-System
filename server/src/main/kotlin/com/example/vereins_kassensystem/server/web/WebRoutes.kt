package com.example.vereins_kassensystem.server.web

import com.example.vereins_kassensystem.server.ServerConfig
import com.example.vereins_kassensystem.server.db.Database
import com.example.vereins_kassensystem.server.devices.DeviceStore
import com.example.vereins_kassensystem.server.devices.Tokens
import com.example.vereins_kassensystem.server.media.ReceiptStore
import com.example.vereins_kassensystem.server.payments.OnlinePayments
import com.example.vereins_kassensystem.server.tenancy.Kuerzel
import com.example.vereins_kassensystem.server.tenancy.Tenant
import com.example.vereins_kassensystem.server.tenancy.TenantContext
import com.example.vereins_kassensystem.server.tenancy.TenantDirectory
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.content.CachingOptions
import io.ktor.http.encodeURLParameter
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
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
import kotlinx.coroutines.withContext
import kotlinx.html.HTML
import kotlinx.html.html
import kotlinx.html.stream.appendHTML
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Was die Seiten brauchen, an einer Stelle. Je Verein gibt es einen Satz davon ([TenantWeb]); die
 * Routen arbeiten mit [CurrentWeb], der jede Frage an den Verein der laufenden Anfrage weitergibt.
 * So bleiben die Seiten, wie sie waren — `web.reads`, `web.accounts` — und wissen nichts von Vereinen.
 */
interface Web {
    val config: ServerConfig
    val tenant: Tenant
    val directory: TenantDirectory get() = tenant.directory
    val devices: DeviceStore
    val receipts: ReceiptStore
    val mailer: Mailer
    val mailbox: Mailbox
    val accounts: Accounts
    val audit: AuditLog
    val settings: VereinSettings
    val reads: Reads
    val writes: Writes
    val purchases: Purchases
    val statements: Statements
    val products: Products
    val books: Books
    val intake: MailIntake
    val cash: Cash
    /** Der Deckel für Mitglieder: Anmeldelinks und Online-Aufladung (/konto). */
    val portal: Portal
    /** Für den ganzen Server, nicht je Verein — bedient wird er in der Systemverwaltung. */
    val updates: Updates
}

class TenantWeb(
    override val config: ServerConfig, db: Database, override val devices: DeviceStore, override val receipts: ReceiptStore,
    override val mailer: Mailer, override val mailbox: Mailbox, override val updates: Updates, override val tenant: Tenant,
    payments: OnlinePayments,
) : Web {
    override val accounts = Accounts(db) { LocalDate.now(config.zone) }
    override val audit = AuditLog(db)
    // Ein neuer Verein heißt, wie der Hauptadmin ihn angelegt hat, bis er selbst einen Namen speichert. Der erste
    // bleibt ohne Namen ohne — sonst stünde „Verein“ als Empfänger im Zahlschein-QR-Code.
    override val settings = VereinSettings(db) { if (tenant.info.isDefault) "" else tenant.info.name }
    override val reads = Reads(db, config.zone)
    override val writes = Writes(db)
    override val purchases = Purchases(db, config.zone)
    override val statements = Statements(db, writes, config.zone)
    override val products = Products(db)
    override val books = Books(db, config.zone)
    override val intake = MailIntake(db, receipts, purchases, settings, mailbox, config.zone)
    override val cash = Cash(db, config.zone)
    override val portal = Portal(db, settings, reads, writes, mailer, payments)

    init {
        // Was die Verwaltung schon weiß, steht beim Start auch für die Tablets bereit.
        settings.mirrorDeviceSettings()
    }
}

/** Der Verein der laufenden Anfrage, gesetzt von [VereinOfCall] aus dem Sitzungscookie. */
class CurrentWeb(override val config: ServerConfig, override val updates: Updates) : Web {
    private val current: TenantWeb get() = checkNotNull(TenantContext.current()) { "Anfrage ohne Verein" }.web
    override val tenant get() = current.tenant
    override val devices get() = current.devices
    override val receipts get() = current.receipts
    override val mailer get() = current.mailer
    override val mailbox get() = current.mailbox
    override val accounts get() = current.accounts
    override val audit get() = current.audit
    override val settings get() = current.settings
    override val reads get() = current.reads
    override val writes get() = current.writes
    override val purchases get() = current.purchases
    override val statements get() = current.statements
    override val products get() = current.products
    override val books get() = current.books
    override val intake get() = current.intake
    override val cash get() = current.cash
    override val portal get() = current.portal
}

internal const val COOKIE = "vd_session"

/**
 * Im Cookie steht das Sitzungstoken, bei einem weiteren Verein dessen ID davor: `<id>.<token>`.
 * Der erste Verein schreibt es wie vor den Vereinen — Sitzungen überstehen das Update, und ein
 * Zurückgehen auf den alten Stand auch.
 */
internal object SessionCookie {
    fun value(tenant: Tenant, token: String) = if (tenant.info.isDefault) token else "${tenant.id}.$token"
    fun tenantId(value: String): UUID? = value.substringBefore('.', "").takeIf { it.isNotEmpty() }?.let(::uuidOrNull)
    fun token(value: String): String = value.substringAfter('.')
}

internal fun sessionCookie(name: String, value: String, path: String, config: ServerConfig) = Cookie(
    name = name, value = value, encoding = CookieEncoding.RAW, path = path, httpOnly = true, secure = !config.insecureCookies,
    maxAge = Accounts.MAX_AGE.seconds.toInt(), extensions = mapOf("SameSite" to "Lax")
)

/**
 * Streng, weil es geht: Die Oberfläche kommt ohne Skripte und ohne Inline-Styles aus. Damit
 * bleibt auch ein Mitgliedsname, der wie HTML aussieht, nur ein Name.
 */
private const val CSP = "default-src 'none'; style-src 'self'; img-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"

internal val SecurityHeaders = createRouteScopedPlugin("VerwaltungHeaders") {
    onCall { call ->
        call.response.header("Content-Security-Policy", CSP)
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "same-origin")
        call.response.header("X-Frame-Options", "DENY")
    }
}

/** Ein Haken um den Rest der Anfrage — wie CallLogging ihn für den MDC nimmt. */
private object AroundCall : Hook<suspend (ApplicationCall, suspend () -> Unit) -> Unit> {
    override fun install(pipeline: ApplicationCallPipeline, handler: suspend (ApplicationCall, suspend () -> Unit) -> Unit) {
        pipeline.intercept(ApplicationCallPipeline.Plugins) { handler(context) { proceed() } }
    }
}

internal class VereinOfCallConfig { lateinit var directory: TenantDirectory }

/**
 * Welcher Verein? Der des Sitzungscookies; ohne Cookie, oder für einen Verein, den es nicht
 * (mehr) gibt, der erste. Die ganze Anfrage läuft dann mit ihm ([TenantContext]).
 */
private val VereinOfCall = createRouteScopedPlugin("Verein", ::VereinOfCallConfig) {
    val directory = pluginConfig.directory
    on(AroundCall) { call, proceed ->
        val tenant = call.request.cookies[COOKIE]?.let(SessionCookie::tenantId)?.let(directory::byId)?.takeIf { it.info.active } ?: directory.default
        withContext(TenantContext.element(tenant)) { proceed() }
    }
}

fun Route.webRoutes(directory: TenantDirectory) {
    val web = CurrentWeb(directory.config, directory.updates)
    get("/") { call.respondRedirect(BASE) }
    route(BASE) {
        install(SecurityHeaders)
        install(VereinOfCall) { this.directory = directory }
        assets(web)
        gate(web, directory)
        serverPages(directory)
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

/** Die Seite fertig gesetzt, bevor die Antwort beginnt — in der Koroutine der Anfrage, mit ihrem Verein. */
internal suspend fun ApplicationCall.html(status: HttpStatusCode = HttpStatusCode.OK, block: HTML.() -> Unit) {
    response.header("Cache-Control", "no-store")
    val page = buildString {
        append("<!DOCTYPE html>\n")
        appendHTML().html(block = block)
    }
    respondText(page, ContentType.Text.Html.withCharset(Charsets.UTF_8), status)
}

/** Die Sitzung zum Cookie — nur im Verein, für den das Cookie ausgestellt ist. */
private fun ApplicationCall.sessionOf(web: Web): WebSession? {
    val value = request.cookies[COOKIE]?.takeIf { it.isNotBlank() } ?: return null
    val expected = if (web.tenant.info.isDefault) null else web.tenant.id
    if (SessionCookie.tenantId(value) != expected) return null
    return web.accounts.session(SessionCookie.token(value))
}

internal fun Web.contextOf(session: WebSession) = PageContext(session, settings.load(), config.zone, Instant.now(), tenant.info, directory.system != null)

/**
 * Jede Seite der Verwaltung geht hier durch: eingerichtet? angemeldet? darf die Rolle das?
 * Wer nicht darf, bekommt eine Seite, die das sagt — kein stilles Umleiten, bei dem man
 * rätselt, wo der Menüpunkt geblieben ist.
 */
internal suspend fun ApplicationCall.guarded(web: Web, area: Area?, block: suspend (PageContext) -> Unit) {
    val session = sessionOf(web)
        ?: return respondRedirect(if (web.directory.needsSetup()) "$BASE/einrichten" else "$BASE/anmelden?weiter=${request.uri.encodeURLParameter()}")
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
internal fun safeTarget(target: String?, fallback: String, under: String = BASE): String =
    target?.takeIf { (it == under || it.startsWith("$under/")) && !it.startsWith("//") && "://" !in it && '\\' !in it } ?: fallback

private fun Route.gate(web: Web, directory: TenantDirectory) {
    val signIn = SignIn(directory)
    get("/einrichten") {
        if (directory.default.web.accounts.anyUser()) return@get call.respondRedirect("$BASE/anmelden")
        call.html { setupPage(null) }
    }
    rateLimit(RateLimitName("login")) {
        post("/einrichten") {
            // Die Einrichtung gilt dem ersten Verein; weitere legt der Hauptadmin an, mit ihrem ersten Administrator.
            val first = directory.default.web
            if (first.accounts.anyUser()) return@post call.respondRedirect("$BASE/anmelden")
            val form = call.receiveParameters()
            val problem = when {
                !Tokens.constantTimeEquals(form["schluessel"].orEmpty().trim(), web.config.pairingAdminToken) ->
                    "Der Verwaltungsschlüssel stimmt nicht. Er steht als PAIRING_ADMIN_TOKEN in der .env des Servers."
                form["passwort"] != form["passwort2"] -> "Die beiden Passwörter sind verschieden."
                else -> try {
                    val user = first.accounts.create(form["login"].orEmpty(), form["name"].orEmpty(), Role.ADMIN, form["passwort"].orEmpty())
                    first.audit.record(user, "user.create", user.displayName, "Ersteinrichtung, Rolle ${Role.ADMIN.label}")
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
            when (val outcome = signIn.attempt(login, form["passwort"].orEmpty())) {
                is SignIn.Failed -> {
                    (outcome.tenant?.web?.audit ?: directory.system?.audit)?.record(null, "login.failed", login.take(80))
                    call.html { loginPage("Anmeldename oder Passwort stimmen nicht — oder der Zugang ist abgelaufen.", login, form["weiter"], kuerzel = directory.all().size > 1) }
                }
                is SignIn.System -> {
                    directory.system?.audit?.record(outcome.session.user, "login", outcome.session.user.displayName)
                    call.response.cookies.append(sessionCookie(SYSTEM_COOKIE, outcome.token, SYSTEM_BASE, web.config))
                    call.respondRedirect(safeTarget(form["weiter"], SYSTEM_BASE, under = SYSTEM_BASE))
                }
                is SignIn.Verein -> {
                    val user = outcome.session.user
                    if (user.via == null) outcome.tenant.web.audit.record(user, "login", user.displayName)
                    else outcome.tenant.web.audit.record(user, "login.test", user.displayName, "Testanmeldung als ${user.role.label}")
                    outcome.hauptadmin?.let { directory.system?.audit?.record(it, "support.login", "${user.login}@${outcome.tenant.info.slug}", "als ${user.role.label}") }
                    call.response.cookies.append(sessionCookie(COOKIE, SessionCookie.value(outcome.tenant, outcome.token), BASE, web.config))
                    call.respondRedirect(safeTarget(form["weiter"], homeOf(user.role)))
                }
            }
        }
    }
    get("/anmelden") {
        // Ein frischer Server führt zur Einrichtung — außer es gibt schon einen Hauptadmin, der sich hier anmelden will.
        if (directory.needsSetup() && directory.system?.accounts?.anyUser() != true) return@get call.respondRedirect("$BASE/einrichten")
        if (call.sessionOf(web) != null) return@get call.respondRedirect(BASE)
        val target = call.request.queryParameters["weiter"]
        val notice = if (call.request.queryParameters["neu"] == "1") "Eingerichtet. Jetzt mit dem neuen Zugang anmelden." else null
        // Ein Hinweis, keine Bestätigung — deshalb neutral, nicht grün.
        val hint = if (target != null && (target == SYSTEM_BASE || target.startsWith("$SYSTEM_BASE/"))) "Systemverwaltung: mit name@${Kuerzel.SYSTEM} anmelden." else null
        call.html { loginPage(null, "", target, notice, kuerzel = directory.all().size > 1, hint = hint) }
    }
    post("/abmelden") {
        call.guardedPost(web, null) { _, _ ->
            call.request.cookies[COOKIE]?.let { web.accounts.logout(SessionCookie.token(it)) }
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
