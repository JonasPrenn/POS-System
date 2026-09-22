package com.example.vereins_kassensystem.server.web

import io.ktor.server.response.header
import io.ktor.server.application.ApplicationCall
import kotlinx.html.textArea
import kotlinx.html.FormEncType
import io.ktor.server.response.respondText
import io.ktor.http.withCharset
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import com.example.vereins_kassensystem.data.Ledger
import com.example.vereins_kassensystem.platform.Ids
import com.example.vereins_kassensystem.ui.format.Money
import com.example.vereins_kassensystem.ui.format.Quantity
import io.ktor.http.encodeURLParameter
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.hiddenInput
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

// ------------------------------------------------------ Anmeldung und Einrichtung

fun HTML.loginPage(problem: String?, login: String, target: String?, notice: String? = null) = document("Anmelden") {
    div("gate") {
        panel("gate-card") {
            div("brand") {
                span("brand-mark") { icon("mark", "l") }
                div("brand-text") {
                    span("title-m") { +"VereinsDeckel" }
                    span("cap") { +"Verwaltung" }
                }
            }
            notice?.let { div("note note-ok") { icon("check", "m"); span { +it } } }
            problem?.let { div("note note-error") { icon("alert", "m"); span { +it } } }
            form(action = "$BASE/anmelden", method = FormMethod.post) {
                target?.let { hiddenInput(name = "weiter") { value = it } }
                label("field") {
                    span { +"Anmeldename" }
                    input(InputType.text, name = "login") { value = login; required = true; autoFocus = true; attributes["autocomplete"] = "username" }
                }
                label("field") {
                    span { +"Passwort" }
                    input(InputType.password, name = "passwort") { required = true; attributes["autocomplete"] = "current-password" }
                }
                button(type = ButtonType.submit, classes = "btn btn-primary btn-wide") { +"Anmelden" }
            }
            p("cap") { +"Zugänge vergibt der Administrator der Verbindung. Geräte an der Theke melden sich nicht hier an, sondern mit einem Kopplungscode." }
        }
    }
}

fun HTML.setupPage(problem: String?, name: String = "", login: String = "") = document("Einrichten") {
    div("gate") {
        panel("gate-card") {
            div("brand") {
                span("brand-mark") { icon("mark", "l") }
                div("brand-text") {
                    span("title-m") { +"Verwaltung einrichten" }
                    span("cap") { +"Einmalig: der erste Administrator" }
                }
            }
            p("muted") { +"Es gibt noch keinen Benutzer. Wer den Verwaltungsschlüssel des Servers kennt, legt hier den ersten an; danach ist diese Seite zu." }
            problem?.let { div("note note-error") { icon("alert", "m"); span { +it } } }
            form(action = "$BASE/einrichten", method = FormMethod.post) {
                label("field") {
                    span { +"Verwaltungsschlüssel des Servers" }
                    input(InputType.password, name = "schluessel") { required = true; attributes["autocomplete"] = "off" }
                }
                label("field") {
                    span { +"Dein Name" }
                    input(InputType.text, name = "name") { value = name; required = true; attributes["autocomplete"] = "name" }
                }
                label("field") {
                    span { +"Anmeldename" }
                    input(InputType.text, name = "login") { value = login; required = true; attributes["autocomplete"] = "username" }
                }
                label("field") {
                    span { +"Passwort, mindestens ${Accounts.MIN_PASSWORD} Zeichen" }
                    input(InputType.password, name = "passwort") { required = true; attributes["autocomplete"] = "new-password" }
                }
                label("field") {
                    span { +"Passwort wiederholen" }
                    input(InputType.password, name = "passwort2") { required = true; attributes["autocomplete"] = "new-password" }
                }
                button(type = ButtonType.submit, classes = "btn btn-primary btn-wide") { +"Administrator anlegen" }
            }
        }
    }
}

private suspend fun ApplicationCall.membersView(web: Web, ctx: PageContext, importPlan: MemberCsv.Plan?, notice: String?, problem: String?) {
    val all = web.reads.members()
    val selected = uuidOrNull(request.queryParameters["m"])?.let { id -> all.firstOrNull { it.id == id } }
    val shown = selected ?: all.firstOrNull()
    val categories = if (ctx.user.role.writesMembers) web.writes.categories() else emptyList()
    html {
        membersPage(ctx, all, request.queryParameters["q"].orEmpty(), request.queryParameters["f"] ?: "alle",
            shown, selected != null, shown?.let { web.reads.statement(it.id, 12) }.orEmpty(), categories,
            shown?.let { web.statements.profile(it.id) }, shown?.let { web.statements.statementsOf(it.id, 5) }.orEmpty(),
            notice, problem, importPlan)
    }
}

/** Anlegen, was die Vorschau versprochen hat — je Zeile ein Schreibvorgang, damit ein Fehler in Zeile 40 die 39 davor nicht mitnimmt. */
private fun applyImport(web: Web, user: WebUser, plan: MemberCsv.Plan): String {
    val categories = web.writes.categories().associateBy({ it.name.lowercase() }, { it.id }).toMutableMap()
    val membersById = web.reads.members().associateBy { it.id }
    var created = 0; var filled = 0; var newCategories = 0; var failed = 0
    for (line in plan.lines) {
        val row = line.row
        try {
            when (line.fate) {
                MemberCsv.Fate.NEW -> {
                    // Eine unbekannte Kategorie entsteht mit Limit 0; das Limit setzt der Kassier unter „Kategorien und Limits“.
                    val categoryId = row.category.takeIf { it.isNotBlank() }?.let { name ->
                        categories[name.lowercase()] ?: web.products.createCategory(user, name, "0").also { categories[name.lowercase()] = it; newCategories++ }
                    }
                    val id = web.writes.createMember(user, row.name, row.nickname, categoryId)
                    if (listOf(row.number, row.email, row.address, row.notes).any { it.isNotBlank() } || row.consent == true) {
                        web.statements.saveProfile(user, id, row.number, row.email, row.address, row.consent == true && row.email.isNotBlank(), row.notes)
                    }
                    created++
                }
                MemberCsv.Fate.EXISTS -> if (line.fills.isNotEmpty()) {
                    val id = line.existingId!!
                    if ("Couleurname" in line.fills) membersById[id]?.let { m -> web.writes.updateMember(user, id, m.name, row.nickname, m.category?.let { categories[it.lowercase()] }) }
                    val p = web.statements.profile(id)
                    val email = p.email.ifBlank { row.email }
                    web.statements.saveProfile(user, id, p.number.ifBlank { row.number }, email, p.address.ifBlank { row.address }, p.consentEmail || (row.consent == true && email.isNotBlank()), p.notes.ifBlank { row.notes })
                    filled++
                }
                MemberCsv.Fate.SKIPPED -> Unit
            }
        } catch (e: AccountProblem) { failed++ }
    }
    val skipped = plan.skipped + failed
    web.audit.record(user, "member.import", detail = "$created angelegt, $filled ergänzt, $skipped übersprungen, $newCategories Kategorien neu")
    return "${count(created, "Mitglied", "Mitglieder")} angelegt" +
        (if (newCategories > 0) ", ${count(newCategories, "Kategorie", "Kategorien")} neu" else "") +
        (if (filled > 0) ", ${count(filled, "Profil", "Profile")} ergänzt" else "") +
        (if (skipped > 0) ", $skipped übersprungen" else "") +
        ". Die Tablets bekommen die Mitglieder beim nächsten Abgleich."
}

