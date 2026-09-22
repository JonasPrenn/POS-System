package com.example.vereins_kassensystem.server.web

import com.example.vereins_kassensystem.server.devices.DeviceRecord
import io.ktor.http.encodeURLParameter
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.h2
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.ol
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.strong
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/**
 * Ein frisch erzeugter Kopplungscode, bis er einmal angezeigt wurde. Er steht nur hier im
 * Speicher und in keiner Adresse: In der Datenbank liegt allein sein Hash, und eine URL mit
 * dem Code stünde im Verlauf des Browsers und im Protokoll des Proxys.
 */
private class FreshCodes {
    class Fresh(val code: String, val until: java.time.Instant)
    private val bySession = ConcurrentHashMap<String, Fresh>()
    fun put(csrf: String, fresh: Fresh) { bySession[csrf] = fresh }
    fun take(csrf: String): Fresh? = bySession.remove(csrf)?.takeIf { it.until.isAfter(java.time.Instant.now()) }
}

private val ACTIONS = mapOf(
    "login" to "Angemeldet", "login.failed" to "Anmeldung fehlgeschlagen",
    "device.code" to "Kopplungscode erzeugt", "device.register" to "Gerät gekoppelt", "device.revoke" to "Gerät gesperrt",
    "user.create" to "Benutzer angelegt", "user.update" to "Benutzer geändert", "user.password" to "Passwort gesetzt",
    "settings.save" to "Einstellungen geändert",
    "member.create" to "Mitglied angelegt", "member.update" to "Mitglied geändert",
    "tab.topup" to "Deckel aufgeladen", "tab.correction" to "Deckel korrigiert",
    "purchase.create" to "Beleg erfasst", "purchase.update" to "Belegdaten geändert", "purchase.paid" to "Beleg bezahlt",
    "purchase.stock" to "Wareneingang gebucht", "purchase.line" to "Belegzeile zugeordnet", "purchase.line.remove" to "Belegzeile entfernt", "supplier.update" to "Lieferant geändert",
)

