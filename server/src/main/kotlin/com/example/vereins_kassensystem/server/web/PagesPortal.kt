package com.example.vereins_kassensystem.server.web

import com.example.vereins_kassensystem.server.devices.Tokens
import com.example.vereins_kassensystem.server.payments.PaymentProblem
import com.example.vereins_kassensystem.server.tenancy.Tenant
import com.example.vereins_kassensystem.server.tenancy.TenantDirectory
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.fieldSet
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.hiddenInput
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.legend
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Der Deckel für Mitglieder unter /konto/<kürzel> — eigene Seiten, eigene Sitzung (Cookie nur
 * unter diesem Pfad), dieselbe Strenge wie die Verwaltung: keine Skripte, ein Stylesheet. Bezahlt
 * wird auf der Seite des Anbieters; hierher führt nur ein Link dorthin.
 */
internal const val PORTAL = "/konto"
private const val PORTAL_COOKIE = "vd_konto"

/**
 * Unter welcher Adresse Mitglieder und Anbieter den Server erreichen: wie in der Systemverwaltung
 * eingestellt, sonst aus der Anfrage. Hinter einem Proxy kommt sie als http an; nach außen ist es
 * https — eine andere Adresse nähme kein Zahlungsanbieter.
 */
internal fun ApplicationCall.publicBase(directory: TenantDirectory): String {
    directory.publicUrl?.let { return it }
    val origin = request.origin
    val host = origin.serverHost
    val local = host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2" || host.endsWith(".localhost")
    if (!local) return "https://$host"
    return "${origin.scheme}://$host" + if (origin.serverPort == 80 || origin.serverPort == 443) "" else ":${origin.serverPort}"
}

private fun ApplicationCall.tenantOf(directory: TenantDirectory): Tenant? = parameters["verein"]?.let(directory::bySlug)?.takeIf { it.info.active }

private fun ApplicationCall.portalSession(tenant: Tenant): Portal.Session? =
    request.cookies[PORTAL_COOKIE]?.takeIf { it.isNotBlank() }?.let(tenant.web.portal::session)

private suspend fun ApplicationCall.unknownVerein() = html(HttpStatusCode.NotFound) {
    document("Nicht gefunden", farbe = null) {
        div("portal") {
            panel { div("panel-body") { h1("title-m") { +"Diesen Verein gibt es hier nicht." }; p("muted") { +"Den Link vom Verein noch einmal öffnen — vielleicht hat sich sein Kürzel geändert." } } }
        }
    }
}