// ------------------------------------------------------------------ Seiten

internal fun Route.mainPages(web: Web) {
    get {
        call.guarded(web, null) { ctx ->
            if (!ctx.user.role.may(Area.OVERVIEW)) return@guarded call.respondRedirect(homeOf(ctx.user.role))
            val week = web.reads.revenueByDay(ctx.today.minusDays(6), ctx.today)
            val members = web.reads.members()
            val stock = if (ctx.user.role.may(Area.STOCK)) web.reads.stock() else emptyList()
            call.html {
                // Abrechnungen, deren Deckel seit dem Stichtag aufgeladen wurde: vermutlich bezahlt, noch offen — das sieht der Kassier zuerst hier.
                val covered = if (ctx.user.role.may(Area.STATEMENTS)) web.statements.topUpsSince(web.statements.openStatements()).size else 0
                overviewPage(ctx, week, web.reads.tabTotals(members), web.reads.recentCheckouts(8), web.devices.list(), stock, if (ctx.user.role.may(Area.PURCHASES)) web.purchases.openDocuments() else emptyList(), covered)
            }
        }
    }
    get("/mitglieder") {
        call.guarded(web, Area.MEMBERS) { ctx -> call.membersView(web, ctx, null, call.request.queryParameters["hinweis"], call.request.queryParameters["fehler"]) }
    }
    // Die Liste als Datei — mit den Profilfeldern, also nur für den, der sie auch pflegt.
    get("/mitglieder.csv") {
        call.guarded(web, Area.MEMBERS) { ctx ->
            if (!ctx.user.role.writesMembers) return@guarded call.forbidden(ctx, "Die Liste mit Profilen bekommt der Kassier.")
            val members = web.reads.members()
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"mitglieder.csv\"")
            call.respondText(MemberCsv.export(members, web.statements.profiles(members.map { it.id })), ContentType.Text.CSV.withCharset(Charsets.UTF_8))
        }
    }
    // Erst die Vorschau: Die Datei wird gelesen und gegen den Bestand gerechnet, angelegt wird noch nichts.
    post("/mitglieder/import") {
        call.guardedUpload(web, Area.MEMBERS, back = "$BASE/mitglieder") { ctx, upload ->
            if (!ctx.user.role.writesMembers) return@guardedUpload call.forbidden(ctx, "Mitglieder importiert der Kassier.")
            try {
                val table = MemberCsv.parse(upload.bytes ?: throw AccountProblem("Keine Datei ausgewählt."))
                val members = web.reads.members()
                call.membersView(web, ctx, MemberCsv.plan(table, members, web.writes.categories(), web.statements.profiles(members.map { it.id })), null, null)
            } catch (e: AccountProblem) {
                call.respondRedirect("$BASE/mitglieder?fehler=${e.message.orEmpty().encodeURLParameter()}")
            }
        }
    }
    // Dann das Anlegen — noch einmal gegen den Bestand gerechnet, damit ein zweiter Klick niemanden doppelt anlegt.
    post("/mitglieder/import/anlegen") {
        call.guardedPost(web, Area.MEMBERS) { ctx, form ->
            if (!ctx.user.role.writesMembers) return@guardedPost call.forbidden(ctx, "Mitglieder importiert der Kassier.")
            val outcome = try {
                val table = MemberCsv.parse(form["daten"].orEmpty().toByteArray())
                val members = web.reads.members()
                "hinweis=" + applyImport(web, ctx.user, MemberCsv.plan(table, members, web.writes.categories(), web.statements.profiles(members.map { it.id }))).encodeURLParameter()
            } catch (e: AccountProblem) { "fehler=" + e.message.orEmpty().encodeURLParameter() }
            call.respondRedirect("$BASE/mitglieder?$outcome")
        }
    }
    post("/mitglieder") {
        call.guardedPost(web, Area.MEMBERS) { ctx, form ->
            if (!ctx.user.role.writesMembers) return@guardedPost call.forbidden(ctx, "Mitglieder legt der Kassier an.")
            val target = try {
                val id = web.writes.createMember(ctx.user, form["name"].orEmpty(), form["vulgo"].orEmpty(), uuidOrNull(form["kategorie"]))
                "m=$id&hinweis=" + "Angelegt. Die Tablets bekommen das Mitglied beim nächsten Abgleich.".encodeURLParameter()
            } catch (e: AccountProblem) {
                "fehler=" + e.message.orEmpty().encodeURLParameter()
            }
            call.respondRedirect("$BASE/mitglieder?$target")
        }
    }
    post("/mitglieder/{id}") {
        call.guardedPost(web, Area.MEMBERS) { ctx, form ->
            if (!ctx.user.role.writesMembers) return@guardedPost call.forbidden(ctx, "Mitglieder ändert der Kassier.")
            val id = uuidOrNull(call.parameters["id"]) ?: return@guardedPost call.respondRedirect("$BASE/mitglieder")
            val outcome = try {
                web.writes.updateMember(ctx.user, id, form["name"].orEmpty(), form["vulgo"].orEmpty(), uuidOrNull(form["kategorie"]))
                "hinweis=" + "Gespeichert.".encodeURLParameter()
            } catch (e: AccountProblem) {
                "fehler=" + e.message.orEmpty().encodeURLParameter()
            }
            call.respondRedirect("$BASE/mitglieder?m=$id&$outcome")
        }
    }
    post("/mitglieder/{id}/loeschen") {
        call.guardedPost(web, Area.MEMBERS) { ctx, _ ->
            if (!ctx.user.role.writesMembers) return@guardedPost call.forbidden(ctx, "Mitglieder löscht der Kassier.")
            val id = uuidOrNull(call.parameters["id"]) ?: return@guardedPost call.respondRedirect("$BASE/mitglieder")
            val outcome = try {
                web.writes.deleteMember(ctx.user, id)
                "hinweis=" + "Gelöscht. Die Buchungen behalten den Namen; die Tablets nehmen das Mitglied beim nächsten Abgleich aus der Liste.".encodeURLParameter()
            } catch (e: AccountProblem) {
                "m=$id&fehler=" + e.message.orEmpty().encodeURLParameter()
            }
            call.respondRedirect("$BASE/mitglieder?$outcome")
        }
    }
    post("/mitglieder/{id}/sperre") {
        call.guardedPost(web, Area.MEMBERS) { ctx, form ->
            if (!ctx.user.role.writesMembers) return@guardedPost call.forbidden(ctx, "Deckel sperrt der Kassier.")
            val id = uuidOrNull(call.parameters["id"]) ?: return@guardedPost call.respondRedirect("$BASE/mitglieder")
            val outcome = try {
                val reason = form["grund"].orEmpty()
                web.writes.blockMember(ctx.user, id, reason)
                "hinweis=" + (if (reason.isBlank()) "Sperre aufgehoben. Die Tablets sehen es beim nächsten Abgleich." else "Deckel gesperrt. Die Theke zeigt den Grund beim nächsten Abgleich.").encodeURLParameter()
            } catch (e: AccountProblem) {
                "fehler=" + e.message.orEmpty().encodeURLParameter()
            }
            call.respondRedirect("$BASE/mitglieder?m=$id&$outcome")
        }
    }
    post("/mitglieder/{id}/buchung") {
        call.guardedPost(web, Area.MEMBERS) { ctx, form ->
            if (!ctx.user.role.writesMembers) return@guardedPost call.forbidden(ctx, "Auf Deckel bucht der Kassier.")
            val id = uuidOrNull(call.parameters["id"]) ?: return@guardedPost call.respondRedirect("$BASE/mitglieder")
            val outcome = try {
                // Wer das Minus aus dem Platzhalter abtippt, meint es auch so.
                val amount = Money.parse(form["betrag"].orEmpty().replace('\u2212', '-')) ?: throw AccountProblem("Den Betrag bitte als Zahl, etwa 20 oder 12,50.")
                val booking = uuidOrNull(form["buchung"]) ?: throw AccountProblem("Das Formular ist unvollständig. Bitte die Seite neu laden.")
                val correction = form["art"] == "korrektur"
                val type = if (correction) "CORRECTION" else (TopUpKind.entries.firstOrNull { it.name == form["zahlart"] } ?: TopUpKind.BANK).paymentType
                val booked = web.writes.bookTab(ctx.user, id, booking, amount, type, form["notiz"].orEmpty())
                "hinweis=" + (if (booked) "Gebucht: ${euroSigned(Money.cents(amount))}. Die Tablets sehen es beim nächsten Abgleich." else "Diese Buchung war schon verbucht — nichts doppelt.").encodeURLParameter()
            } catch (e: AccountProblem) {
                "fehler=" + e.message.orEmpty().encodeURLParameter()
            }
            call.respondRedirect("$BASE/mitglieder?m=$id&$outcome")
        }
    }
    get("/berichte") {
        call.guarded(web, Area.REPORTS) { ctx ->
            val start = ctx.verein.fiscalStartMonth
            // Das laufende Rechnungsjahr beginnt im letzten Startmonat, der nicht in der Zukunft liegt.
            val current = if (ctx.today.monthValue >= start) ctx.today.year else ctx.today.year - 1
            val year = call.request.queryParameters["jahr"]?.toIntOrNull()?.takeIf { it in 2000..current } ?: current
            val first = web.reads.firstBookingYear()?.let { if (start > 1) it - 1 else it } ?: current
            val members = web.reads.members()
            val calendar = web.reads.months(YearMonth.of(ctx.today.year, 1))
            call.html { reportsPage(ctx, year, (maxOf(first, current - 6)..current).toList(), web.reads.months(YearMonth.of(year, start)), web.reads.tabTotals(members), calendar) }
        }
    }
    get("/mehr") {
        call.guarded(web, null) { ctx ->
            call.html {
                shell(ctx, null, "Mehr", "Alle Bereiche, die deine Rolle sehen darf") {
                    panel {
                        div("more-list") {
                            for (entry in NAV.flatMap { it.second }.filter { ctx.user.role.may(it.area) }) {
                                a(href = entry.path) { icon(entry.icon); span("grow") { +entry.label }; icon("chevron", "s") }
                            }
                        }
                    }
                    panel {
                        div("panel-body") {
                            div("row") {
                                span("avatar") { +ctx.user.initials }
                                twoLine(ctx.user.displayName, ctx.user.role.label)
                            }
                            postForm(ctx, "$BASE/abmelden") { button(type = ButtonType.submit, classes = "btn btn-wide") { icon("logout", "m"); +"Abmelden" } }
                        }
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------- Übersicht

private fun FlowContent.legend(revenue: Revenue) = div("legend cap") {
    span { span("dot bg-primary"); +"Bar "; span("tnum") { +euro(revenue.cash) } }
    span { span("dot bg-tertiary"); +"Karte "; span("tnum") { +euro(revenue.card) } }
    span { span("dot bg-secondary"); +"Deckel "; span("tnum") { +euro(revenue.tab) } }
}

/** Sieben gestapelte Säulen als SVG: Deckel oben, Karte, Bar unten — wie im Balken daneben. */
private fun FlowContent.weekChart(ctx: PageContext, week: List<DayRevenue>) {
    val max = week.maxOf { it.revenue.total }.takeIf { it > 0 } ?: 1.0
    // Breiter als hoch, wie die Zelle, in der es steht: So skaliert es gleichmäßig und die Tagesnamen bleiben lesbar.
    val width = 280.0
    val slot = width / week.size
    val sb = StringBuilder("""<svg class="chart" viewBox="0 0 280 64" role="img" aria-label="Umsatz je Tag, letzte sieben Tage">""")
    week.forEachIndexed { i, day ->
        val x = i * slot + slot * 0.18
        val w = slot * 0.64
        var y = 46.0
        if (day.revenue.total <= 0) {
            sb.append("""<rect class="f-hair" x="${x.svg()}" y="45" width="${w.svg()}" height="1"/>""")
        } else {
            for ((value, fill) in listOf(day.revenue.cash to "f-primary", day.revenue.card to "f-tertiary", day.revenue.tab to "f-secondary")) {
                val h = value / max * 44
                if (h <= 0) continue
                y -= h
                sb.append("""<rect class="$fill" x="${x.svg()}" y="${y.svg()}" width="${w.svg()}" height="${h.svg()}"/>""")
            }
        }
        val name = day.day.dayOfWeek.getDisplayName(TextStyle.SHORT, AT).take(2)
        sb.append("""<text${if (day.day == ctx.today) " class=\"now\"" else ""} x="${(i * slot + slot / 2).svg()}" y="60">$name</text>""")
    }
    raw(sb.append("</svg>").toString())
}

private fun paymentChip(type: String, topUp: Boolean, refund: Boolean): Pair<String, String> = when {
    refund -> "Storno" to "neutral"
    type == "CASH" -> "Bar" to "bar"
    type == "CARD" -> "Karte" to "karte"
    type == Ledger.MEMBER_BALANCE -> "Deckel" to "deckel"
    topUp -> "Aufladung" to "deckel"
    else -> type to "neutral"
}

private fun HTML.overviewPage(
    ctx: PageContext, week: List<DayRevenue>, tabs: TabTotals, checkouts: List<Checkout>,
    devices: List<com.example.vereins_kassensystem.server.devices.DeviceRecord>, stock: List<StockLine>, open: List<Document>, coveredStatements: Int = 0,
) = shell(ctx, Area.OVERVIEW, "Übersicht", ctx.longDate()) {
    if (coveredStatements > 0) div("note note-warn") {
        icon("wallet", "m")
        span {
            +"${count(coveredStatements, "offene Abrechnung", "offene Abrechnungen")}, deren Deckel seit dem Stichtag aufgeladen wurde — vermutlich bezahlt. "
            a(href = "$BASE/abrechnung", classes = "link") { +"Zur Abrechnung"; icon("chevron", "s") }
        }
    }
    val today = week.last().revenue
    val weekTotal = week.fold(Revenue()) { a, d -> a + d.revenue }
    val quiet = week.filter { it.revenue.total <= 0 && it.day != ctx.today }
    val closed = if (quiet.size > 2) "${quiet.size} Tage" else quiet.joinToString(" und ") { it.day.dayOfWeek.getDisplayName(TextStyle.SHORT, AT).take(2) }

    panel {
        div("figures") {
            figure("Umsatz heute") {
                span("money-l c-primary") { +euro(today.total) }
                bar(listOf(today.cash to "f-primary", today.card to "f-tertiary", today.tab to "f-secondary").map { (v, f) -> (if (today.total > 0) v / today.total * 100 else 0.0) to f }, "Aufteilung nach Zahlart")
                legend(today)
            }
            figure("Letzte sieben Tage") {
                div("row-between") {
                    span("money-l") { +euro(weekTotal.total) }
                    if (closed.isNotEmpty()) span("cap") { +"$closed ohne Umsatz" }
                }
                weekChart(ctx, week)
            }
            figure("Außenstände") {
                span("money-l") { +euro(tabs.owedSum) }
                span("cap") { +"${count(tabs.owedCount, "Mitglied", "Mitglieder")} im Minus · Forderung des Vereins" }
                if (tabs.overLimit > 0) div { chip("${tabs.overLimit} über dem Limit", "error", "alert") }
            }
            figure("Guthaben auf Deckeln") {
                span("money-l c-secondary") { +euro(tabs.creditSum) }
                span("cap") { +"${count(tabs.creditCount, "Mitglied", "Mitglieder")} · Verbindlichkeit des Vereins" }
            }
        }
    }

    div("cols cols-main") {
        panel {
            panelHead("Letzte Buchungen") { span("cap") { +"kommen mit dem Abgleich der Geräte, nicht live" } }
            if (checkouts.isEmpty()) p("empty") { +"Noch keine Buchungen. Sobald ein gekoppeltes Gerät verkauft und abgleicht, stehen sie hier." }
            else table("t") {
                thead { tr { th(classes = "hide-sm") { +"Zeit" }; th(classes = "hide-sm") { +"Gerät" }; th { +"Wer und was" }; th { +"Zahlart" }; th(classes = "num") { +"Betrag" } } }
                tbody {
                    for (row in checkouts) tr {
                        val time = ctx.friendly(row.at).removePrefix("heute ")
                        td("c-muted tnum nowrap hide-sm") { +time }
                        td("c-muted nowrap hide-sm") { +(row.device ?: "Verwaltung") }
                        td("fill") {
                            span("two") {
                                span("title-s") { +(row.who ?: "Gast") }
                                span("cap") { +row.what; span("only-sm") { +" · $time" } }
                            }
                        }
                        td { paymentChip(row.paymentType, row.topUp, row.refund).let { (text, kind) -> chip(text, kind) } }
                        td("num") { span("money-s${if (row.topUp) " c-secondary" else ""}") { +(if (row.topUp) euroSigned(row.amount) else euro(row.amount)) } }
                    }
                }
            }
        }
        div("stack") {
            panel {
                panelHead("Geräte") { if (ctx.user.role.may(Area.DEVICES)) more("Verwalten", "$BASE/geraete") }
                val active = devices.filter { !it.revoked }
                if (active.isEmpty()) p("empty") { +"Noch kein Gerät gekoppelt." }
                else table("t") {
                    tbody {
                        for (device in active) tr {
                            td { div("row") { span("c-muted") { icon("tablet") }; twoLine(device.label, "${platformName(device.platform)} · ${ctx.ago(device.lastSeenAt?.toInstant())}") } }
                            td("num") { deviceChip(ctx, device.lastSeenAt?.toInstant()) }
                        }
                    }
                }
            }
        }
    }

    div("cols cols-2") {
        if (ctx.user.role.may(Area.STOCK)) panel {
            panelHead("Lagerwarnungen") { more("Lager", "$BASE/lager") }
            val low = stock.filter { it.low }
            if (low.isEmpty()) p("empty") { +"Alles über dem Mindestbestand." }
            else table("t") {
                tbody {
                    for (line in low.take(5)) tr {
                        val unit = line.state.item.unit
                        td { twoLine(line.state.item.name, "${Quantity.format(line.available)} $unit · Mindestbestand ${Quantity.format(line.state.item.minLevel)} $unit") }
                        td("num") { chip("fehlen ${Quantity.format(line.state.item.minLevel - line.available)} $unit", "warn") }
                    }
                }
            }
        }
        if (ctx.user.role.may(Area.PURCHASES)) panel {
            panelHead("Offene Belege") { more("Einkauf", "$BASE/einkauf") }
            if (open.isEmpty()) p("empty") { +"Nichts offen." }
            else table("t") {
                tbody {
                    for (d in open.take(4)) tr {
                        td("fill") { twoLine(d.supplier.ifBlank { "Ohne Lieferant" }, listOfNotNull(d.number.takeIf { it.isNotBlank() }, d.dueDate?.let { "fällig ${ctx.dayShort(it)}" } ?: "ohne Fälligkeit").joinToString(" · ")) }
                        td("num") { span("money-s") { +(d.gross?.let(::euro) ?: "—") } }
                    }
                    tr("sum") { td { +"Offen gesamt" }; td("num") { span("money-s") { +euro(open.sumOf { it.gross ?: 0.0 }) } } }
                }
            }
        }
    }
}

internal fun platformName(platform: String) = if (platform == "ios") "iPad" else "Android"

internal fun FlowContent.deviceChip(ctx: PageContext, lastSeen: java.time.Instant?) {
    val minutes = lastSeen?.let { java.time.Duration.between(it, ctx.now).toMinutes() }
    when {
        minutes == null -> chip("noch nie", "neutral")
        minutes <= 5 -> chip("Abgeglichen", "ok", "check")
        minutes < 24 * 60 -> chip("still seit ${ctx.time(lastSeen)}", "neutral")
        else -> chip("${minutes / (24 * 60)} Tage still", "warn", "cloudoff")
    }
}

// -------------------------------------------------------------- Mitglieder

private val MEMBER_FILTERS: List<Triple<String, String, (MemberLine) -> Boolean>> = listOf(
    Triple("alle", "Alle") { true },
    Triple("minus", "Im Minus") { it.owes },
    Triple("limit", "Über dem Limit") { it.overLimit },
    Triple("guthaben", "Guthaben") { it.balance > 0 },
    Triple("gesperrt", "Gesperrt") { it.blocked },
)

private fun HTML.membersPage(
    ctx: PageContext, all: List<MemberLine>, query: String, filter: String,
    shown: MemberLine?, chosen: Boolean, statement: List<StatementLine>, categories: List<MemberCategoryOption>,
    profile: Profile?, history: List<Statement>, notice: String?, problem: String?, importPlan: MemberCsv.Plan? = null,
) {
    val tabs = all.count { it.owes }
    val test = MEMBER_FILTERS.firstOrNull { it.first == filter }?.third ?: { true }
    val q = query.trim().lowercase()
    val visible = all.filter { test(it) && (q.isEmpty() || it.name.lowercase().contains(q) || it.nickname.lowercase().contains(q)) }
    fun url(f: String = filter, m: MemberLine? = null) = buildString {
        append("$BASE/mitglieder?f=$f")
        if (query.isNotBlank()) append("&q=${query.encodeURLParameter()}")
        m?.let { append("&m=${it.id}") }
    }

    shell(ctx, Area.MEMBERS, "Mitglieder", "${count(all.size, "Mitglied", "Mitglieder")} · $tabs im Minus · ${all.count { it.overLimit }} über dem Limit", actions = {
        a(href = "$BASE/mitglieder/kategorien", classes = "btn btn-quiet") { +"Kategorien und Limits" }
        if (ctx.user.role.writesMembers) {
            a(href = "$BASE/mitglieder.csv", classes = "btn btn-quiet") { icon("download", "m"); +"CSV" }
            dialog("$BASE/mitglieder/import", "btn btn-quiet", "Importieren", "Mitglieder aus einer CSV-Datei", triggerIcon = "upload") {
                form(action = "$BASE/mitglieder/import", encType = FormEncType.multipartFormData, method = FormMethod.post, classes = "stack-tight") {
                    csrf(ctx)
                    p("muted") { +"Die Datei des Tablets („Name;Mitgliedergruppe“) oder eine aus Excel mit Kopfzeile — erkannt werden Name, Couleurname, Kategorie, Mitgliedsnummer, E-Mail, Anschrift, Einwilligung. Wen es schon gibt, legt der Import nicht noch einmal an; am Profil ergänzt er nur, was leer ist. Erst kommt eine Vorschau." }
                    label("field") { span { +"CSV-Datei" }; input(InputType.file, name = "datei") { accept = ".csv,text/csv,text/plain"; required = true } }
                    button(type = ButtonType.submit, classes = "btn btn-primary") { +"Prüfen" }
                }
            }
        }
        if (ctx.user.role.writesMembers) dialog("$BASE/mitglieder/neu", "btn btn-primary", "Mitglied anlegen", triggerIcon = "plus") {
            postForm(ctx, "$BASE/mitglieder", "stack-tight") {
                label("field") { span { +"Name" }; input(InputType.text, name = "name") { required = true; maxLength = "80" } }
                label("field") { span { +"Couleurname (Vulgo)" }; input(InputType.text, name = "vulgo") { placeholder = "Sokrates"; maxLength = "60" } }
                categorySelect(categories, null)
                button(type = ButtonType.submit, classes = "btn btn-primary") { +"Anlegen" }
            }
        }
    }) {
        flash(notice, problem)
        importPlan?.let { plan -> importPreview(ctx, plan) }
        div("cols cols-side split${if (chosen) " has-sel" else ""}") {
            panel("split-list") {
                form(action = "$BASE/mitglieder", method = FormMethod.get, classes = "toolbar") {
                    hiddenInput(name = "f") { value = filter }
                    label("search") {
                        icon("search", "m")
                        span("sr") { +"Mitglied suchen" }
                        input(InputType.search, name = "q") { value = query; placeholder = "Name oder Couleurname" }
                    }
                    div("pills") {
                        for ((key, text, rule) in MEMBER_FILTERS) a(href = url(f = key), classes = "pill") {
                            if (key == filter) attributes["aria-current"] = "true"
                            span { +text }
                            span("tnum") { +all.count(rule).toString() }
                        }
                    }
                }
                if (visible.isEmpty()) p("empty") { +(if (all.isEmpty()) "Noch keine Mitglieder. Sie entstehen am Tablet und kommen mit dem Abgleich." else "Niemand passt auf diese Suche.") }
                else table("t") {
                    thead { tr { th { +"Name" }; th(classes = "hide-sm") { +"Kategorie" }; th(classes = "num") { +"Deckel" }; th(classes = "hide-sm") { +"Letzte Buchung" } } }
                    tbody {
                        for (m in visible) tr("pick") {
                            if (chosen && m.id == shown?.id) attributes["aria-selected"] = "true"
                            td("fill") {
                                div("row") {
                                    span("avatar avatar-s") { +initialsOf(m.name) }
                                    a(href = url(m = m), classes = "cover two") {
                                        span("title-s") { +m.displayName }
                                        span("cap") { +listOfNotNull(m.category ?: "Ohne Kategorie", "Deckel gesperrt".takeIf { m.blocked }).joinToString(" · ") }
                                    }
                                }
                            }
                            td("c-muted hide-sm") { +(m.category ?: "—") }
                            td("num") { span("money-s ${m.tone}") { +euro(m.balance) } }
                            td("c-muted nowrap hide-sm") { +ctx.friendly(m.lastAt) }
                        }
                    }
                }
                div("panel-foot cap") { +"${visible.size} von ${all.size} · Salden sind die Summe der Buchungen aller Geräte" }
            }
            div("split-detail stack") {
                a(href = url(), classes = "back") { icon("back", "m"); +"Alle Mitglieder" }
                if (shown == null) panel { p("empty") { +"Noch kein Mitglied." } }
                else memberDetail(ctx, shown, statement, categories, profile ?: Profile.empty(shown.id), history)
            }
        }
    }
}

private fun FlowContent.categorySelect(categories: List<MemberCategoryOption>, selected: String?) = label("field") {
    span { +"Kategorie" }
    select {
        name = "kategorie"
        option { value = ""; +"Ohne Kategorie" }
        for (c in categories) option {
            value = c.id.toString()
            if (c.name == selected) this.selected = true
            +(if (c.limit < 0) "${c.name} · Limit ${euro(c.limit)}" else c.name)
        }
    }
}

/** Aufladen, Korrektur, Ändern — aufklappbar, ohne Skript. Jede Buchung trägt ihren eigenen Schlüssel gegen den Doppelklick. */
private fun FlowContent.memberActions(ctx: PageContext, m: MemberLine, categories: List<MemberCategoryOption>) = div("row wrap") {
    dialog("$BASE/mitglieder/${m.id}/aufladen", "btn btn-brass", "Aufladen", "Deckel aufladen", triggerIcon = "plus") {
        postForm(ctx, "$BASE/mitglieder/${m.id}/buchung", "stack-tight") {
            hiddenInput(name = "buchung") { value = Ids.new() }
            hiddenInput(name = "art") { value = "aufladung" }
            label("field") { span { +"Betrag in Euro" }; input(InputType.text, name = "betrag") { required = true; placeholder = "20,00"; attributes["inputmode"] = "decimal" } }
            label("field") {
                span { +"Bezahlt per" }
                select { name = "zahlart"; for (kind in TopUpKind.entries) option { value = kind.name; +kind.label } }
            }
            label("field") { span { +"Notiz (etwa: Abrechnung August)" }; input(InputType.text, name = "notiz") { maxLength = "200" } }
            button(type = ButtonType.submit, classes = "btn btn-brass") { +"Aufladung buchen" }
        }
    }
    dialog("$BASE/mitglieder/${m.id}/korrektur", "btn", "Korrektur", "Deckel korrigieren") {
        postForm(ctx, "$BASE/mitglieder/${m.id}/buchung", "stack-tight") {
            hiddenInput(name = "buchung") { value = Ids.new() }
            hiddenInput(name = "art") { value = "korrektur" }
            label("field") { span { +"Betrag mit Vorzeichen: −4,20 zieht ab, 4,20 schreibt gut" }; input(InputType.text, name = "betrag") { required = true; placeholder = "−4,20"; attributes["inputmode"] = "text" } }
            label("field") { span { +"Grund — steht im Kontoauszug und im Protokoll" }; input(InputType.text, name = "notiz") { required = true; maxLength = "200" } }
            button(type = ButtonType.submit, classes = "btn btn-primary") { +"Korrektur buchen" }
        }
    }
    dialog("$BASE/mitglieder/${m.id}/sperre", "btn", if (m.blocked) "Sperre aufheben" else "Sperren", if (m.blocked) "Sperre aufheben" else "Deckel sperren") {
        postForm(ctx, "$BASE/mitglieder/${m.id}/sperre", "stack-tight") {
            if (m.blocked) {
                p("cap") { +"Gesperrt: ${m.blockedReason}" }
                hiddenInput(name = "grund") { value = "" }
                button(type = ButtonType.submit, classes = "btn btn-primary") { +"Sperre aufheben" }
            } else {
                label("field") { span { +"Grund — steht an der Theke, bevor angeschrieben wird" }; input(InputType.text, name = "grund") { required = true; maxLength = "120"; placeholder = "Abrechnung offen seit August" } }
                button(type = ButtonType.submit, classes = "btn btn-primary") { +"Deckel sperren" }
            }
        }
    }
    dialog("$BASE/mitglieder/${m.id}/aendern", "btn", "Ändern", "Mitglied ändern") {
        postForm(ctx, "$BASE/mitglieder/${m.id}", "stack-tight") {
            label("field") { span { +"Name" }; input(InputType.text, name = "name") { value = m.name; required = true; maxLength = "80" } }
            label("field") { span { +"Couleurname (Vulgo)" }; input(InputType.text, name = "vulgo") { value = m.nickname; maxLength = "60" } }
            categorySelect(categories, m.category)
            button(type = ButtonType.submit, classes = "btn btn-primary") { +"Speichern" }
        }
    }
    dialog("$BASE/mitglieder/${m.id}/loeschen", "btn btn-quiet", "Löschen", "Mitglied löschen") {
        postForm(ctx, "$BASE/mitglieder/${m.id}/loeschen", "stack-tight") {
            p { +"${m.displayName} verschwindet von den Tablets und aus dieser Liste; das Profil mit Adresse und E-Mail wird gelöscht. Die Buchungen bleiben mit dem Namen, weil sie aufbewahrt werden müssen." }
            if (kotlin.math.abs(m.balance) >= 0.005) div("note note-warn") { icon("alert", "m"); span { +"Der Deckel steht auf ${euro(m.balance)}. Erst ausgleichen, dann löschen." } }
            button(type = ButtonType.submit, classes = "btn btn-danger") { +"Ja, löschen" }
        }
    }
}

/** Die Vorschau des Imports: was angelegt, was ergänzt, was übersprungen würde — und der Knopf, der es tut. */
private fun FlowContent.importPreview(ctx: PageContext, plan: MemberCsv.Plan) = panel {
    panelHead("Import prüfen") { span("cap") { +"${count(plan.table.rows.size, "Zeile", "Zeilen")} · Trennzeichen ${if (plan.table.delimiter == '\t') "Tabulator" else "„${plan.table.delimiter}“"} · ${plan.table.charset}" } }
    div("panel-note") {
        +"${count(plan.created, "Mitglied", "Mitglieder")} neu"
        if (plan.newCategories.isNotEmpty()) +", ${count(plan.newCategories.size, "Kategorie", "Kategorien")} neu (${plan.newCategories.joinToString(", ")}, mit Limit 0 — unter „Kategorien und Limits“ nachziehen)"
        if (plan.existing > 0) +", ${plan.existing} gibt es schon"
        if (plan.skipped > 0) +", ${plan.skipped} übersprungen"
        +"."
    }
    table("t t-tight") {
        thead { tr { th { +"Name" }; th(classes = "hide-sm") { +"Kategorie" }; th(classes = "hide-sm") { +"Profil" }; th { +"Ergebnis" } } }
        tbody {
            for (l in plan.lines) tr {
                td("fill") { twoLine(l.row.name.ifBlank { "(ohne Namen)" }, listOfNotNull("Zeile ${l.row.line}", l.row.nickname.takeIf { it.isNotBlank() }?.let { "v. $it" }).joinToString(" · ")) }
                td("hide-sm") { +l.row.category; if (l.newCategory) { +" "; chip("neu", "neutral") } }
                td("hide-sm cap") { +listOf(l.row.number, l.row.email, l.row.address.lineSequence().firstOrNull().orEmpty()).filter { it.isNotBlank() }.joinToString(" · ") }
                td {
                    when (l.fate) {
                        MemberCsv.Fate.NEW -> chip("wird angelegt", "ok", "check")
                        MemberCsv.Fate.EXISTS -> chip(if (l.fills.isEmpty()) "gibt es schon" else "ergänzt: ${l.fills.joinToString(", ")}", "neutral")
                        MemberCsv.Fate.SKIPPED -> chip("übersprungen: ${l.reason}", "warn", "alert")
                    }
                }
            }
        }
    }
    div("panel-foot") {
        if (plan.hasWork) postForm(ctx, "$BASE/mitglieder/import/anlegen", "row wrap") {
            // Die Datei fährt als Text mit; beim Anlegen wird sie noch einmal gegen den Bestand gerechnet.
            textArea { attributes["hidden"] = ""; name = "daten"; +plan.table.text }
            button(type = ButtonType.submit, classes = "btn btn-primary") { icon("upload", "m"); +"${count(plan.created, "Mitglied", "Mitglieder")} anlegen${if (plan.filled > 0) " und Profile ergänzen" else ""}" }
            a(href = "$BASE/mitglieder", classes = "btn") { +"Abbrechen" }
        } else p("muted") { +"Nichts zu tun — alle stehen schon in der Liste." }
    }
}

private fun FlowContent.memberDetail(ctx: PageContext, m: MemberLine, statement: List<StatementLine>, categories: List<MemberCategoryOption>, profile: Profile, history: List<Statement>) = panel {
    div("panel-body") {
        div("head-row") {
            span("avatar avatar-l") { +initialsOf(m.name) }
            div("two") {
                h2("title-m") { +m.displayName }
                span("muted") { +(m.category ?: "Ohne Kategorie") }
            }
            if (m.blocked) chip("Deckel gesperrt", "warn", "lock")
        }
        if (m.blocked) div("note note-warn") { icon("lock", "m"); span { +"Gesperrt: ${m.blockedReason}. Die Theke schreibt nicht an, Bar und Karte gehen." } }
        div("sub stack-tight") {
            div("row-between") {
                span("label-m") { +"Deckel" }
                span("cap") { +(if (m.limit < 0) "Limit ${euro(m.limit)}" else "Kein Kredit in dieser Kategorie") }
            }
            div { span("money-d ${m.tone}") { +euro(m.balance) } }
            val used = if (m.limit < 0 && m.balance < 0) minOf(100.0, m.balance / m.limit * 100) else 0.0
            bar(listOf(used to m.tone.replace("c-", "f-")), "Anteil des Limits", thin = true)
            span("cap") {
                +when {
                    m.overLimit -> "Über dem Limit um ${euro(m.limit - m.balance)}"
                    m.balance < 0 && m.limit < 0 -> "${used.toInt()} % des Limits genutzt"
                    m.balance > 0 -> "Guthaben — der Verein schuldet es dem Mitglied"
                    else -> "Ausgeglichen"
                }
            }
        }
        if (ctx.user.role.writesMembers) memberActions(ctx, m, categories)
        div {
            h3("title-s") { +"Kontoauszug" }
            if (statement.isEmpty()) p("cap") { +"Noch keine Buchung auf diesem Deckel." }
            else table("t t-tight t-flush") {
                thead { tr { th { +"Datum" }; th { +"Buchung" }; th(classes = "num") { +"Betrag" }; th(classes = "num") { +"Stand" } } }
                tbody {
                    for (line in statement) tr {
                        td("c-muted tnum nowrap cap") { +ctx.day(line.at) }
                        td { +line.text }
                        td("num") { span("money-s${if (line.effect > 0) " c-secondary" else ""}") { +euroSigned(line.effect) } }
                        td("num c-muted tnum") { +euro(line.after) }
                    }
                }
            }
            p("cap") { +"Der Stand ist die Summe der Buchungen aller Geräte — hergeleitet, nirgends gespeichert." }
        }
        profileSection(ctx, m, profile, history, ctx.user.role.writesMembers)
    }
}

// ---------------------------------------------------------------- Berichte

private fun monthName(month: YearMonth) = month.month.getDisplayName(TextStyle.FULL, AT) + if (month.monthValue == 1 || month.monthValue == 12) " ${month.year}" else ""

private fun HTML.reportsPage(ctx: PageContext, year: Int, years: List<Int>, months: List<MonthReport>, tabs: TabTotals, calendar: List<MonthReport>) {
    val start = ctx.verein.fiscalStartMonth
    val label = if (start == 1) "$year" else "$year/${(year + 1) % 100}"
    val total = months.fold(Revenue()) { a, m -> a + m.revenue }
    val topUps = months.sumOf { it.topUpCash + it.topUpCard + it.topUpOther }
    val purchases = months.sumOf { it.purchases }
    val shownMonths = months.filter { !it.month.isAfter(YearMonth.from(ctx.today)) }

    shell(ctx, Area.REPORTS, "Berichte", "Rechnungsjahr $label · aus den Buchungen aller Geräte", actions = {
        div("pills") {
            for (y in years) a(href = "$BASE/berichte?jahr=$y", classes = "pill") {
                if (y == year) attributes["aria-current"] = "true"
                +(if (start == 1) "$y" else "$y/${(y + 1) % 100}")
            }
        }
    }) {
        panel {
            div("figures") {
                figure("Umsatz der Bude") {
                    span("money-l c-primary") { +euro(total.total) }
                    bar(listOf(total.cash to "f-primary", total.card to "f-tertiary", total.tab to "f-secondary").map { (v, f) -> (if (total.total > 0) v / total.total * 100 else 0.0) to f }, "Aufteilung nach Zahlart")
                    legend(total)
                }
                figure("Aufladungen") {
                    span("money-l c-secondary") { +euro(topUps) }
                    span("cap") { +"bar ${euro(months.sumOf { it.topUpCash })} · Karte ${euro(months.sumOf { it.topUpCard })}" }
                }
                figure("Wareneingang") {
                    span("money-l") { +euro(purchases) }
                    span("cap") { +"Summe der Belege, die am Tablet gebucht wurden" }
                }
                figure("Deckel heute") {
                    span("money-l") { +euro(tabs.owedSum) }
                    span("cap") { +"Forderungen · dagegen ${euro(tabs.creditSum)} Guthaben" }
                }
            }
        }
        div("cols cols-side") {
            panel {
                panelHead("Monate") { span("cap") { +"Umsatz ist Verkauf nach Rabatt, Stornos abgezogen; Aufladungen und Trinkgeld zählen nicht dazu" } }
                if (shownMonths.all { it.revenue.total == 0.0 && it.purchases == 0.0 && it.topUpCash + it.topUpCard == 0.0 }) p("empty") { +"In diesem Rechnungsjahr ist nichts gebucht." }
                else table("t") {
                    thead { tr { th { +"Monat" }; th(classes = "num hide-sm") { +"Bar" }; th(classes = "num hide-sm") { +"Karte" }; th(classes = "num hide-sm") { +"Deckel" }; th(classes = "num") { +"Umsatz" }; th(classes = "num hide-sm") { +"Aufladungen" }; th(classes = "num") { +"Wareneingang" } } }
                    tbody {
                        for (m in shownMonths) tr {
                            td { +monthName(m.month) }
                            money(m.revenue.cash, cell = "num hide-sm"); money(m.revenue.card, cell = "num hide-sm"); money(m.revenue.tab, cell = "num hide-sm")
                            money(m.revenue.total, "money-s"); money(m.topUpCash + m.topUpCard + m.topUpOther, cell = "num hide-sm"); money(m.purchases)
                        }
                        tr("sum") {
                            td { +"Rechnungsjahr $label" }
                            money(total.cash, cell = "num hide-sm"); money(total.card, cell = "num hide-sm"); money(total.tab, cell = "num hide-sm")
                            money(total.total, "money-s"); money(topUps, cell = "num hide-sm"); money(purchases)
                        }
                    }
                }
            }
            div("stack") {
                val cal = calendar.fold(Revenue()) { a, m -> a + m.revenue }
                panel {
                    div("panel-body") {
                        h2("title-m") { +"Kalenderjahr ${ctx.today.year}, Schwellen" }
                        threshold("Alle Zahlarten", cal.total, 15_000.0, "f-muted")
                        threshold("Bar und Karte", cal.cash + cal.card, 7_500.0, "f-tertiary")
                        p("cap") { +"Die Marken sind die Schwellen des § 131b BAO; erst wenn beide überschritten sind, stellt sich die Frage der Registrierkasse. Eine Zahl zum Hinschauen, keine Steuerberatung." }
                    }
                }
                panel {
                    div("panel-body") {
                        h2("title-m") { +"Noch nicht hier" }
                        p("muted") { +"Die Einnahmen-Ausgaben-Rechnung mit Vermögensübersicht braucht Kassenbuch, Eingangsrechnungen mit Konten und das Bankbuch. Die kommen mit den nächsten Phasen; bis dahin zeigt diese Seite, was sich aus den Buchungen der Theke sicher sagen lässt." }
                        val tips = months.sumOf { it.tips }
                        val refunds = months.sumOf { it.refunds }
                        p("cap") { +"Trinkgeld im Rechnungsjahr: ${euro(tips)} · Stornos: ${euro(refunds)}" }
                    }
                }
            }
        }
    }
}

private fun FlowContent.threshold(label: String, value: Double, limit: Double, fill: String) = div("stack-tight") {
    div("row-between") {
        span { +label }
        span("cap") { span("money-s") { +euro(value) }; +" von ${euro(limit)}" }
    }
    bar(listOf(minOf(100.0, value / limit * 100) to fill), "$label: Anteil an der Schwelle")
}
