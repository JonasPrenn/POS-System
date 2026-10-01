package com.example.vereins_kassensystem.server.web

import com.example.vereins_kassensystem.AppVersion
import kotlinx.html.pre
import com.example.vereins_kassensystem.server.devices.DeviceRecord
import com.example.vereins_kassensystem.server.payments.PaymentProblem
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
import kotlinx.html.hiddenInput
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
import kotlinx.html.textArea
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

internal val ACTIONS = mapOf(
    "login" to "Angemeldet", "login.failed" to "Anmeldung fehlgeschlagen", "login.test" to "Testanmeldung", "verein.create" to "Verein angelegt", "verein.update" to "Verein geändert", "support.login" to "Testanmeldung in einem Verein", "minversion.set" to "Mindestversion der App gesetzt",
    "device.code" to "Kopplungscode erzeugt", "device.register" to "Gerät gekoppelt", "device.revoke" to "Gerät gesperrt",
    "user.create" to "Benutzer angelegt", "user.update" to "Benutzer geändert", "user.password" to "Passwort gesetzt",
    "settings.save" to "Einstellungen geändert",
    "member.create" to "Mitglied angelegt", "member.update" to "Mitglied geändert", "member.delete" to "Mitglied gelöscht", "member.import" to "Mitglieder importiert", "update.check" to "Updates gesucht", "update.install" to "Update installiert", "member.block" to "Deckel gesperrt", "member.unblock" to "Sperre aufgehoben",
    "tab.topup" to "Deckel aufgeladen", "tab.correction" to "Deckel korrigiert",
    "purchase.create" to "Beleg erfasst", "purchase.update" to "Belegdaten geändert", "purchase.paid" to "Beleg bezahlt",
    "profile.update" to "Profil geändert", "statement.run" to "Abrechnungslauf erstellt", "statement.sent" to "Abrechnung versandt",
    "statement.reminded" to "Erinnerung", "statement.cancelled" to "Abrechnung storniert", "statement.paid" to "Zahlung eingegangen",
    "bank.import" to "Kontoauszug eingelesen", "bank.ignored" to "Bankumsatz ohne Zuordnung",
    "product.create" to "Produkt angelegt", "product.update" to "Produkt geändert", "product.retire" to "Produkt aus dem Sortiment genommen",
    "variant.create" to "Variante angelegt", "variant.update" to "Variante geändert", "variant.remove" to "Variante entfernt",
    "component.set" to "Rezeptur geändert", "component.remove" to "Rezepturzeile entfernt",
    "mail.accept" to "Rechnung aus dem Posteingang übernommen", "mail.reject" to "Mail im Posteingang abgelehnt", "mail.untrust" to "Absender gesperrt",
    "deposit.kind" to "Pfandgebinde angelegt oder geändert", "deposit.move" to "Pfand gebucht",
    "books.bank" to "Bankstand eingetragen", "books.bundle" to "Prüfermappe erzeugt",
    "category.create" to "Kategorie angelegt", "category.update" to "Kategorie geändert", "category.remove" to "Kategorie entfernt",
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
        call.guarded(web, Area.SETTINGS) { ctx ->
            val link = portalLink(call.publicBase(web.directory), web.tenant)
            call.html { settingsPage(ctx, web, call.request.queryParameters["hinweis"], call.request.queryParameters["fehler"], link) }
        }
    }
    post("/einstellungen") {
        call.guardedPost(web, Area.SETTINGS) { ctx, form ->
            val outcome = try {
                when (form["teil"]) {
                    "bank" -> { web.settings.saveBank(form["inhaber"].orEmpty(), form["iban"].orEmpty(), form["bic"].orEmpty(), form["text"].orEmpty()); web.audit.record(ctx.user, "settings.save", detail = "Bankverbindung und Text der Abrechnung") }
                    "smtp" -> { web.settings.saveSmtp(form["host"].orEmpty(), form["port"]?.toIntOrNull() ?: 587, form["benutzer"].orEmpty(), form["passwort"], form["absender"].orEmpty(), form["starttls"] == "1"); web.audit.record(ctx.user, "settings.save", detail = "E-Mail-Versand") }
                    "update" -> {
                        if (ctx.user.role != Role.ADMIN) return@guardedPost call.forbidden(ctx, "Updates sind Sache des Administrators.")
                        // Mit Systemverwaltung gehören Updates dem Hauptadmin: Sie starten den Server für alle Vereine neu.
                        if (ctx.withSystem) return@guardedPost call.forbidden(ctx, "Updates macht der Hauptadmin in der Systemverwaltung ($SYSTEM_BASE).")
                        when (form["aktion"]) {
                            "pruefen" -> { web.updates.request("check"); web.audit.record(ctx.user, "update.check", detail = "Suche angestoßen") }
                            "installieren" -> { web.updates.request("install"); web.audit.record(ctx.user, "update.install", detail = "Installation angestoßen: ${web.updates.status().latest ?: "?"}") }
                            else -> {
                                val mode = Updates.Mode.entries.firstOrNull { it.name == form["modus"] } ?: Updates.Mode.CHECK
                                web.updates.saveSettings(mode, form["abstand"]?.toIntOrNull() ?: 60)
                                web.audit.record(ctx.user, "settings.save", detail = "Updates: ${mode.label}, alle ${form["abstand"] ?: "60"} Minuten")
                            }
                        }
                    }
                    "kuerzel" -> {
                        if (ctx.user.role != Role.ADMIN) return@guardedPost call.forbidden(ctx, "Das Kürzel ändert die Anmeldung aller — das ist Sache des Administrators.")
                        val before = web.tenant.info.slug
                        web.directory.rename(web.tenant, slugText = form["kuerzel"].orEmpty())
                        if (web.tenant.info.slug != before) web.audit.record(ctx.user, "settings.save", detail = "Kürzel: $before → ${web.tenant.info.slug}")
                    }
                    "online" -> {
                        val online = ctx.verein.online
                        val enabled = form["aktiv"] == "1"
                        val remove = form["schluessel_entfernen"] == "1"
                        val key = if (remove) "" else form["schluessel"]?.trim()?.takeIf { it.isNotEmpty() } ?: online.apiKey
                        val merchant = form["haendler"].orEmpty().trim()
                        if (enabled && !ctx.verein.smtp.configured) throw AccountProblem("Für die Anmeldelinks braucht es den E-Mail-Versand — unten bei E-Mail-Versand eintragen.")
                        // Geprüft wird bei SumUp selbst: Stimmt der Schlüssel, passt der Händlercode, und was ist freigeschaltet?
                        val methods = if (key.isNotBlank() && merchant.isNotBlank()) {
                            try { web.portal.check(key, merchant) } catch (e: PaymentProblem) { throw AccountProblem(e.message ?: "SumUp lehnt ab.") }
                        } else null
                        if (enabled && methods == null) throw AccountProblem("Zum Einschalten braucht es den API-Schlüssel und den Händlercode von SumUp.")
                        web.settings.saveOnline(
                            enabled, form["schluessel"], remove, merchant, form["betraege"].orEmpty(),
                            form["min"]?.toIntOrNull() ?: VereinSettings.DEFAULT_MIN, form["max"]?.toIntOrNull() ?: VereinSettings.DEFAULT_MAX, methods,
                        )
                        web.audit.record(ctx.user, "settings.save", detail = "Online aufladen: ${if (enabled) "an" else "aus"}${if (remove) ", Schlüssel entfernt" else ""}")
                    }
                    "tablets" -> { web.settings.saveTablets(form["sumup"], form["sumup_entfernen"] == "1", form["sicherung"] == "1"); web.audit.record(ctx.user, "settings.save", detail = "Tablets: SumUp-Schlüssel und Sicherung") }
                    "imap" -> { web.settings.saveImap(form["host"].orEmpty(), form["port"]?.toIntOrNull() ?: 993, form["benutzer"].orEmpty(), form["passwort"], form["ordner"].orEmpty(), form["aktiv"] == "1"); web.audit.record(ctx.user, "settings.save", detail = "E-Mail-Empfang") }
                    else -> {
                        web.settings.save(form["name"].orEmpty(), form["farbe"].orEmpty(), form["monat"]?.toIntOrNull() ?: 1, form["anschrift"].orEmpty())
                        // Die Systemverwaltung führt den Verein unter seinem Namen.
                        if (ctx.withSystem && form["name"].orEmpty().isNotBlank()) web.directory.rename(web.tenant, name = form["name"].orEmpty())
                        web.audit.record(ctx.user, "settings.save", detail = "Name, Vereinsfarbe, Rechnungsjahr, Anschrift")
                    }
                }
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
                        thead { tr { th { +"Gerät" }; th(classes = "hide-sm") { +"Zuletzt gesehen" }; th(classes = "hide-sm") { +"Stand" }; th(classes = "hide-sm") { +"Status" }; th { span("sr") { +"Aktion" } } } }
                        tbody {
                            for (d in devices.sortedBy { it.revoked }) tr {
                                val status: FlowContent.() -> Unit = { if (d.revoked) chip("Gesperrt", "neutral", "lock") else deviceChip(ctx, d.lastSeenAt?.toInstant()) }
                                td("fill") { div("row") { span("c-muted") { icon("tablet") }; twoLine(d.label, "${platformName(d.platform)} · seit ${ctx.dayYear(d.createdAt.toInstant())}", status) } }
                                td("nowrap hide-sm") { +ctx.ago(d.lastSeenAt?.toInstant()) }
                                td("c-muted tnum hide-sm") { +"${d.lastAckSeq}" }
                                td("hide-sm") { status() }
                                td("num") {
                                    if (!d.revoked) dialog("$BASE/geraete/${d.id}/sperren", "btn btn-danger", "Sperren", "Gerät sperren") {
                                        postForm(ctx, "$BASE/geraete/${d.id}/sperren", "stack-tight") {
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
                                dialog("$BASE/benutzer/${u.id}", "btn", "Ändern", "Benutzer ändern") {
                                    postForm(ctx, "$BASE/benutzer/${u.id}", "stack-tight") {
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
                        ctx.tenant?.takeIf { ctx.withSystem }?.let { p("cap") { +"Angemeldet wird mit Anmeldename@${it.slug}." } }
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

/**
 * Updates aus dem Git-Repo (Betrieb): was läuft, was im Repo ist, und was der Updater davon
 * halten soll. Nur für den Administrator — es startet den Dienst neu.
 */
internal fun FlowContent.updatesPanel(csrf: String, action: String, updates: Updates) = panel {
    val status = updates.status()
    val settings = updates.settings()
    val available = updates.updateAvailable(status)
    div("panel-body stack-tight") {
        div("row-between") {
            h2("title-m") { +"Updates" }
            when {
                !updates.available -> chip("kein Updater", "neutral")
                status.state == "installing" -> chip("wird installiert", "warn")
                status.state == "failed" -> chip("fehlgeschlagen", "error", "alert")
                available -> chip("Update verfügbar", "deckel")
                status.latest != null -> chip("aktuell", "ok", "check")
                else -> chip("noch nicht gesucht", "neutral")
            }
        }
        if (!updates.available) p("muted") { +"Kein Updater aufgestellt. Läuft der Dienst über server/deploy/compose.yaml mit dem Dienst „updater“, steht hier, was im Git-Repo neu ist — und ein Klick spielt es ein." }
        else {
        p("muted") {
            +"Läuft: Version ${AppVersion.LABEL}, Stand ${updates.runningVersion}${updates.runningDate.takeIf { it.isNotBlank() }?.let { " vom ${it.take(10)}" }.orEmpty()}. "
            +(status.latest?.let { "Im Repo (${status.branch ?: "main"}): ${updates.label(status.latestVersion)?.let { v -> "Version $v, " }.orEmpty()}Stand $it${status.latestDate?.let { d -> " vom ${d.take(10)}" }.orEmpty()}${status.latestMessage?.takeIf { m -> m.isNotBlank() }?.let { m -> " — „$m“" }.orEmpty()}${if (available && status.behindCount > 0) ", ${count(status.behindCount, "Commit", "Commits")} voraus" else ""}. " } ?: "Noch nicht im Repo gesucht. ")
            +status.message
        }
        if (status.state == "failed" && status.log.isNotBlank()) pre("cap") { +status.log.takeLast(1200) }
        div("row wrap") {
            postForm(csrf, action) { hiddenInput(name = "teil") { value = "update" }; hiddenInput(name = "aktion") { value = "pruefen" }; button(type = ButtonType.submit, classes = "btn") { icon("search", "m"); +"Jetzt suchen" } }
            if (available && status.state != "installing") postForm(csrf, action) { hiddenInput(name = "teil") { value = "update" }; hiddenInput(name = "aktion") { value = "installieren" }; button(type = ButtonType.submit, classes = "btn btn-primary") { icon("upload", "m"); +"Jetzt installieren" } }
        }
        if (available) p("cap") { +"Installieren holt den Stand, baut den Dienst neu und startet ihn — ein paar Minuten, in denen die Verwaltung nicht antwortet. Die Tablets merken nur eine Pause im Abgleich; Datenbank und Belegfotos bleiben." }
        }
    }
    if (updates.available) postForm(csrf, action, "panel-body stack-tight") {
        hiddenInput(name = "teil") { value = "update" }
        span("label-m") { +"Von selbst" }
        for (mode in Updates.Mode.entries) label("check") {
            input(InputType.radio, name = "modus") { value = mode.name; checked = mode == settings.modeOrDefault }
            span { +mode.label; +" — "; span("cap") { +mode.hint } }
        }
        label("field") {
            span { +"Abstand der Suche" }
            select { name = "abstand"; for ((minutes, text) in listOf(15 to "alle 15 Minuten", 60 to "stündlich", 360 to "alle 6 Stunden", 1440 to "täglich", 10080 to "wöchentlich")) option { value = "$minutes"; if (minutes == settings.intervalMinutes) selected = true; +text } }
        }
        div { button(type = ButtonType.submit, classes = "btn btn-primary") { +"Speichern" } }
    }
}

/** Das Kürzel des Vereins: Es steht bei der Anmeldung hinter dem @ und ist auf dem Server einmalig. */
private fun FlowContent.kuerzelPanel(ctx: PageContext) = panel {
    val slug = ctx.tenant?.slug.orEmpty()
    postForm(ctx, "$BASE/einstellungen", "panel-body") {
        hiddenInput(name = "teil") { value = "kuerzel" }
        h2("title-m") { +"Anmeldung: Kürzel des Vereins" }
        p("muted") { +"Steht bei der Anmeldung hinter dem @, etwa ${ctx.user.login}@$slug. Jedes Kürzel gibt es auf diesem Server nur einmal; Anmeldenamen müssen dafür nur in diesem Verein eindeutig sein. Wer es ändert, ändert die Anmeldung aller — vorher Bescheid sagen." }
        div("form-grid") {
            label("field") { span { +"Kürzel: Kleinbuchstaben, Ziffern, Bindestrich" }; input(InputType.text, name = "kuerzel") { value = slug; maxLength = "30"; required = true; attributes["autocomplete"] = "off" } }
        }
        p("cap") { +"Testanmeldung: Mit ${ctx.user.login}#anmeldename@$slug und dem eigenen Passwort zeigt die Verwaltung, was dieser Zugang sieht. Jede Testanmeldung steht im Protokoll." }
        div { button(type = ButtonType.submit, classes = "btn btn-primary") { +"Speichern" } }
    }
}

private val MONTHS = listOf("Jänner", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")

/**
 * Online aufladen: das SumUp-Konto des Vereins (API-Schlüssel und Händlercode, nicht der Schlüssel
 * fürs Terminal), die Beträge, und wo Mitglieder ihren Deckel finden. Welche Zahlungsarten auf der
 * Bezahlseite stehen, entscheidet SumUp — die Seite zeigt, was beim letzten Prüfen freigeschaltet war.
 */
private fun FlowContent.onlinePanel(ctx: PageContext, web: Web, link: String) = panel {
    val online = ctx.verein.online
    postForm(ctx, "$BASE/einstellungen", "panel-body") {
        hiddenInput(name = "teil") { value = "online" }
        div("row-between") {
            h2("title-m") { +"Online aufladen" }
            when {
                online.usable -> chip("eingeschaltet", "ok", "check")
                online.configured -> chip("aus", "neutral")
                else -> chip("nicht eingerichtet", "neutral")
            }
        }
        p("muted") { +"Mitglieder laden ihren Deckel am PC oder Handy auf. Angemeldet wird mit einem Link an die E-Mail-Adresse aus dem Profil; bezahlt auf der Seite von SumUp, auf das SumUp-Konto des Vereins. Gebucht wird eine Aufladung mit Karte — die Tablets sehen sie beim nächsten Abgleich." }
        div("field") { span { +"Hier finden Mitglieder ihren Deckel — etwa als QR-Code an der Bude" }; span("copy") { +link } }
        if (online.configured) p("muted") {
            +"Auf der Bezahlseite: ${(listOf("Karte") + online.methods.map(Portal::methodLabel)).joinToString(", ")}."
            val missing = listOf("apple_pay" to "Apple Pay", "google_pay" to "Google Pay", "eps" to "EPS").filter { it.first !in online.methods }.map { it.second }
            if (missing.isNotEmpty()) +" ${missing.joinToString(" und ")} ${if (missing.size == 1) "erscheint" else "erscheinen"}, sobald der SumUp-Support ${if (missing.size == 1) "es" else "sie"} für das Konto freischaltet; danach hier einmal speichern."
        }
        div("form-grid") {
            label("field") { span { +(if (online.apiKey.isNotEmpty()) "SumUp API-Schlüssel (leer: bleibt)" else "SumUp API-Schlüssel") }; input(InputType.password, name = "schluessel") { attributes["autocomplete"] = "off" } }
            label("field") { span { +"Händlercode, etwa MC4XYZ8B" }; input(InputType.text, name = "haendler") { value = online.merchantCode; attributes["autocomplete"] = "off" } }
            label("field") { span { +"Beträge zum Antippen, in €" }; input(InputType.text, name = "betraege") { value = online.presets.joinToString(", ") } }
            label("field") { span { +"Kleinster und größter Betrag, in €" }; div("row") { input(InputType.number, name = "min") { value = "${online.min}"; attributes["min"] = "1" }; input(InputType.number, name = "max") { value = "${online.max}"; attributes["min"] = "1" } } }
        }
        if (online.apiKey.isNotEmpty()) label("check") { input(InputType.checkBox, name = "schluessel_entfernen") { value = "1" }; span { +"Schlüssel entfernen — dann geht online nichts mehr" } }
        label("check") { input(InputType.checkBox, name = "aktiv") { value = "1"; checked = online.enabled }; span { +"Mitglieder können online aufladen" } }
        p("cap") { +"Den API-Schlüssel legt man in SumUp unter Einstellungen, Für Entwickler, API-Schlüssel an. Für einen Verein schaltet der SumUp-Support Online-Zahlungen frei. Zum Ausprobieren ohne echtes Geld gibt es bei SumUp ein Testkonto." }
        div { button(type = ButtonType.submit, classes = "btn btn-primary") { +"Speichern und bei SumUp prüfen" } }
    }
    val recent = web.portal.recent(8)
    if (recent.isNotEmpty()) table("t t-tight") {
        tbody {
            for (t in recent) tr {
                td { twoLine(t.memberName, listOf(ctx.friendly(t.createdAt), t.detail).filter { it.isNotBlank() }.joinToString(" · ")) }
                td("num") { span("money-s") { +euro(t.amount.toDouble()) } }
                td("num") {
                    when (t.status) {
                        "PAID" -> if (t.detail.startsWith("Bezahlt, aber nicht gebucht")) chip("von Hand buchen", "warn", "alert") else chip("gebucht", "ok", "check")
                        "PENDING" -> chip("offen", "neutral")
                        "EXPIRED" -> chip("abgebrochen", "neutral")
                        else -> chip("nicht bezahlt", "neutral")
                    }
                }
            }
        }
    }
}

private fun HTML.settingsPage(ctx: PageContext, web: Web, notice: String?, problem: String?, portalLink: String) =
    shell(ctx, Area.SETTINGS, "Einstellungen", "Name, Farbe und Rechnungsjahr der Verbindung — und was die Tablets von hier bekommen") {
        flash(notice, problem)
        panel {
            postForm(ctx, "$BASE/einstellungen", "panel-body") {
                p("muted") { +"Name und Vereinsfarbe gelten auch für die gekoppelten Tablets — sie kommen mit dem nächsten Abgleich dort an und sind am Gerät dann nur zu sehen." }
                div("form-grid") {
                    label("field") { span { +"Name der Verbindung" }; input(InputType.text, name = "name") { value = ctx.verein.name; maxLength = "80" } }
                    label("field") {
                        span { +"Rechnungsjahr beginnt im" }
                        select { name = "monat"; MONTHS.forEachIndexed { i, m -> option { value = "${i + 1}"; if (i + 1 == ctx.verein.fiscalStartMonth) selected = true; +m } } }
                    }
                }
                label("field") { span { +"Anschrift der Bude, wie sie auf den Kontoauszug kommt (eine Zeile je Zeile)" }; textArea(classes = "input") { name = "anschrift"; rows = "3"; +ctx.verein.address } }
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
            postForm(ctx, "$BASE/einstellungen", "panel-body") {
                hiddenInput(name = "teil") { value = "tablets" }
                h2("title-m") { +"Tablets: Kartenzahlung und Sicherung" }
                p("muted") { +"Gilt für alle gekoppelten Tablets, mit dem nächsten Abgleich; am Gerät ist beides dann nur zu sehen. ${if (ctx.verein.sumUpKey.isNotEmpty()) "Ein SumUp-Schlüssel ist hinterlegt." else "Ohne SumUp-Schlüssel bleibt an der Theke Bar und Deckel."}" }
                div("form-grid") {
                    label("field") { span { +(if (ctx.verein.sumUpKey.isNotEmpty()) "SumUp Affiliate Key (leer: bleibt)" else "SumUp Affiliate Key") }; input(InputType.password, name = "sumup") { attributes["autocomplete"] = "off" } }
                }
                if (ctx.verein.sumUpKey.isNotEmpty()) label("check") { input(InputType.checkBox, name = "sumup_entfernen") { value = "1" }; span { +"Schlüssel entfernen — die Tablets verlieren die Kartenzahlung" } }
                label("check") { input(InputType.checkBox, name = "sicherung") { value = "1"; checked = ctx.verein.tabletBackup }; span { +"Tablets sichern täglich von selbst — den Ordner dafür wählt man am Gerät" } }
                div { button(type = ButtonType.submit, classes = "btn btn-primary") { +"Speichern" } }
            }
        }
        onlinePanel(ctx, web, portalLink)
        panel {
            postForm(ctx, "$BASE/einstellungen", "panel-body") {
                hiddenInput(name = "teil") { value = "bank" }
                h2("title-m") { +"Bankverbindung und Abrechnung" }
                p("muted") { +"Kommt auf jeden Kontoauszug, mit QR-Code zum Bezahlen. Ohne IBAN steht dort nur der Verwendungszweck." }
                div("form-grid") {
                    label("field") { span { +"Kontoinhaber" }; input(InputType.text, name = "inhaber") { value = ctx.verein.bank.holder; maxLength = "70" } }
                    label("field") { span { +"IBAN" }; input(InputType.text, name = "iban") { value = ctx.verein.bank.iban.chunked(4).joinToString(" "); placeholder = "AT.. .... .... .... ...."; attributes["autocomplete"] = "off" } }
                    label("field") { span { +"BIC (nur nötig, wenn die Bank ihn verlangt)" }; input(InputType.text, name = "bic") { value = ctx.verein.bank.bic; maxLength = "11" } }
                }
                label("field") { span { +"Text unter jedem Auszug (etwa: Fragen an den Kassier, Telefon)" }; textArea(classes = "input") { name = "text"; rows = "2"; +ctx.verein.statementText } }
                div { button(type = ButtonType.submit, classes = "btn btn-primary") { +"Speichern" } }
            }
        }
        panel {
            postForm(ctx, "$BASE/einstellungen", "panel-body") {
                hiddenInput(name = "teil") { value = "smtp" }
                h2("title-m") { +"E-Mail-Versand" }
                p("muted") { +"Der SMTP-Zugang des Vereins, mit dem Abrechnungen und Erinnerungen verschickt werden. ${if (ctx.verein.smtp.configured) "Eingerichtet." else "Noch nicht eingerichtet — bis dahin gehen Abrechnungen in die Druckmappe."}" }
                div("form-grid") {
                    label("field") { span { +"SMTP-Server" }; input(InputType.text, name = "host") { value = ctx.verein.smtp.host; placeholder = "smtp.example.at" } }
                    label("field") { span { +"Port (587 mit STARTTLS, 465 mit SSL)" }; input(InputType.number, name = "port") { value = ctx.verein.smtp.port.toString() } }
                    label("field") { span { +"Benutzer" }; input(InputType.text, name = "benutzer") { value = ctx.verein.smtp.user; attributes["autocomplete"] = "off" } }
                    label("field") { span { +(if (ctx.verein.smtp.password.isNotEmpty()) "Passwort (leer: bleibt)" else "Passwort") }; input(InputType.password, name = "passwort") { attributes["autocomplete"] = "new-password" } }
                    label("field") { span { +"Absender" }; input(InputType.email, name = "absender") { value = ctx.verein.smtp.from; placeholder = "kassier@example.at" } }
                }
                label("check") { input(InputType.checkBox, name = "starttls") { value = "1"; checked = ctx.verein.smtp.startTls }; span { +"STARTTLS verwenden" } }
                div { button(type = ButtonType.submit, classes = "btn btn-primary") { +"Speichern" } }
            }
        }
        panel {
            postForm(ctx, "$BASE/einstellungen", "panel-body") {
                hiddenInput(name = "teil") { value = "imap" }
                h2("title-m") { +"E-Mail-Empfang: Rechnungen" }
                p("muted") { +"Dasselbe Postfach als Rechnungsadresse bei Brauerei und Händler: Jede Mail mit PDF landet im Posteingang unter Einkauf; von freigegebenen Absendern wird sie gleich ein Beleg. Benutzer und Passwort leer: die vom Versand. ${ctx.verein.mailLastPoll?.let { "Zuletzt abgerufen ${ctx.friendly(it)}" + (ctx.verein.mailLastError?.let { e -> " — $e" } ?: ", ohne Fehler") } ?: "Noch nie abgerufen."}" }
                div("form-grid") {
                    label("field") { span { +"IMAP-Server" }; input(InputType.text, name = "host") { value = ctx.verein.imap.host; placeholder = "imap.example.at" } }
                    label("field") { span { +"Port (993 mit SSL)" }; input(InputType.number, name = "port") { value = ctx.verein.imap.port.toString() } }
                    label("field") { span { +"Benutzer (leer: wie Versand)" }; input(InputType.text, name = "benutzer") { value = ctx.verein.imap.user; attributes["autocomplete"] = "off" } }
                    label("field") { span { +(if (ctx.verein.imap.password.isNotEmpty()) "Passwort (leer: bleibt)" else "Passwort (leer: wie Versand)") }; input(InputType.password, name = "passwort") { attributes["autocomplete"] = "new-password" } }
                    label("field") { span { +"Ordner" }; input(InputType.text, name = "ordner") { value = ctx.verein.imap.folder } }
                }
                label("check") { input(InputType.checkBox, name = "aktiv") { value = "1"; checked = ctx.verein.imap.enabled }; span { +"Alle 10 Minuten abrufen" } }
                div { button(type = ButtonType.submit, classes = "btn btn-primary") { +"Speichern" } }
            }
        }
        if (ctx.user.role == Role.ADMIN && ctx.withSystem) kuerzelPanel(ctx)
        if (ctx.user.role == Role.ADMIN && !ctx.withSystem) updatesPanel(ctx.session.csrf, "$BASE/einstellungen", web.updates)
        panel {
            div("panel-body") {
                h2("title-m") { +"Server" }
                p("muted") { +"Version ${AppVersion.LABEL} · Stand ${web.updates.runningVersion}${web.updates.runningDate.takeIf { it.isNotBlank() }?.let { " vom ${it.take(10)}" }.orEmpty()} · Zeitzone ${ctx.zone.id} · Serverzeit ${ctx.now.atOffset(ZoneOffset.UTC).toLocalTime().withNano(0)} UTC. Sicherung und Zertifikat sind Sache des Betriebs; die Anleitung steht in server/README.md.${if (ctx.withSystem) " Updates, weitere Vereine und die Mindestversion der App: in der Systemverwaltung unter $SYSTEM_BASE, für den Hauptadmin." else ""}" }
            }
        }
    }