fun Route.portalRoutes(directory: TenantDirectory) {
    get(PORTAL) { call.respondRedirect("$PORTAL/${directory.default.info.slug}") }

    route("$PORTAL/{verein}") {
        install(SecurityHeaders)

        get {
            val tenant = call.tenantOf(directory) ?: return@get call.unknownVerein()
            val web = tenant.web
            val session = call.portalSession(tenant)
            val notice = call.request.queryParameters["hinweis"]
            val problem = call.request.queryParameters["fehler"]
            if (session == null) return@get call.html { portalLogin(tenant, web.settings.load(), notice, problem, "") }
            val members = web.portal.members(session.email)
            call.html { portalAccount(tenant, web.settings.load(), session, members, members.associate { it.id to web.reads.statement(it.id, 8) }, directory.config.zone, notice, problem) }
        }

        get("/farbe.css") {
            val tenant = call.tenantOf(directory) ?: return@get call.respond(HttpStatusCode.NotFound)
            val accent = tenant.web.settings.load().accent
            call.response.header("Cache-Control", "no-cache")
            call.respondText(VereinSettings.accentSheet(accent), ContentType.Text.CSS)
        }

        // Der Link aus der Mail: Erst ein Klick hier meldet an. So verbraucht ein Virenscanner, der
        // Links in Mails vorab öffnet, den Link nicht.
        get("/link/{token}") {
            val tenant = call.tenantOf(directory) ?: return@get call.unknownVerein()
            call.html { portalRedeem(tenant, tenant.web.settings.load(), call.parameters["token"].orEmpty()) }
        }

        rateLimit(RateLimitName("login")) {
            post("/anmelden") {
                val tenant = call.tenantOf(directory) ?: return@post call.unknownVerein()
                val form = call.receiveParameters()
                val values = tenant.web.settings.load()
                val verein = values.name.ifBlank { tenant.info.name }
                val base = call.publicBase(directory)
                tenant.web.portal.requestLink(form["email"].orEmpty(), verein, { token -> "$base$PORTAL/${tenant.info.slug}/link/$token" })
                // Immer dieselbe Antwort: Wer fragt, erfährt nicht, ob es die Adresse gibt.
                call.html { portalLogin(tenant, values, "Wenn die Adresse beim Verein hinterlegt ist, ist der Link unterwegs. Er gilt ${Portal.LINK_VALID.toMinutes()} Minuten.", null, form["email"].orEmpty()) }
            }
            post("/link/{token}") {
                val tenant = call.tenantOf(directory) ?: return@post call.unknownVerein()
                val session = tenant.web.portal.redeem(call.parameters["token"].orEmpty())
                    ?: return@post call.respondRedirect("$PORTAL/${tenant.info.slug}?fehler=" + "Der Link ist abgelaufen oder schon benutzt. Einen neuen anfordern.".encodeURLParameter())
                call.response.cookies.append(
                    Cookie(
                        name = PORTAL_COOKIE, value = session, path = "$PORTAL/${tenant.info.slug}", httpOnly = true, secure = !directory.config.insecureCookies,
                        maxAge = Portal.SESSION_VALID.seconds.toInt(), extensions = mapOf("SameSite" to "Lax")
                    )
                )
                call.respondRedirect("$PORTAL/${tenant.info.slug}")
            }
        }

        post("/aufladen") {
            val tenant = call.tenantOf(directory) ?: return@post call.unknownVerein()
            val slug = tenant.info.slug
            val session = call.portalSession(tenant) ?: return@post call.respondRedirect("$PORTAL/$slug")
            val form = call.receiveParameters()
            if (!Tokens.constantTimeEquals(form["_csrf"].orEmpty(), session.csrf)) return@post call.respondRedirect("$PORTAL/$slug?fehler=" + "Die Seite war zu alt. Bitte noch einmal.".encodeURLParameter())
            val outcome = try {
                val memberId = uuidOrNull(form["mitglied"]) ?: throw AccountProblem("Welcher Deckel?")
                val amount = (form["eigener"]?.takeIf { it.isNotBlank() } ?: form["betrag"])
                    ?.trim()?.replace(',', '.')?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
                    ?: throw AccountProblem("Bitte einen Betrag wählen.")
                val base = call.publicBase(directory)
                val (topUp, _) = tenant.web.portal.start(session, memberId, amount) { id -> "$base/v1/online/sumup/${tenant.id}" to "$base$PORTAL/$slug/zahlung/$id" }
                "$PORTAL/$slug/bezahlen/${topUp.id}"
            } catch (e: AccountProblem) {
                "$PORTAL/$slug?fehler=" + e.message.orEmpty().encodeURLParameter()
            }
            call.respondRedirect(outcome)
        }

        // Nach dem Absenden: Betrag und Mitglied noch einmal, dann der Link zum Anbieter.
        get("/bezahlen/{id}") {
            val tenant = call.tenantOf(directory) ?: return@get call.unknownVerein()
            val session = call.portalSession(tenant) ?: return@get call.respondRedirect("$PORTAL/${tenant.info.slug}")
            val topUp = uuidOrNull(call.parameters["id"])?.let(tenant.web.portal::topUp)?.takeIf { it.email.equals(session.email, ignoreCase = true) }
                ?: return@get call.respondRedirect("$PORTAL/${tenant.info.slug}")
            val payUrl = tenant.web.portal.payUrl(topUp.id) ?: return@get call.respondRedirect("$PORTAL/${tenant.info.slug}/zahlung/${topUp.id}")
            call.html { portalPay(tenant, tenant.web.settings.load(), topUp, payUrl) }
        }

        // Zurück vom Anbieter. Nachgefragt wird hier auch, falls dessen Benachrichtigung noch nicht da ist.
        get("/zahlung/{id}") {
            val tenant = call.tenantOf(directory) ?: return@get call.unknownVerein()
            val id = uuidOrNull(call.parameters["id"]) ?: return@get call.respondRedirect("$PORTAL/${tenant.info.slug}")
            val topUp = try {
                tenant.web.portal.settle(id)
            } catch (e: PaymentProblem) {
                tenant.web.portal.topUp(id)
            } ?: return@get call.respondRedirect("$PORTAL/${tenant.info.slug}")
            val session = call.portalSession(tenant)?.takeIf { it.email.equals(topUp.email, ignoreCase = true) }
            val balance = session?.let { s -> tenant.web.portal.members(s.email).firstOrNull { it.id == topUp.memberId }?.balance }
            call.html { portalResult(tenant, tenant.web.settings.load(), topUp, balance, session != null) }
        }

        post("/abmelden") {
            val tenant = call.tenantOf(directory) ?: return@post call.unknownVerein()
            val session = call.portalSession(tenant)
            val form = call.receiveParameters()
            if (session != null && Tokens.constantTimeEquals(form["_csrf"].orEmpty(), session.csrf)) {
                call.request.cookies[PORTAL_COOKIE]?.let(tenant.web.portal::logout)
                call.response.cookies.append(Cookie(name = PORTAL_COOKIE, value = "", path = "$PORTAL/${tenant.info.slug}", httpOnly = true, maxAge = 0))
            }
            call.respondRedirect("$PORTAL/${tenant.info.slug}")
        }
    }

    /**
     * Die Benachrichtigung von SumUp: nur die Checkout-ID, ohne Unterschrift. Geglaubt wird ihr
     * nichts — der Server fragt mit dem Schlüssel des Vereins bei SumUp nach (Portal.settle). Ist
     * SumUp gerade nicht erreichbar, antwortet er mit 503, und SumUp versucht es später noch einmal.
     */
    post("/v1/online/sumup/{verein}") {
        val tenant = uuidOrNull(call.parameters["verein"])?.let(directory::byId)
        val checkoutId = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject["id"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        if (tenant != null && !checkoutId.isNullOrBlank()) {
            try {
                tenant.web.portal.settleCheckout(checkoutId)
            } catch (e: PaymentProblem) {
                if (e.retry) return@post call.respond(HttpStatusCode.ServiceUnavailable)
            }
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

// ------------------------------------------------------------------- Seiten

private val DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy", AT)

private fun farbeOf(tenant: Tenant) = "$PORTAL/${tenant.info.slug}/farbe.css"

private fun FlowContent.portalBrand(verein: String) = div("brand") {
    span("brand-mark") { icon("mark", "l") }
    div("brand-text") {
        span("title-s") { +verein }
        span("cap") { +"Dein Deckel" }
    }
}

private fun nameOf(tenant: Tenant, values: VereinSettings.Values) = values.name.ifBlank { tenant.info.name }

/** Womit man auf der Seite des Anbieters zahlen kann: die Karte immer, der Rest, wie SumUp ihn freigeschaltet hat. */
private fun methodsOf(online: OnlineTopUp): String = (listOf("Karte") + online.methods.map(Portal::methodLabel)).joinToString(", ")

private fun HTML.portalLogin(tenant: Tenant, values: VereinSettings.Values, notice: String?, problem: String?, email: String) = document("Dein Deckel", farbeOf(tenant)) {
    div("portal") {
        portalBrand(nameOf(tenant, values))
        panel {
            div("panel-body") {
                h1("headline") { +"Dein Deckel" }
                p("muted") {
                    +"Den Stand ansehen${if (values.online.usable) " und online aufladen" else ""}. Angemeldet wird mit einem Link an die E-Mail-Adresse, die beim Verein für dich hinterlegt ist — ohne Passwort."
                }
                flash(notice, problem)
                form(action = "$PORTAL/${tenant.info.slug}/anmelden", method = FormMethod.post) {
                    label("field") {
                        span { +"E-Mail-Adresse" }
                        input(InputType.email, name = "email") { value = email; required = true; attributes["autocomplete"] = "email"; attributes["inputmode"] = "email" }
                    }
                    button(type = ButtonType.submit, classes = "btn btn-primary btn-big") { +"Link schicken" }
                }
            }
        }
        p("cap") { +"Keine Adresse hinterlegt oder eine alte? Beim Kassier melden." }
    }
}

private fun HTML.portalRedeem(tenant: Tenant, values: VereinSettings.Values, token: String) = document("Anmelden", farbeOf(tenant)) {
    div("portal") {
        portalBrand(nameOf(tenant, values))
        panel {
            div("panel-body") {
                h1("headline") { +"Anmelden" }
                p("muted") { +"Ein Tipp, und du siehst deinen Deckel. Auf diesem Gerät bleibst du ${Portal.SESSION_VALID.toDays()} Tage angemeldet." }
                form(action = "$PORTAL/${tenant.info.slug}/link/$token", method = FormMethod.post) {
                    button(type = ButtonType.submit, classes = "btn btn-primary btn-big") { +"Jetzt anmelden" }
                }
            }
        }
    }
}

private fun HTML.portalAccount(
    tenant: Tenant, values: VereinSettings.Values, session: Portal.Session, members: List<MemberLine>,
    statements: Map<UUID, List<StatementLine>>, zone: ZoneId, notice: String?, problem: String?,
) = document("Dein Deckel", farbeOf(tenant)) {
    val slug = tenant.info.slug
    val online = values.online
    div("portal") {
        div("row-between") {
            portalBrand(nameOf(tenant, values))
            form(action = "$PORTAL/$slug/abmelden", method = FormMethod.post) {
                hiddenInput(name = "_csrf") { value = session.csrf }
                button(type = ButtonType.submit, classes = "btn btn-quiet") { +"Abmelden" }
            }
        }
        flash(notice, problem)
        if (members.isEmpty()) panel { div("panel-body") { p("muted") { +"Zu ${session.email} gibt es beim Verein keinen Deckel mehr. Beim Kassier melden." } } }
        for (m in members) panel {
            div("panel-body") {
                h2("title-m") { +m.displayName }
                div {
                    span("label-m") { +(if (m.balance < 0) "Offen auf dem Deckel" else "Guthaben") }
                    span("money-l ${m.tone}") { +euro(m.balance) }
                }
                if (m.blocked) div("note note-warn") { icon("alert", "m"); span { +"An der Theke ist dein Deckel gesperrt: ${m.blockedReason}. Aufladen geht trotzdem." } }
                if (online.usable) form(action = "$PORTAL/$slug/aufladen", method = FormMethod.post) {
                    hiddenInput(name = "_csrf") { value = session.csrf }
                    hiddenInput(name = "mitglied") { value = m.id.toString() }
                    fieldSet("amounts") {
                        legend("sr") { +"Betrag" }
                        val preselected = online.presets.getOrNull(1) ?: online.presets.first()
                        for (amount in online.presets) label("amount") {
                            input(InputType.radio, name = "betrag") { value = "$amount"; checked = amount == preselected }
                            span { +euro(amount.toDouble()).replace(",00", "") }
                        }
                    }
                    label("field") {
                        span { +"Oder ein anderer Betrag, ${online.min} bis ${online.max} €" }
                        input(InputType.number, name = "eigener") {
                            attributes["min"] = "${online.min}"; attributes["max"] = "${online.max}"; attributes["step"] = "1"; attributes["inputmode"] = "numeric"
                        }
                    }
                    button(type = ButtonType.submit, classes = "btn btn-money btn-big") { +"Weiter zur Bezahlung" }
                    p("cap") { +"Bezahlt wird auf der Seite von SumUp: ${methodsOf(online)}." }
                }
                val lines = statements[m.id].orEmpty()
                if (lines.isNotEmpty()) {
                    span("label-m") { +"Zuletzt" }
                    table("t t-tight t-flush") {
                        tbody {
                            for (line in lines) tr {
                                td("c-muted nowrap") { +line.at.atZone(zone).format(DAY) }
                                td { +line.text }
                                td("num") { span("tnum") { +euroSigned(line.effect) } }
                            }
                        }
                    }
                }
            }
        }
        if (!online.usable) p("cap") { +"Online aufladen ist bei diesem Verein nicht eingeschaltet — aufgeladen wird an der Theke." }
    }
}

private fun HTML.portalPay(tenant: Tenant, values: VereinSettings.Values, topUp: Portal.TopUp, payUrl: String) = document("Bezahlen", farbeOf(tenant)) {
    div("portal") {
        portalBrand(nameOf(tenant, values))
        panel {
            div("panel-body") {
                div {
                    span("label-m") { +"Auf den Deckel von ${topUp.memberName}" }
                    span("money-l c-money") { +euro(topUp.amount.toDouble()) }
                }
                p("muted") { +"Bezahlt wird auf der Seite von SumUp — ${methodsOf(values.online)}. Danach kommst du hierher zurück, und der Betrag steht auf deinem Deckel." }
                a(href = payUrl, classes = "btn btn-money btn-big") { +"Bei SumUp bezahlen" }
                a(href = "$PORTAL/${tenant.info.slug}", classes = "link") { +"Abbrechen" }
            }
        }
    }
}

private fun HTML.portalResult(tenant: Tenant, values: VereinSettings.Values, topUp: Portal.TopUp, balance: Double?, signedIn: Boolean) = document("Aufladen", farbeOf(tenant)) {
    val slug = tenant.info.slug
    div("portal") {
        portalBrand(nameOf(tenant, values))
        panel {
            div("panel-body") {
                when {
                    topUp.paid && topUp.detail.startsWith("Bezahlt, aber nicht gebucht") -> div("note note-warn") {
                        icon("alert", "m"); span { +"Bezahlt — gebucht wird es von Hand. Der Kassier ist informiert; du musst nichts tun." }
                    }
                    topUp.paid -> {
                        div("note note-ok") { icon("check", "m"); span { +"Danke! ${euro(topUp.amount.toDouble())} sind ${if (signedIn) "auf dem Deckel von ${topUp.memberName}" else "gutgeschrieben"}." } }
                        balance?.let { div { span("label-m") { +"Neuer Stand"; +" " }; span("money-l ${if (it < 0) "c-warning" else "c-money"}") { +euro(it) } } }
                        p("cap") { +"An der Theke steht es nach dem nächsten Abgleich der Tablets, meist binnen einer Minute." }
                    }
                    topUp.open -> {
                        div("note") { span { +"Die Zahlung ist noch nicht bestätigt. Das dauert meist nur Sekunden." } }
                        a(href = "$PORTAL/$slug/zahlung/${topUp.id}", classes = "btn btn-big") { +"Neu laden" }
                    }
                    else -> div("note note-error") { icon("alert", "m"); span { +"Die Zahlung ist nicht durchgegangen. Abgebucht wurde nichts." } }
                }
                a(href = "$PORTAL/$slug", classes = "link") { +(if (signedIn) "Zum Deckel" else "Anmelden und Stand ansehen") }
            }
        }
    }
}

/** Für die Verwaltung: Adresse, unter der Mitglieder ihren Deckel finden. */
internal fun portalLink(base: String, tenant: Tenant) = "$base$PORTAL/${tenant.info.slug}"