internal fun Route.systemPages(web: Web) {
    val fresh = FreshCodes()

    get("/geraete") {
        call.guarded(web, Area.DEVICES) { ctx ->
            val code = fresh.take(ctx.session.csrf)
            call.html { devicesPage(ctx, web.devices.list(), web.audit.recent(8, "device."), code?.let { it.code to ctx.time(it.until) }) }
        }
    }
    post("/geraete/code") {
        call.guardedPost(web, Area.DEVICES) { ctx, _ ->
            val (code, until) = web.devices.createPairingCode()
            fresh.put(ctx.session.csrf, FreshCodes.Fresh(code, until.toInstant()))
            web.audit.record(ctx.user, "device.code", detail = "gültig bis ${ctx.time(until.toInstant())}")
            call.respondRedirect("$BASE/geraete")
        }
    }
    post("/geraete/{id}/sperren") {
        call.guardedPost(web, Area.DEVICES) { ctx, form ->
            val device = uuidOrNull(call.parameters["id"])?.let { id -> web.devices.list().firstOrNull { it.id == id } }
            if (device != null && !device.revoked && web.devices.revoke(device.id)) {
                web.audit.record(ctx.user, "device.revoke", device.label, form["grund"].orEmpty().trim().take(200))
            }
            call.respondRedirect("$BASE/geraete")
        }
    }

    get("/benutzer") {
        call.guarded(web, Area.USERS) { ctx -> call.html { usersPage(ctx, web.accounts.list(), call.request.queryParameters["hinweis"], call.request.queryParameters["fehler"]) } }
    }
    post("/benutzer") {
        call.guardedPost(web, Area.USERS) { ctx, form ->
            val outcome = try {
                val role = Role.valueOf(form["rolle"].orEmpty())
                val user = web.accounts.create(form["login"].orEmpty(), form["name"].orEmpty(), role, form["passwort"].orEmpty(), form["bis"]?.takeIf { it.isNotBlank() }?.let(LocalDate::parse))
                web.audit.record(ctx.user, "user.create", user.displayName, "Rolle ${role.label}")
                "hinweis=" + "${user.displayName} ist angelegt. Das Passwort persönlich weitergeben, nicht per E-Mail.".encodeURLParameter()
            } catch (e: AccountProblem) {
                "fehler=" + e.message.orEmpty().encodeURLParameter()
            } catch (e: IllegalArgumentException) {
                "fehler=" + "Rolle oder Datum sind nicht lesbar.".encodeURLParameter()
            }
            call.respondRedirect("$BASE/benutzer?$outcome")
        }
    }
    post("/benutzer/{id}") {
        call.guardedPost(web, Area.USERS) { ctx, form ->
            val target = uuidOrNull(call.parameters["id"])?.let(web.accounts::find) ?: return@guardedPost call.respondRedirect("$BASE/benutzer")
            val outcome = try {
                val role = Role.valueOf(form["rolle"].orEmpty())
                val active = form["aktiv"] == "1"
                val until = form["bis"]?.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
                // Der letzte Administrator sperrt sich nicht selbst aus — sonst hilft nur noch die Datenbank.
                val losesAdmin = target.role == Role.ADMIN && (role != Role.ADMIN || !active || (until != null && until.isBefore(ctx.today)))
                if (losesAdmin && web.accounts.activeAdmins() <= 1) throw AccountProblem("Das ist der letzte Administrator. Erst einen zweiten anlegen.")
                web.accounts.update(target.id, role, active, until)
                web.audit.record(ctx.user, "user.update", target.displayName, "Rolle ${role.label}, ${if (active) "aktiv" else "gesperrt"}${until?.let { ", bis $it" } ?: ""}")
                form["passwort"]?.takeIf { it.isNotBlank() }?.let {
                    web.accounts.setPassword(target.id, it)
                    web.audit.record(ctx.user, "user.password", target.displayName)
                }
                "hinweis=" + "${target.displayName} ist gespeichert.".encodeURLParameter()
            } catch (e: AccountProblem) {
                "fehler=" + e.message.orEmpty().encodeURLParameter()
            } catch (e: IllegalArgumentException) {
                "fehler=" + "Rolle oder Datum sind nicht lesbar.".encodeURLParameter()
            }
            call.respondRedirect("$BASE/benutzer?$outcome")
        }
    }

    get("/protokoll") {
        call.guarded(web, Area.AUDIT) { ctx -> call.html { auditPage(ctx, web.audit.recent(200)) } }
    }

    get("/einstellungen") {
        call.guarded(web, Area.SETTINGS) { ctx -> call.html { settingsPage(ctx, call.request.queryParameters["hinweis"], call.request.queryParameters["fehler"]) } }
    }
    post("/einstellungen") {
        call.guardedPost(web, Area.SETTINGS) { ctx, form ->
            val outcome = try {
                web.settings.save(form["name"].orEmpty(), form["farbe"].orEmpty(), form["monat"]?.toIntOrNull() ?: 1)
                web.audit.record(ctx.user, "settings.save", detail = "Name, Vereinsfarbe, Rechnungsjahr")
                "hinweis=" + "Gespeichert.".encodeURLParameter()
            } catch (e: AccountProblem) {
                "fehler=" + e.message.orEmpty().encodeURLParameter()
            }
            call.respondRedirect("$BASE/einstellungen?$outcome")
        }
    }
}

internal fun FlowContent.flash(notice: String?, problem: String?) {
    notice?.takeIf { it.isNotBlank() }?.let { div("note note-ok") { icon("check", "m"); span { +it } } }
    problem?.takeIf { it.isNotBlank() }?.let { div("note note-error") { icon("alert", "m"); span { +it } } }
}

// ------------------------------------------------------------------- Geräte

