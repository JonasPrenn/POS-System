package com.example.vereins_kassensystem.server.web

import com.example.vereins_kassensystem.AppVersion
import com.example.vereins_kassensystem.server.devices.Tokens
import com.example.vereins_kassensystem.server.tenancy.Kuerzel
import com.example.vereins_kassensystem.server.tenancy.SystemStore
import com.example.vereins_kassensystem.server.tenancy.TenantDirectory
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.uri
import io.ktor.server.response.respondRedirect
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
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.hiddenInput
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.main
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import java.time.Instant

/**
 * Die Systemverwaltung (Hauptadmin): welche Vereine es auf diesem Server gibt, die Mindestversion
 * der App, Updates, die Hauptadmins. Eigene Sitzung mit eigenem Cookie, nur unter diesem Pfad —
 * wer hier angemeldet ist, ist es in keinem Verein, und umgekehrt. In einen Verein kommt ein
 * Hauptadmin über die Testanmeldung (hauptadmin#benutzer@verein), die dort im Protokoll steht.
 */
internal const val SYSTEM_BASE = "$BASE/system"
internal const val SYSTEM_COOKIE = "vd_system"

/** Die Seiten der Verwaltung brauchen einen [PageContext]; die Systemverwaltung hat keinen Verein und leiht sich die Vorgaben. */
private fun TenantDirectory.contextOf(session: WebSession) = PageContext(
    session,
    VereinSettings.Values("", VereinSettings.DEFAULT_ACCENT, 1, "", BankAccount("", "", ""), Smtp("", 587, "", "", "", true), ""),
    config.zone, Instant.now(), withSystem = true,
)

private suspend fun ApplicationCall.systemGuarded(directory: TenantDirectory, block: suspend (PageContext, SystemStore) -> Unit) {
    val system = directory.system ?: return html(HttpStatusCode.ServiceUnavailable) { unavailablePage(directory.systemProblem) }
    if (!system.accounts.anyUser()) return respondRedirect("$SYSTEM_BASE/einrichten")
    val session = request.cookies[SYSTEM_COOKIE]?.takeIf { it.isNotBlank() }?.let(system.accounts::session)
        ?: return respondRedirect("$BASE/anmelden?weiter=${request.uri.encodeURLParameter()}")
    block(directory.contextOf(session), system)
}

private suspend fun ApplicationCall.systemPost(directory: TenantDirectory, block: suspend (PageContext, SystemStore, Parameters) -> Unit) = systemGuarded(directory) { ctx, system ->
    val form = receiveParameters()
    if (!Tokens.constantTimeEquals(form["_csrf"].orEmpty(), ctx.session.csrf)) {
        return@systemGuarded html(HttpStatusCode.Forbidden) { serverShell(ctx, "Kein Zugriff", "Das Formular ist abgelaufen. Bitte die Seite neu laden und noch einmal versuchen.") { } }
    }
    block(ctx, system, form)
}