private fun HTML.devicesPage(ctx: PageContext, devices: List<DeviceRecord>, log: List<AuditLog.Entry>, code: Pair<String, String>?) {
    val active = devices.count { !it.revoked }
    val top = devices.maxOfOrNull { it.lastAckSeq } ?: 0
    shell(ctx, Area.DEVICES, "Geräte", "$active aktive Geräte · der Server ruft nie an, die Geräte melden sich") {
        div("cols cols-side") {
            div("stack") {
                panel {
                    panelHead("Gekoppelte Geräte")
                    div("panel-note cap") { +"„Stand“ ist die höchste Sequenznummer, die das Gerät bestätigt hat. Wer hinter $top liegt, hat noch nicht alles." }
                    if (devices.isEmpty()) p("empty") { +"Noch kein Gerät. Rechts einen Code erzeugen und am Tablet eintragen." }
                    else table("t") {
                        thead { tr { th { +"Gerät" }; th(classes = "hide-sm") { +"Zuletzt gesehen" }; th(classes = "hide-sm") { +"Stand" }; th { +"Status" }; th { span("sr") { +"Aktion" } } } }
                        tbody {
                            for (d in devices.sortedBy { it.revoked }) tr {
                                td { div("row") { span("c-muted") { icon("tablet") }; twoLine(d.label, "${platformName(d.platform)} · seit ${ctx.dayYear(d.createdAt.toInstant())}") } }
                                td("nowrap hide-sm") { +ctx.ago(d.lastSeenAt?.toInstant()) }
                                td("c-muted tnum hide-sm") { +"${d.lastAckSeq}" }
                                td { if (d.revoked) chip("Gesperrt", "neutral", "lock") else deviceChip(ctx, d.lastSeenAt?.toInstant()) }
                                td("num") {
                                    if (!d.revoked) details {
                                        summary("btn btn-danger") { +"Sperren" }
                                        postForm(ctx, "$BASE/geraete/${d.id}/sperren", "stack-tight confirm") {
                                            label("field") {
                                                span { +"Grund, fürs Protokoll" }
                                                input(InputType.text, name = "grund") { placeholder = "verloren, defekt, falsches Gerät …"; maxLength = "200" }
                                            }
                                            button(type = ButtonType.submit, classes = "btn btn-danger") { +"„${d.label}“ jetzt sperren" }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                panel {
                    panelHead("Protokoll der Kopplungen") { if (ctx.user.role.may(Area.AUDIT)) more("Ganzes Protokoll", "$BASE/protokoll") }
                    if (log.isEmpty()) p("empty") { +"Noch nichts passiert." } else auditTable(ctx, log)
                }
            }
            panel {
                div("panel-body") {
                    h2("title-m") { +"Neues Gerät koppeln" }
                    p("muted") { +"Kein Passwort am Tablet: Der Code gilt zehn Minuten und genau einmal." }
                    if (code != null) div("code") {
                        attributes["aria-live"] = "polite"
                        strong { +code.first }
                        span("cap") { +"gültig bis ${code.second} Uhr · wird nur dieses eine Mal angezeigt" }
                    } else div("photo-none") { span { +"Noch kein Code erzeugt" } }
                    postForm(ctx, "$BASE/geraete/code") {
                        button(type = ButtonType.submit, classes = "btn btn-primary btn-wide") { icon("plus", "m"); +(if (code != null) "Neuen Code erzeugen" else "Kopplungscode erzeugen") }
                    }
                    ol("steps") {
                        li { span { +"Am Gerät: Einstellungen → Server und Abgleich." } }
                        li { span { +"Adresse des Servers, den Code und einen Namen eintragen, „Koppeln“." } }
                        li { span { +"Ist der Server leer, lädt das Gerät seinen Bestand hoch. Sonst übernimmt es den des Servers — nach Rückfrage, wenn es selbst Daten hat." } }
                    }
                    div("note") {
                        icon("lock", "m")
                        span("cap") { raw("<b>Gesperrt und doch das richtige Gerät?</b><br>"); +"Mit einem frischen Code meldet es sich unter „Neu anmelden“ wieder an. Was es inzwischen gebucht hat, wartet und geht danach hoch." }
                    }
                }
            }
        }
    }
}

private fun FlowContent.auditTable(ctx: PageContext, entries: List<AuditLog.Entry>) = table("t") {
    tbody {
        for (e in entries) tr {
            td("c-muted tnum nowrap cap") { +"${ctx.day(e.at)} ${ctx.time(e.at)}" }
            td { twoLine(listOf(ACTIONS[e.action] ?: e.action, e.subject.takeIf { it.isNotBlank() }?.let { "„$it“" }).filterNotNull().joinToString(": "), listOf(e.actor, e.detail).filter { it.isNotBlank() }.joinToString(" · ")) }
        }
    }
}

private fun HTML.auditPage(ctx: PageContext, entries: List<AuditLog.Entry>) =
    shell(ctx, Area.AUDIT, "Protokoll", "Wer hat wann was getan — die letzten ${entries.size} Einträge, nichts davon lässt sich ändern") {
        panel { if (entries.isEmpty()) p("empty") { +"Noch nichts passiert." } else auditTable(ctx, entries) }
    }

// ----------------------------------------------------------------- Benutzer

private fun FlowContent.roleSelect(selected: Role?) = label("field") {
    span { +"Rolle" }
    select {
        name = "rolle"
        for (role in Role.entries) option { value = role.name; if (role == selected) this.selected = true; +role.label }
    }
}

private fun HTML.usersPage(ctx: PageContext, users: List<WebUser>, notice: String?, problem: String?) =
    shell(ctx, Area.USERS, "Benutzer und Rollen", "Chargen wechseln jedes Semester — Zugänge wechseln mit") {
        flash(notice, problem)
        div("cols cols-side") {
            panel {
                panelHead("Zugänge")
                table("t") {
                    thead { tr { th { +"Name" }; th(classes = "hide-sm") { +"Zuletzt angemeldet" }; th { +"Status" }; th { span("sr") { +"Ändern" } } } }
                    tbody {
                        for (u in users) tr {
                            td { div("row") { span("avatar avatar-s") { +u.initials }; twoLine(u.displayName, "${u.role.label} · ${u.login}") } }
                            td("c-muted nowrap hide-sm") { +ctx.friendly(u.lastLoginAt) }
                            td {
                                when {
                                    !u.active -> chip("Gesperrt", "neutral", "lock")
                                    u.validUntil != null && ctx.today.isAfter(u.validUntil) -> chip("abgelaufen", "warn")
                                    u.validUntil != null -> chip("bis ${u.validUntil}", "neutral")
                                    else -> chip("Aktiv", "ok", "check")
                                }
                            }
                            td("num") {
                                details {
                                    summary("btn") { +"Ändern" }
                                    postForm(ctx, "$BASE/benutzer/${u.id}", "stack-tight confirm") {
                                        roleSelect(u.role)
                                        label("field") { span { +"Zugang bis (leer: unbegrenzt)" }; input(InputType.date, name = "bis") { value = u.validUntil?.toString().orEmpty() } }
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
            div("stack") {
                panel {
                    div("panel-body") {
                        h2("title-m") { +"Zugang anlegen" }
                        postForm(ctx, "$BASE/benutzer", "stack-tight") {
                            label("field") { span { +"Name" }; input(InputType.text, name = "name") { required = true } }
                            label("field") { span { +"Anmeldename" }; input(InputType.text, name = "login") { required = true; attributes["autocomplete"] = "off" } }
                            roleSelect(Role.VORSTAND)
                            label("field") { span { +"Zugang bis (leer: unbegrenzt)" }; input(InputType.date, name = "bis") }
                            label("field") { span { +"Erstes Passwort, mindestens ${Accounts.MIN_PASSWORD} Zeichen" }; input(InputType.password, name = "passwort") { required = true; attributes["autocomplete"] = "new-password" } }
                            button(type = ButtonType.submit, classes = "btn btn-primary btn-wide") { icon("plus", "m"); +"Anlegen" }
                        }
                    }
                }
                panel {
                    panelHead("Was welche Rolle sieht")
                    table("t t-tight") { tbody { for (role in Role.entries) tr { td { twoLine(role.label, role.hint) } } } }
                }
            }
        }
    }

// ------------------------------------------------------------ Einstellungen

private val MONTHS = listOf("Jänner", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")

private fun HTML.settingsPage(ctx: PageContext, notice: String?, problem: String?) =
    shell(ctx, Area.SETTINGS, "Einstellungen", "Name, Farbe und Rechnungsjahr der Verbindung — gilt für die Verwaltung, nicht für die Tablets") {
        flash(notice, problem)
        panel {
            postForm(ctx, "$BASE/einstellungen", "panel-body") {
                div("form-grid") {
                    label("field") { span { +"Name der Verbindung" }; input(InputType.text, name = "name") { value = ctx.verein.name; maxLength = "80" } }
                    label("field") {
                        span { +"Rechnungsjahr beginnt im" }
                        select { name = "monat"; MONTHS.forEachIndexed { i, m -> option { value = "${i + 1}"; if (i + 1 == ctx.verein.fiscalStartMonth) selected = true; +m } } }
                    }
                }
                div("field") {
                    span { +"Vereinsfarbe — färbt Navigation und Avatare, nie Geld, Warnung oder Fehler" }
                    div("swatches") {
                        for ((name, hex) in VereinSettings.PRESETS) label("swatch") {
                            input(InputType.radio, name = "farbe") { value = hex; checked = hex.equals(ctx.verein.accent, ignoreCase = true) }
                            span { raw("""<svg viewBox="0 0 24 24" aria-hidden="true"><rect width="24" height="24" fill="$hex"/></svg>"""); +name }
                        }
                    }
                }
                div { button(type = ButtonType.submit, classes = "btn btn-primary") { +"Speichern" } }
            }
        }
        panel {
            div("panel-body") {
                h2("title-m") { +"Server" }
                p("muted") { +"Zeitzone ${ctx.zone.id} · Serverzeit ${ctx.now.atOffset(ZoneOffset.UTC).toLocalTime().withNano(0)} UTC. Sicherung und Zertifikat sind Sache des Betriebs; die Anleitung steht in server/README.md." }
            }
        }
    }