internal fun Route.serverPages(directory: TenantDirectory) = route("/system") {
    get {
        call.systemGuarded(directory) { ctx, system ->
            call.html { serverPage(ctx, directory, system, call.request.queryParameters["hinweis"], call.request.queryParameters["fehler"]) }
        }
    }
    post {
        call.systemPost(directory) { ctx, system, form ->
            val outcome = try {
                when (form["teil"]) {
                    "verein" -> {
                        if (form["passwort"] != form["passwort2"]) throw AccountProblem("Die beiden Passwörter sind verschieden.")
                        val tenant = directory.create(form["name"].orEmpty(), form["kuerzel"].orEmpty(), form["admin_name"].orEmpty(), form["admin_login"].orEmpty(), form["passwort"].orEmpty())
                        tenant.web.audit.recordAs("${ctx.user.displayName} (Hauptadmin)", "verein.create", tenant.info.name, "erster Administrator: ${form["admin_login"].orEmpty().trim()}")
                        system.audit.record(ctx.user, "verein.create", tenant.info.name, "Kürzel ${tenant.info.slug}, Datenbank ${tenant.info.dbName}")
                        "${tenant.info.name} ist angelegt. Anmelden mit ${form["admin_login"].orEmpty().trim()}@${tenant.info.slug}."
                    }
                    "verein-aendern" -> {
                        val tenant = uuidOrNull(form["id"])?.let(directory::byId) ?: throw AccountProblem("Diesen Verein gibt es nicht.")
                        val before = tenant.info
                        directory.rename(tenant, form["kuerzel"].orEmpty(), form["name"].orEmpty())
                        if (tenant.info != before) {
                            val detail = listOfNotNull(
                                "Kürzel ${before.slug} → ${tenant.info.slug}".takeIf { before.slug != tenant.info.slug },
                                "Name „${before.name}“ → „${tenant.info.name}“".takeIf { before.name != tenant.info.name },
                            ).joinToString(" · ")
                            system.audit.record(ctx.user, "verein.update", tenant.info.name, detail)
                            tenant.web.audit.recordAs("${ctx.user.displayName} (Hauptadmin)", "settings.save", detail = detail)
                        }
                        "Gespeichert."
                    }
                    "mindestversion" -> {
                        directory.setMinAppVersion(form["version"].orEmpty())
                        system.audit.record(ctx.user, "minversion.set", directory.minAppVersion)
                        if (directory.minAppVersion == TenantDirectory.NO_MINIMUM) "Gespeichert: Alle Versionen der App dürfen kassieren."
                        else "Gespeichert: Tablets unter ${directory.minAppVersion} sperren sich beim nächsten Abgleich."
                    }
                    "adresse" -> {
                        directory.setPublicUrl(form["adresse"].orEmpty())
                        system.audit.record(ctx.user, "settings.save", detail = "Adresse von außen: ${directory.publicUrl ?: "aus der Anfrage"}")
                        "Gespeichert."
                    }
                    "update" -> {
                        when (form["aktion"]) {
                            "pruefen" -> { directory.updates.request("check"); system.audit.record(ctx.user, "update.check", detail = "Suche angestoßen") }
                            "installieren" -> { directory.updates.request("install"); system.audit.record(ctx.user, "update.install", detail = "Installation angestoßen: ${directory.updates.status().latest ?: "?"}") }
                            else -> {
                                val mode = Updates.Mode.entries.firstOrNull { it.name == form["modus"] } ?: Updates.Mode.CHECK
                                directory.updates.saveSettings(mode, form["abstand"]?.toIntOrNull() ?: 60)
                                system.audit.record(ctx.user, "settings.save", detail = "Updates: ${mode.label}, alle ${form["abstand"] ?: "60"} Minuten")
                            }
                        }
                        "Gespeichert."
                    }
                    "hauptadmin" -> {
                        if (form["passwort"] != form["passwort2"]) throw AccountProblem("Die beiden Passwörter sind verschieden.")
                        val user = system.accounts.create(form["login"].orEmpty(), form["name"].orEmpty(), Role.ADMIN, form["passwort"].orEmpty())
                        system.audit.record(ctx.user, "user.create", user.displayName, "Hauptadmin")
                        "${user.displayName} ist Hauptadmin. Anmelden mit ${user.login}@${Kuerzel.SYSTEM}."
                    }
                    "hauptadmin-aendern" -> {
                        val target = uuidOrNull(form["id"])?.let(system.accounts::find) ?: throw AccountProblem("Diesen Hauptadmin gibt es nicht.")
                        val active = form["aktiv"] == "1"
                        if (!active && target.active && system.accounts.activeAdmins() <= 1) throw AccountProblem("Das ist der letzte Hauptadmin. Erst einen zweiten anlegen.")
                        if (active != target.active) {
                            system.accounts.update(target.id, Role.ADMIN, active, null)
                            system.audit.record(ctx.user, "user.update", target.displayName, if (active) "aktiv" else "gesperrt")
                        }
                        form["passwort"]?.takeIf { it.isNotEmpty() }?.let {
                            system.accounts.setPassword(target.id, it)
                            system.audit.record(ctx.user, "user.password", target.displayName)
                        }
                        "Gespeichert."
                    }
                    else -> throw AccountProblem("Unbekanntes Formular.")
                }.let { "hinweis=" + it.encodeURLParameter() }
            } catch (e: AccountProblem) {
                "fehler=" + e.message.orEmpty().encodeURLParameter()
            }
            call.respondRedirect("$SYSTEM_BASE?$outcome")
        }
    }

    get("/einrichten") {
        val system = directory.system ?: return@get call.html(HttpStatusCode.ServiceUnavailable) { unavailablePage(directory.systemProblem) }
        if (system.accounts.anyUser()) return@get call.respondRedirect(SYSTEM_BASE)
        call.html { systemSetupPage(null) }
    }
    rateLimit(RateLimitName("login")) {
        post("/einrichten") {
            val system = directory.system ?: return@post call.html(HttpStatusCode.ServiceUnavailable) { unavailablePage(directory.systemProblem) }
            if (system.accounts.anyUser()) return@post call.respondRedirect(SYSTEM_BASE)
            val form = call.receiveParameters()
            val problem = when {
                !Tokens.constantTimeEquals(form["schluessel"].orEmpty().trim(), directory.config.pairingAdminToken) ->
                    "Der Verwaltungsschlüssel stimmt nicht. Er steht als PAIRING_ADMIN_TOKEN in der .env des Servers."
                form["passwort"] != form["passwort2"] -> "Die beiden Passwörter sind verschieden."
                else -> try {
                    val user = system.accounts.create(form["login"].orEmpty(), form["name"].orEmpty(), Role.ADMIN, form["passwort"].orEmpty())
                    system.audit.record(user, "user.create", user.displayName, "Ersteinrichtung, Hauptadmin")
                    null
                } catch (e: AccountProblem) {
                    e.message
                }
            }
            if (problem != null) return@post call.html { systemSetupPage(problem, form["name"].orEmpty(), form["login"].orEmpty()) }
            call.respondRedirect("$BASE/anmelden?weiter=${SYSTEM_BASE.encodeURLParameter()}")
        }
    }
    post("/abmelden") {
        call.systemPost(directory) { _, system, _ ->
            call.request.cookies[SYSTEM_COOKIE]?.let(system.accounts::logout)
            call.response.cookies.append(Cookie(name = SYSTEM_COOKIE, value = "", path = SYSTEM_BASE, httpOnly = true, maxAge = 0))
            call.respondRedirect("$BASE/anmelden")
        }
    }
}

// ------------------------------------------------------------------ Seiten

private fun HTML.serverShell(ctx: PageContext, title: String, subtitle: String, content: FlowContent.() -> Unit) = document(title, farbe = null) {
    div("app") {
        nav("side") {
            attributes["aria-label"] = "Systemverwaltung"
            div("brand") {
                span("brand-mark") { icon("mark", "l") }
                div("brand-text") {
                    span("title-s") { +"VereinsDeckel" }
                    span("cap") { +"Systemverwaltung" }
                }
            }
            div("nav-group") {
                a(href = SYSTEM_BASE, classes = "nav-item") { attributes["aria-current"] = "page"; icon("gear"); span { +"Übersicht" } }
            }
            div("grow")
            div("who") {
                span("avatar") { +ctx.user.initials }
                div("who-text") {
                    span("title-s") { +ctx.user.displayName }
                    span("cap") { +"Hauptadmin" }
                }
            }
        }
        main {
            div("page-head") {
                div {
                    h1("headline") { +title }
                    p("muted") { +subtitle }
                }
                div("actions") {
                    postForm(ctx, "$SYSTEM_BASE/abmelden") { button(type = ButtonType.submit, classes = "btn") { icon("logout", "m"); +"Abmelden" } }
                }
            }
            content()
        }
    }
}

private fun HTML.serverPage(ctx: PageContext, directory: TenantDirectory, system: SystemStore, notice: String?, problem: String?) =
    serverShell(ctx, "Systemverwaltung", "Die Vereine auf diesem Server, die Mindestversion der App, Updates") {
        flash(notice, problem)
        div("cols cols-side") {
            div("stack") {
                panel {
                    panelHead("Vereine") {
                        dialog("verein-neu", "btn btn-primary", "Verein anlegen", triggerIcon = "plus") {
                            postForm(ctx, SYSTEM_BASE, "stack-tight") {
                                hiddenInput(name = "teil") { value = "verein" }
                                p("muted") { +"Eigene Datenbank, eigene Geräte, eigene Zugänge. Name und Farbe kommen erst auf die Tablets, wenn der Verein sie in seinen Einstellungen speichert — nach dem Koppeln des ersten Tablets." }
                                label("field") { span { +"Name des Vereins" }; input(InputType.text, name = "name") { required = true; maxLength = "80" } }
                                label("field") { span { +"Kürzel für die Anmeldung (name@kürzel)" }; input(InputType.text, name = "kuerzel") { required = true; maxLength = "30"; attributes["autocomplete"] = "off" } }
                                span("label-m") { +"Erster Administrator des Vereins" }
                                label("field") { span { +"Name" }; input(InputType.text, name = "admin_name") { required = true } }
                                label("field") { span { +"Anmeldename" }; input(InputType.text, name = "admin_login") { required = true; attributes["autocomplete"] = "off" } }
                                label("field") { span { +"Erstes Passwort, mindestens ${Accounts.MIN_PASSWORD} Zeichen" }; input(InputType.password, name = "passwort") { required = true; attributes["autocomplete"] = "new-password" } }
                                label("field") { span { +"Passwort wiederholen" }; input(InputType.password, name = "passwort2") { required = true; attributes["autocomplete"] = "new-password" } }
                                button(type = ButtonType.submit, classes = "btn btn-primary") { +"Anlegen" }
                            }
                        }
                    }
                    table("t") {
                        thead { tr { th { +"Verein" }; th(classes = "num") { +"Geräte" }; th(classes = "num hide-sm") { +"Zugänge" }; th(classes = "hide-sm") { +"Angelegt" }; th { span("sr") { +"Ändern" } } } }
                        tbody {
                            for (tenant in directory.all()) tr {
                                val info = tenant.info
                                td { twoLine(info.name, "@${info.slug}${if (info.isDefault) " · erster Verein" else ""}${if (!info.active) " · abgeschaltet" else ""}") }
                                td("num") { span("tnum") { +"${runCatching { tenant.devices.list().count { !it.revoked } }.getOrDefault(0)}" } }
                                td("num hide-sm") { span("tnum") { +"${runCatching { tenant.web.accounts.list().count { it.active } }.getOrDefault(0)}" } }
                                td("c-muted nowrap hide-sm") { +ctx.dayYear(info.createdAt) }
                                td("num") {
                                    dialog("verein-${info.id}", "btn", "Ändern", "Verein ändern") {
                                        postForm(ctx, SYSTEM_BASE, "stack-tight") {
                                            hiddenInput(name = "teil") { value = "verein-aendern" }
                                            hiddenInput(name = "id") { value = info.id.toString() }
                                            label("field") { span { +"Name" }; input(InputType.text, name = "name") { value = info.name; required = true; maxLength = "80" } }
                                            label("field") { span { +"Kürzel" }; input(InputType.text, name = "kuerzel") { value = info.slug; required = true; maxLength = "30"; attributes["autocomplete"] = "off" } }
                                            p("cap") { +"Ein neues Kürzel gilt sofort für alle Anmeldungen dieses Vereins. Den Namen ändert der Verein auch selbst, in seinen Einstellungen." }
                                            button(type = ButtonType.submit, classes = "btn btn-primary") { +"Speichern" }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    div("panel-body") {
                        p("cap") { +"Anmelden im Verein mit name@kürzel; ohne Kürzel ist es der erste Verein. Testanmeldung als jemand anderes: ${ctx.user.login}#name@kürzel mit dem eigenen Passwort — sie steht im Protokoll des Vereins und hier." }
                    }
                }
                panel {
                    panelHead("Protokoll")
                    val entries = system.audit.recent(40)
                    if (entries.isEmpty()) div("panel-body") { p("muted") { +"Noch nichts." } }
                    else table("t t-tight") {
                        tbody {
                            for (e in entries) tr {
                                td { twoLine(listOfNotNull(ACTIONS[e.action] ?: e.action, e.subject.takeIf { it.isNotBlank() }?.let { "„$it“" }).joinToString(": "), listOf(e.actor, e.detail).filter { it.isNotBlank() }.joinToString(" · ")) }
                                td("num c-muted nowrap") { +ctx.friendly(e.at) }
                            }
                        }
                    }
                }
            }
            div("stack") {
                panel {
                    postForm(ctx, SYSTEM_BASE, "panel-body stack-tight") {
                        hiddenInput(name = "teil") { value = "mindestversion" }
                        div("row-between") {
                            h2("title-m") { +"Mindestversion der App" }
                            if (directory.minAppVersion == TenantDirectory.NO_MINIMUM) chip("alle Versionen", "neutral") else chip("ab ${directory.minAppVersion}", "warn")
                        }
                        p("muted") { +"Ein Tablet mit einer älteren App lässt sich nicht mehr bedienen, bis die App aktualisiert ist; sein Abgleich läuft weiter, nichts geht verloren. Die Sperre kennen Apps ab 1.3.0 — ältere arbeiten unabhängig davon weiter, deshalb bleibt der Server zu ihnen kompatibel. 0.0.0 heißt: alle Versionen; 1.3.0 verlangt, dass jedes Tablet die Sperre kennt." }
                        label("field") { span { +"Mindestversion, etwa 1.3.0" }; input(InputType.text, name = "version") { value = directory.minAppVersion; required = true; maxLength = "10"; attributes["inputmode"] = "decimal" } }
                        p("cap") { +"Dieser Server: Version ${AppVersion.LABEL}." }
                        div { button(type = ButtonType.submit, classes = "btn btn-primary") { +"Speichern" } }
                    }
                }
                panel {
                    postForm(ctx, SYSTEM_BASE, "panel-body stack-tight") {
                        hiddenInput(name = "teil") { value = "adresse" }
                        h2("title-m") { +"Adresse von außen" }
                        p("muted") { +"Unter dieser Adresse erreichen Mitglieder ihren Deckel und SumUp den Server — für Anmeldelinks, die Rückkehr vom Bezahlen und die Bestätigung einer Zahlung. Leer: aus der Anfrage abgeleitet, was hinter einem Proxy meist stimmt." }
                        label("field") { span { +"Adresse, etwa https://deckel.example.at" }; input(InputType.url, name = "adresse") { value = directory.publicUrl.orEmpty(); attributes["autocomplete"] = "off" } }
                        div { button(type = ButtonType.submit, classes = "btn btn-primary") { +"Speichern" } }
                    }
                }
                updatesPanel(ctx.session.csrf, SYSTEM_BASE, directory.updates)
                panel {
                    panelHead("Hauptadmins") {
                        dialog("hauptadmin-neu", "btn", "Anlegen", "Hauptadmin anlegen", triggerIcon = "plus") {
                            postForm(ctx, SYSTEM_BASE, "stack-tight") {
                                hiddenInput(name = "teil") { value = "hauptadmin" }
                                label("field") { span { +"Name" }; input(InputType.text, name = "name") { required = true } }
                                label("field") { span { +"Anmeldename" }; input(InputType.text, name = "login") { required = true; attributes["autocomplete"] = "off" } }
                                label("field") { span { +"Erstes Passwort, mindestens ${Accounts.MIN_PASSWORD} Zeichen" }; input(InputType.password, name = "passwort") { required = true; attributes["autocomplete"] = "new-password" } }
                                label("field") { span { +"Passwort wiederholen" }; input(InputType.password, name = "passwort2") { required = true; attributes["autocomplete"] = "new-password" } }
                                button(type = ButtonType.submit, classes = "btn btn-primary") { +"Anlegen" }
                            }
                        }
                    }
                    table("t t-tight") {
                        tbody {
                            for (u in system.accounts.list()) tr {
                                // Zwei Spalten: In der Seitenspalte ist für einen dritten Platz kein Platz.
                                td { div("row") { span("avatar avatar-s") { +u.initials }; twoLine(u.displayName, "${u.login}@${Kuerzel.SYSTEM}${if (u.active) "" else " · gesperrt"}") } }
                                td("num") {
                                    dialog("hauptadmin-${u.id}", "btn", "Ändern", "Hauptadmin ändern") {
                                        postForm(ctx, SYSTEM_BASE, "stack-tight") {
                                            hiddenInput(name = "teil") { value = "hauptadmin-aendern" }
                                            hiddenInput(name = "id") { value = u.id.toString() }
                                            label("field") { span { +"Neues Passwort (leer: bleibt)" }; input(InputType.password, name = "passwort") { attributes["autocomplete"] = "new-password" } }
                                            label("check") { input(InputType.checkBox, name = "aktiv") { value = "1"; checked = u.active }; span { +"Darf sich anmelden" } }
                                            button(type = ButtonType.submit, classes = "btn btn-primary") { +"Speichern" }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

private fun HTML.unavailablePage(problem: String?) = document("Systemverwaltung", farbe = null) {
    div("gate") {
        panel("gate-card") {
            div("brand") {
                span("brand-mark") { icon("mark", "l") }
                div("brand-text") { span("title-m") { +"Systemverwaltung" }; span("cap") { +"nicht verfügbar" } }
            }
            div("note note-warn") { icon("alert", "m"); span { +"Die Systemdatenbank fehlt${problem?.let { ": $it" } ?: "."}" } }
            p("muted") { +"Der Server läuft mit einem Verein weiter, wie vor den Vereinen; Updates stehen in dessen Einstellungen. Darf der Datenbankbenutzer Datenbanken anlegen, entsteht die Systemdatenbank beim nächsten Start — siehe server/README.md." }
            a(href = "$BASE/anmelden", classes = "link") { +"Zur Anmeldung" }
        }
    }
}

private fun HTML.systemSetupPage(problem: String?, name: String = "", login: String = "") = document("Systemverwaltung einrichten", farbe = null) {
    div("gate") {
        panel("gate-card") {
            div("brand") {
                span("brand-mark") { icon("mark", "l") }
                div("brand-text") {
                    span("title-m") { +"Systemverwaltung einrichten" }
                    span("cap") { +"Einmalig: der erste Hauptadmin" }
                }
            }
            p("muted") { +"Der Hauptadmin legt Vereine an, setzt die Mindestversion der App und spielt Updates ein. Wer den Verwaltungsschlüssel des Servers kennt, legt hier den ersten an; danach ist diese Seite zu." }
            problem?.let { div("note note-error") { icon("alert", "m"); span { +it } } }
            form(action = "$SYSTEM_BASE/einrichten", method = FormMethod.post) {
                label("field") { span { +"Verwaltungsschlüssel des Servers" }; input(InputType.password, name = "schluessel") { required = true; attributes["autocomplete"] = "off" } }
                label("field") { span { +"Dein Name" }; input(InputType.text, name = "name") { value = name; required = true; attributes["autocomplete"] = "name" } }
                label("field") { span { +"Anmeldename (angemeldet wird mit name@${Kuerzel.SYSTEM})" }; input(InputType.text, name = "login") { value = login; required = true; attributes["autocomplete"] = "username" } }
                label("field") { span { +"Passwort, mindestens ${Accounts.MIN_PASSWORD} Zeichen" }; input(InputType.password, name = "passwort") { required = true; attributes["autocomplete"] = "new-password" } }
                label("field") { span { +"Passwort wiederholen" }; input(InputType.password, name = "passwort2") { required = true; attributes["autocomplete"] = "new-password" } }
                button(type = ButtonType.submit, classes = "btn btn-primary btn-wide") { +"Hauptadmin anlegen" }
            }
        }
    }
}
