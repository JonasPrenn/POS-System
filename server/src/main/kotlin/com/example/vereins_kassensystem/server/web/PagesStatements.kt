package com.example.vereins_kassensystem.server.web

import com.example.vereins_kassensystem.server.devices.Tokens
import com.example.vereins_kassensystem.ui.format.Money
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.encodeURLParameter
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormEncType
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
import kotlinx.html.textArea
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.io.readByteArray
import java.time.LocalDate
import java.util.UUID

private fun statementText(verein: VereinSettings.Values, s: Statement, run: StatementRun, reminder: Boolean): String {
    val fmt = { d: LocalDate -> "%02d.%02d.%d".format(d.dayOfMonth, d.monthValue, d.year) }
    val club = verein.name.ifBlank { "VereinsDeckel" }
    return buildString {
        append("Lieber Bundesbruder,\n\n")
        if (reminder) append("zur Abrechnung ${s.number} (${run.label}) ist bei uns noch keine Zahlung angekommen. Der Betrag von ${Money.format(s.amount)} war bis ${fmt(s.dueDate)} fällig — bitte überweise ihn, falls es noch nicht geschehen ist; sonst betrachte diese Nachricht als gegenstandslos.\n\n")
        else append("anbei dein Kontoauszug „${run.label}“ vom ${fmt(run.from)} bis ${fmt(run.to)}.${if (s.amount > 0) " Bitte überweise ${Money.format(s.amount)} bis ${fmt(s.dueDate)} mit dem Verwendungszweck ${s.number} — oder scanne den QR-Code im Anhang mit deiner Banking-App." else " Es ist nichts zu zahlen."}\n\n")
        if (verein.bank.configured) append("${verein.bank.holder}\nIBAN ${verein.bank.iban.chunked(4).joinToString(" ")}\nVerwendungszweck ${s.number}\n\n")
        append("Mit bundesbrüderlichen Grüßen\nder Kassier\n$club")
    }
}

/** Baut den Auszug, mit allem, was er braucht — für PDF und E-Mail dieselbe Quelle. */
private fun Web.documentOf(ctx: PageContext, s: Statement): StatementDocument {
    val run = statements.run(s.runId) ?: throw AccountProblem("Der Lauf zu dieser Abrechnung fehlt.")
    return StatementDocument(s, run, statements.lines(s, run), statements.profile(s.memberId), ctx.verein, config.zone, statements.nicknameOf(s.memberId))
}

private fun Web.sendStatement(ctx: PageContext, s: Statement, reminder: Boolean): String? {
    val profile = statements.profile(s.memberId)
    if (!profile.canEmail) return "${s.memberName} hat keine E-Mail-Adresse mit Einwilligung — bitte drucken."
    val doc = documentOf(ctx, s)
    val pdf = StatementPdf.render(listOf(doc))
    val subject = (if (reminder) "Erinnerung: " else "") + "${doc.run.label} — Kontoauszug ${s.number}"
    val problem = mailer.send(ctx.verein.smtp, Mail(profile.email, subject, statementText(ctx.verein, s, doc.run, reminder), "${s.number}.pdf", pdf))
    if (problem != null) return problem
    if (reminder) statements.markReminded(ctx.user, s.id, "per E-Mail an ${profile.email}") else statements.markSent(ctx.user, s.id, "EMAIL", profile.email)
    return null
}

internal fun Route.statementPages(web: Web) {
    get("/abrechnung") {
        call.guarded(web, Area.STATEMENTS) { ctx ->
            val runs = web.statements.runs()
            val run = uuidOrNull(call.request.queryParameters["lauf"])?.let { id -> runs.firstOrNull { it.id == id } } ?: runs.firstOrNull()
            val list = run?.let { web.statements.statements(it.id) }.orEmpty()
            val profiles = web.statements.profiles(list.map { it.memberId })
            val threshold = call.request.queryParameters["schwelle"]?.let { Money.parse(it.replace('−', '-')) } ?: -5.0
            val nextFrom = web.statements.nextPeriodStart(LocalDate.of(ctx.today.year, ctx.verein.fiscalStartMonth, 1).let { if (it.isAfter(ctx.today)) it.minusYears(1) else it })
            // Vorgabe für den Stichtag: das Ende des Vormonats — oder, wenn der letzte Lauf schon weiter ist, das Ende von dessen Folgemonat, nie in der Zukunft.
            val lastMonthEnd = ctx.today.withDayOfMonth(1).minusDays(1)
            val to = call.request.queryParameters["stichtag"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: if (nextFrom.isAfter(lastMonthEnd)) minOf(nextFrom.withDayOfMonth(nextFrom.lengthOfMonth()), ctx.today) else lastMonthEnd
            val open = web.statements.openStatements()
            val covered = web.statements.topUpsSince(list + open)
            call.html {
                statementsPage(ctx, runs, run, list, profiles, open, covered, web.statements.openBankTransactions(),
                    nextFrom, to, threshold, web.statements.preview(to, threshold), call.request.queryParameters["hinweis"], call.request.queryParameters["fehler"])
            }
        }
    }
    get("/abrechnung/{id}.pdf") {
        call.guarded(web, Area.STATEMENTS) { ctx ->
            val s = uuidOrNull(call.parameters["id"])?.let(web.statements::statement) ?: return@guarded call.respondText("Keine Abrechnung unter dieser Nummer.", status = HttpStatusCode.NotFound)
            call.response.header("Content-Disposition", "inline; filename=\"${s.number}.pdf\"")
            call.respondBytes(StatementPdf.render(listOf(web.documentOf(ctx, s))), ContentType.Application.Pdf)
        }
    }
    // Die Mappe zum Drucken: alle Auszüge eines Laufs, die nicht per E-Mail gehen, in einem PDF.
    get("/abrechnung/lauf/{id}.pdf") {
        call.guarded(web, Area.STATEMENTS) { ctx ->
            val run = uuidOrNull(call.parameters["id"])?.let(web.statements::run) ?: return@guarded call.respondText("Keinen Lauf unter dieser Nummer.", status = HttpStatusCode.NotFound)
            val all = call.request.queryParameters["alle"] == "1"
            val list = web.statements.statements(run.id).filter { it.status != StatementStatus.CANCELLED }
            val profiles = web.statements.profiles(list.map { it.memberId })
            val toPrint = list.filter { all || !(profiles[it.memberId]?.canEmail ?: false) }
            if (toPrint.isEmpty()) return@guarded call.respondRedirect("$BASE/abrechnung?lauf=${run.id}&hinweis=${"Nichts zu drucken: alle bekommen den Auszug per E-Mail.".encodeURLParameter()}")
            val pdf = StatementPdf.render(toPrint.map { web.documentOf(ctx, it) })
            if (ctx.user.role.writesMembers) toPrint.filter { it.sentAt == null }.forEach { web.statements.markSent(ctx.user, it.id, "PRINT", "") }
            call.response.header("Content-Disposition", "inline; filename=\"${run.label.replace(Regex("[^A-Za-z0-9äöüÄÖÜß ]"), "").trim()}.pdf\"")
            call.respondBytes(pdf, ContentType.Application.Pdf)
        }
    }
    post("/abrechnung/lauf") {
        call.guardedPost(web, Area.STATEMENTS) { ctx, form ->
            if (!ctx.user.role.writesMembers) return@guardedPost call.forbidden(ctx, "Abrechnungen erstellt der Kassier.")
            val outcome = try {
                val to = LocalDate.parse(form["stichtag"].orEmpty())
                val run = web.statements.createRun(ctx.user, Statements.RunRequest(
                    label = form["name"].orEmpty(), from = LocalDate.parse(form["von"].orEmpty()), to = to,
                    dueDate = form["ziel"]?.takeIf { it.isNotBlank() }?.let(LocalDate::parse) ?: to.plusDays(14),
                    threshold = Money.parse(form["schwelle"].orEmpty().replace('−', '-')) ?: -5.0,
                    extraLabel = form["zusatz"].orEmpty(), extraAmount = form["zusatzbetrag"]?.takeIf { it.isNotBlank() }?.let { Money.parse(it) ?: throw AccountProblem("Den Zusatzbetrag bitte als Zahl.") } ?: 0.0,
                    includeAll = form["alle"] == "1",
                ))
                "lauf=${run.id}&hinweis=" + "${count(run.count, "Abrechnung", "Abrechnungen")} erstellt. Jetzt versenden oder drucken.".encodeURLParameter()
            } catch (e: AccountProblem) { "fehler=" + e.message.orEmpty().encodeURLParameter() }
            catch (e: java.time.format.DateTimeParseException) { "fehler=" + "Ein Datum fehlt oder ist nicht lesbar.".encodeURLParameter() }
            call.respondRedirect("$BASE/abrechnung?$outcome")
        }
    }
    post("/abrechnung/lauf/{id}/senden") {
        call.guardedPost(web, Area.STATEMENTS) { ctx, _ ->
            if (!ctx.user.role.writesMembers) return@guardedPost call.forbidden(ctx, "Abrechnungen versendet der Kassier.")
            val run = uuidOrNull(call.parameters["id"])?.let(web.statements::run) ?: return@guardedPost call.respondRedirect("$BASE/abrechnung")
            var sent = 0; var skipped = 0; var failed: String? = null
            for (s in web.statements.statements(run.id).filter { it.status == StatementStatus.OPEN && it.sentAt == null }) {
                val problem = web.sendStatement(ctx, s, reminder = false)
                when {
                    problem == null -> sent++
                    problem.startsWith("Kein SMTP") || problem.startsWith("Versand fehlgeschlagen") -> { failed = problem; break }
                    else -> skipped++
                }
            }
            val message = failed ?: "$sent per E-Mail verschickt${if (skipped > 0) ", $skipped ohne Adresse oder Einwilligung — die kommen in die Druckmappe" else ""}."
            call.respondRedirect("$BASE/abrechnung?lauf=${run.id}&${if (failed != null) "fehler" else "hinweis"}=${message.encodeURLParameter()}")
        }
    }
    post("/abrechnung/{id}/senden") {
        call.guardedPost(web, Area.STATEMENTS) { ctx, form ->
            if (!ctx.user.role.writesMembers) return@guardedPost call.forbidden(ctx, "Abrechnungen versendet der Kassier.")
            val s = uuidOrNull(call.parameters["id"])?.let(web.statements::statement) ?: return@guardedPost call.respondRedirect("$BASE/abrechnung")
            val problem = web.sendStatement(ctx, s, reminder = form["erinnerung"] == "1")
            call.respondRedirect("$BASE/abrechnung?lauf=${s.runId}&${if (problem == null) "hinweis=${"An ${s.memberName} verschickt.".encodeURLParameter()}" else "fehler=${problem.encodeURLParameter()}"}")
        }
    }
    post("/abrechnung/{id}/erinnert") {
        call.guardedPost(web, Area.STATEMENTS) { ctx, form ->
            if (!ctx.user.role.writesMembers) return@guardedPost call.forbidden(ctx, "Erinnerungen schickt der Kassier.")
            val s = uuidOrNull(call.parameters["id"])?.let(web.statements::statement) ?: return@guardedPost call.respondRedirect("$BASE/abrechnung")
            web.statements.markReminded(ctx.user, s.id, form["wie"].orEmpty().trim().take(120).ifEmpty { "persönlich" })
            call.respondRedirect("$BASE/abrechnung?lauf=${s.runId}&hinweis=${"Erinnerung vermerkt.".encodeURLParameter()}")
        }
    }
    post("/abrechnung/{id}/bezahlt") {
        call.guardedPost(web, Area.STATEMENTS) { ctx, form ->
            if (!ctx.user.role.writesMembers) return@guardedPost call.forbidden(ctx, "Zahlungen bucht der Kassier.")
            val s = uuidOrNull(call.parameters["id"])?.let(web.statements::statement) ?: return@guardedPost call.respondRedirect("$BASE/abrechnung")
            val outcome = try {
                val topUp = uuidOrNull(form["aufladung"])
                if (topUp != null) {
                    web.statements.settleWithTopUp(ctx.user, s.id, topUp)
                    "hinweis=" + "Abrechnung ${s.number} bezahlt — mit der Aufladung, die schon am Deckel steht, keine neue Buchung.".encodeURLParameter()
                } else {
                    val amount = form["betrag"]?.takeIf { it.isNotBlank() }?.let { Money.parse(it) ?: throw AccountProblem("Den Betrag bitte als Zahl.") } ?: s.amount
                    web.statements.recordPayment(ctx.user, s.id, amount, TopUpKind.entries.firstOrNull { it.name == form["zahlart"] } ?: TopUpKind.BANK, form["am"]?.takeIf { it.isNotBlank() }?.let(LocalDate::parse) ?: ctx.today)
                    "hinweis=" + "Zahlung von ${s.memberName} gebucht — als Aufladung, die Tablets sehen sie beim nächsten Abgleich.".encodeURLParameter()
                }
            } catch (e: AccountProblem) { "fehler=" + e.message.orEmpty().encodeURLParameter() }
            call.respondRedirect("$BASE/abrechnung?lauf=${s.runId}&$outcome")
        }
    }
    post("/abrechnung/{id}/storno") {
        call.guardedPost(web, Area.STATEMENTS) { ctx, form ->
            if (!ctx.user.role.writesMembers) return@guardedPost call.forbidden(ctx, "Storniert der Kassier.")
            val s = uuidOrNull(call.parameters["id"])?.let(web.statements::statement) ?: return@guardedPost call.respondRedirect("$BASE/abrechnung")
            val outcome = try { web.statements.cancel(ctx.user, s.id, form["grund"].orEmpty()); "hinweis=" + "${s.number} storniert.".encodeURLParameter() } catch (e: AccountProblem) { "fehler=" + e.message.orEmpty().encodeURLParameter() }
            call.respondRedirect("$BASE/abrechnung?lauf=${s.runId}&$outcome")
        }
    }
    post("/abrechnung/bank") {
        call.guarded(web, Area.STATEMENTS) { ctx ->
            if (!ctx.user.role.writesMembers) return@guarded call.forbidden(ctx, "Kontoauszüge liest der Kassier ein.")
            var csrf = ""; var bytes: ByteArray? = null; var name = ""
            call.receiveMultipart(formFieldLimit = 64 * 1024).forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> if (part.name == "_csrf") csrf = part.value
                    is PartData.FileItem -> { name = part.originalFileName.orEmpty(); bytes = part.provider().readRemaining(8L * 1024 * 1024).readByteArray() }
                    else -> Unit
                }
                part.dispose()
            }
            if (!Tokens.constantTimeEquals(csrf, ctx.session.csrf)) return@guarded call.forbidden(ctx, "Das Formular ist abgelaufen. Bitte die Seite neu laden und noch einmal versuchen.")
            val outcome = try {
                val rows = BankImport.parse(bytes ?: throw AccountProblem("Keine Datei ausgewählt."), name)
                val r = web.statements.importBank(ctx.user, rows)
                "hinweis=" + "${r.imported} Umsätze neu (${r.duplicates} schon bekannt), ${r.matched} Abrechnungen damit bezahlt, ${r.ignored} Belastungen abgelegt.".encodeURLParameter()
            } catch (e: AccountProblem) { "fehler=" + e.message.orEmpty().encodeURLParameter() }
            call.respondRedirect("$BASE/abrechnung?$outcome")
        }
    }
    post("/abrechnung/bank/{id}") {
        call.guardedPost(web, Area.STATEMENTS) { ctx, form ->
            if (!ctx.user.role.writesMembers) return@guardedPost call.forbidden(ctx, "Zahlungen ordnet der Kassier zu.")
            val bank = uuidOrNull(call.parameters["id"]) ?: return@guardedPost call.respondRedirect("$BASE/abrechnung")
            val outcome = try {
                if (form["ignorieren"] == "1") { web.statements.ignoreBank(ctx.user, bank); "hinweis=" + "Umsatz abgelegt.".encodeURLParameter() }
                else { web.statements.assignBank(ctx.user, bank, uuidOrNull(form["abrechnung"]) ?: throw AccountProblem("Bitte eine Abrechnung wählen.")); "hinweis=" + "Zugeordnet und gebucht.".encodeURLParameter() }
            } catch (e: AccountProblem) { "fehler=" + e.message.orEmpty().encodeURLParameter() }
            call.respondRedirect("$BASE/abrechnung?$outcome")
        }
    }
    post("/mitglieder/{id}/profil") {
        call.guardedPost(web, Area.MEMBERS) { ctx, form ->
            if (!ctx.user.role.writesMembers) return@guardedPost call.forbidden(ctx, "Profile pflegt der Kassier.")
            val id = uuidOrNull(call.parameters["id"]) ?: return@guardedPost call.respondRedirect("$BASE/mitglieder")
            val outcome = try {
                web.statements.saveProfile(ctx.user, id, form["nummer"].orEmpty(), form["email"].orEmpty(), form["anschrift"].orEmpty(), form["einwilligung"] == "1", form["notizen"].orEmpty())
                "hinweis=" + "Profil gespeichert — bleibt am Server.".encodeURLParameter()
            } catch (e: AccountProblem) { "fehler=" + e.message.orEmpty().encodeURLParameter() }
            call.respondRedirect("$BASE/mitglieder?m=$id&$outcome")
        }
    }
}

// ------------------------------------------------------------------- Seite

private fun HTML.statementsPage(
    ctx: PageContext, runs: List<StatementRun>, run: StatementRun?, list: List<Statement>, profiles: Map<UUID, Profile>,
    open: List<Statement>, covered: Map<UUID, Statements.TopUpsSince>, bank: List<BankTransaction>, nextFrom: LocalDate, previewTo: LocalDate, threshold: Double,
    preview: List<Pair<MemberLine, Double>>, notice: String?, problem: String?,
) {
    val writes = ctx.user.role.writesMembers
    val overdue = open.count { it.overdue(ctx.today) }
    shell(ctx, Area.STATEMENTS, "Abrechnung", "Aus einem Deckel im Minus wird ein Vorgang mit Nummer, Zahlungsziel und Zahlungseingang", actions = {
        if (runs.size > 1) div("pills") {
            for (r in runs.take(6)) a(href = "$BASE/abrechnung?lauf=${r.id}", classes = "pill") { if (r.id == run?.id) attributes["aria-current"] = "true"; +r.label }
        }
    }) {
        flash(notice, problem)
        val coveredOpen = open.filter { it.id in covered }
        if (coveredOpen.isNotEmpty()) div("note note-warn") {
            icon("wallet", "m")
            span {
                +"${count(coveredOpen.size, "offene Abrechnung", "offene Abrechnungen")} mit Aufladung seit dem Stichtag — vermutlich an der Theke bezahlt: "
                +coveredOpen.take(6).joinToString(", ") { "${it.memberName} (${euroSigned(covered.getValue(it.id).amount)})" }
                +(if (coveredOpen.size > 6) " und ${coveredOpen.size - 6} weitere. " else ". ")
                +(if (writes) "„Bezahlt mit dieser Aufladung“ unter „Mehr“ schließt die Abrechnung ohne zweite Buchung." else "Der Kassier kann sie damit schließen.")
            }
        }
        if (!ctx.verein.bank.configured) div("note note-warn") { icon("alert", "m"); span { +"Ohne Bankverbindung unter Einstellungen bekommt der Auszug keinen QR-Code und keine IBAN — nur den Verwendungszweck." } }
        panel {
            div("figures") {
                figure("Offen") { span("money-m${if (overdue > 0) " c-warning" else ""}") { +euro(open.sumOf { it.amount }) }; span("cap") { +"${count(open.size, "Abrechnung", "Abrechnungen")}${if (overdue > 0) " · $overdue überfällig" else ""}" } }
                if (run != null) {
                    figure(run.label) { span("money-m") { +euro(run.amount) }; span("cap") { +"${run.count} Auszüge · ${run.sent} versandt · Stichtag ${ctx.dayShort(run.to)}" } }
                    figure("Davon bezahlt") { span("money-m c-primary") { +euro(run.paidAmount) }; span("cap") { +"${run.paid} von ${run.count} · Zahlungsziel ${ctx.dayShort(run.dueDate)}" } }
                } else figure("Noch kein Lauf") { span("title-s") { +"Rechts den ersten anlegen" }; span("cap") { +"Vorschau zeigt, wer abgerechnet würde" } }
                figure("Bankumsätze ohne Zuordnung") { span("money-m${if (bank.isNotEmpty()) " c-warning" else ""}") { +"${bank.size}" }; span("cap") { +"aus eingelesenen Kontoauszügen" } }
            }
        }
        div("cols cols-side") {
            div("stack") {
                if (run != null) panel {
                    panelHead(run.label) {
                        div("row wrap") {
                            a(href = "$BASE/abrechnung/lauf/${run.id}.pdf", classes = "btn") { icon("printer", "m"); +"Druckmappe" }
                            if (writes && list.any { it.status == StatementStatus.OPEN && it.sentAt == null }) postForm(ctx, "$BASE/abrechnung/lauf/${run.id}/senden") { button(type = ButtonType.submit, classes = "btn btn-primary") { icon("mail", "m"); +"Alle per E-Mail senden" } }
                        }
                    }
                    div("panel-note cap") { +"Zeitraum ${ctx.dayShort(run.from)} bis ${ctx.dayShort(run.to)} · Zahlungsziel ${ctx.dayShort(run.dueDate)} · abgerechnet, wer unter ${euro(run.threshold)} lag${if (run.extraAmount > 0) " · dazu ${run.extraLabel} ${euro(run.extraAmount)}" else ""} · angelegt von ${run.createdBy}" }
                    table("t") {
                        thead { tr { th { +"Mitglied" }; th(classes = "num") { +"Betrag" }; th(classes = "hide-sm") { +"Versand" }; th(classes = "hide-sm") { +"Status" }; th { span("sr") { +"Aktionen" } } } }
                        tbody {
                            for (s in list) tr {
                                val profile = profiles[s.memberId]
                                val status: FlowContent.() -> Unit = {
                                    when {
                                        s.status == StatementStatus.CANCELLED -> chip("storniert", "neutral")
                                        s.status == StatementStatus.PAID -> chip("bezahlt ${s.paidAt?.let { ctx.dayShort(it) }.orEmpty()}", "ok", "check")
                                        s.amount <= 0 -> chip("nichts zu zahlen", "neutral")
                                        covered[s.id] != null -> chip("aufgeladen ${euroSigned(covered.getValue(s.id).amount)}", "deckel", "wallet")
                                        s.reminderLevel > 0 -> chip("erinnert ${s.remindedAt?.let { ctx.day(it) }.orEmpty()}", "warn")
                                        s.overdue(ctx.today) -> chip("überfällig", "error", "alert")
                                        else -> chip("offen", "neutral")
                                    }
                                }
                                td("fill") { twoLine(s.memberName, s.number, status) }
                                td("num") { span("money-s") { +euro(s.amount) } }
                                td("c-muted cap hide-sm") { +(when { s.sentAt == null -> if (profile?.canEmail == true) "E-Mail möglich" else "Druck"; s.sentVia == "EMAIL" -> "E-Mail ${ctx.day(s.sentAt)}"; else -> "gedruckt ${ctx.day(s.sentAt)}" }) }
                                td("hide-sm") { status() }
                                td("num nowrap") {
                                    div("row") {
                                        val more = writes && s.status == StatementStatus.OPEN
                                        // Am Telefon trägt der Dialog den PDF-Knopf, damit der Name in der Zeile Platz behält.
                                        a(href = "$BASE/abrechnung/${s.id}.pdf", classes = if (more) "btn btn-quiet hide-sm" else "btn btn-quiet") { +"PDF" }
                                        if (more) dialog("$BASE/abrechnung/${s.id}/mehr", "btn", "Mehr", "Abrechnung ${s.number}") {
                                            div("stack-tight") {
a(href = "$BASE/abrechnung/${s.id}.pdf", classes = "btn btn-wide only-sm") { icon("printer", "m"); +"PDF öffnen" }
val topUps = covered[s.id]
val reminder = s.sentAt != null && s.overdue(ctx.today)
// Was seit dem Stichtag an der Theke auf den Deckel kam, ist vermutlich die Zahlung: ein Klick, keine zweite Buchung.
if (topUps != null) postForm(ctx, "$BASE/abrechnung/${s.id}/bezahlt", "stack-tight") {
    hiddenInput(name = "aufladung") { value = topUps.lastId.toString() }
    p("muted") { +"Seit dem Stichtag aufgeladen: ${euroSigned(topUps.amount)}${if (topUps.count > 1) " in ${topUps.count} Aufladungen" else ""}, zuletzt ${ctx.friendly(topUps.lastAt)} ${topUps.lastKindLabel}." }
    if (topUps.amount + 0.005 >= s.amount) button(type = ButtonType.submit, classes = "btn btn-primary btn-wide") { icon("check", "m"); +"Bezahlt mit dieser Aufladung" }
    else p("cap") { +"Weniger als die geforderten ${euro(s.amount)} — unten als Zahlung buchen, wenn der Rest kommt." }
}
// Wer an der Theke aufgeladen hat, bekommt keine Erinnerung — nur der erste Versand bleibt möglich.
if (profile?.canEmail == true && !(reminder && topUps != null)) postForm(ctx, "$BASE/abrechnung/${s.id}/senden") { hiddenInput(name = "erinnerung") { value = if (reminder) "1" else "0" }; button(type = ButtonType.submit, classes = "btn btn-wide") { icon("mail", "m"); +(if (reminder) "Erinnerung mailen" else "Per E-Mail senden") } }
                                                if (s.amount > 0) postForm(ctx, "$BASE/abrechnung/${s.id}/bezahlt", "stack-tight") {
                                                    label("field") { span { +"Zahlung eingegangen, Betrag" }; input(InputType.text, name = "betrag") { value = Money.formatPlain(s.amount); attributes["inputmode"] = "decimal" } }
                                                    label("field") { span { +"Wie" }; select { name = "zahlart"; for (k in TopUpKind.entries) option { value = k.name; +k.label } } }
                                                    label("field") { span { +"Am" }; input(InputType.date, name = "am") { value = ctx.today.toString() } }
                                                    button(type = ButtonType.submit, classes = "btn btn-brass btn-wide") { +"Zahlung buchen" }
                                                }
                                                if (s.overdue(ctx.today) && topUps == null) postForm(ctx, "$BASE/abrechnung/${s.id}/erinnert", "stack-tight") {
                                                    label("field") { span { +"Erinnert — wie?" }; input(InputType.text, name = "wie") { placeholder = "auf der Bude angesprochen" } }
                                                    button(type = ButtonType.submit, classes = "btn btn-wide") { +"Erinnerung vermerken" }
                                                }
                                                postForm(ctx, "$BASE/abrechnung/${s.id}/storno", "stack-tight") {
                                                    label("field") { span { +"Stornieren — Grund" }; input(InputType.text, name = "grund") { placeholder = "doppelt, falscher Zeitraum …" } }
                                                    button(type = ButtonType.submit, classes = "btn btn-danger btn-wide") { +"Stornieren" }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else panel { p("empty") { +"Noch kein Abrechnungslauf. Der erste entsteht rechts — die Vorschau zeigt vorher, wen er trifft." } }

                if (bank.isNotEmpty()) panel {
                    panelHead("Bankumsätze ohne Zuordnung") { span("cap") { +"Gutschriften, deren Text keine offene Abrechnungsnummer enthält" } }
                    table("t") {
                        tbody {
                            for (b in bank) tr {
                                td("fill") { twoLine("${b.counterparty.ifBlank { "Unbekannt" }} · ${ctx.dayShort(b.bookingDate)}", b.reference.ifBlank { "ohne Verwendungszweck" }) }
                                td("num") { span("money-s c-secondary") { +euroSigned(b.amount) } }
                                if (writes) td("num") {
                                    dialog("$BASE/abrechnung/bank/${b.id}", "btn", "Zuordnen", "Bankumsatz zuordnen") {
                                        postForm(ctx, "$BASE/abrechnung/bank/${b.id}", "stack-tight") {
                                            label("field") {
                                                span { +"Zu welcher Abrechnung" }
                                                select { name = "abrechnung"; for (s in open) option { value = s.id.toString(); +"${s.memberName} · ${s.number} · ${euro(s.amount)}" } }
                                            }
                                            button(type = ButtonType.submit, classes = "btn btn-primary") { +"Zuordnen und buchen" }
                                            label("check") { input(InputType.checkBox, name = "ignorieren") { value = "1" }; span { +"Stattdessen ablegen (kein Deckel)" } }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            div("stack") {
                if (writes) panel {
                    div("panel-body") {
                        h2("title-m") { +"Neuer Lauf" }
                        val month = previewTo.month.getDisplayName(java.time.format.TextStyle.FULL, AT)
                        postForm(ctx, "$BASE/abrechnung/lauf", "stack-tight") {
                            label("field") { span { +"Name" }; input(InputType.text, name = "name") { value = "Bierrechnung $month ${previewTo.year}"; required = true } }
                            div("form-grid") {
                                label("field") { span { +"Zeitraum ab" }; input(InputType.date, name = "von") { value = nextFrom.toString(); required = true } }
                                label("field") { span { +"Stichtag" }; input(InputType.date, name = "stichtag") { value = previewTo.toString(); required = true } }
                                label("field") { span { +"Zahlungsziel" }; input(InputType.date, name = "ziel") { value = previewTo.plusDays(14).toString() } }
                                label("field") { span { +"Abrechnen, wer darunter liegt" }; input(InputType.text, name = "schwelle") { value = Money.formatPlain(threshold); attributes["inputmode"] = "text" } }
                                label("field") { span { +"Zusatzzeile, etwa Semesterbeitrag" }; input(InputType.text, name = "zusatz") { placeholder = "Semesterbeitrag WS 2026/27" } }
                                label("field") { span { +"Zusatzbetrag" }; input(InputType.text, name = "zusatzbetrag") { placeholder = "0,00"; attributes["inputmode"] = "decimal" } }
                            }
                            label("check") { input(InputType.checkBox, name = "alle") { value = "1" }; span { +"Alle Mitglieder, auch die im Plus (etwa für den Semesterbeitrag)" } }
                            button(type = ButtonType.submit, classes = "btn btn-primary btn-wide") { icon("receipt", "m"); +"Lauf anlegen" }
                        }
                        div {
                            h3("title-s") { +"Vorschau zum ${ctx.dayShort(previewTo)}" }
                            if (preview.isEmpty()) p("cap") { +"Niemand liegt unter ${euro(threshold)}." }
                            else {
                                table("t t-tight t-flush") { tbody { for ((m, balance) in preview.take(12)) tr { td("fill") { +m.displayName }; td("num") { span("money-s ${m.tone}") { +euro(balance) } } } } }
                                p("cap") { +"${count(preview.size, "Mitglied", "Mitglieder")}, zusammen ${euro(preview.sumOf { -it.second })} · andere Schwelle oder anderer Stichtag: " ; a(href = "$BASE/abrechnung?stichtag=${previewTo}&schwelle=${Money.formatPlain(threshold)}", classes = "link") { +"in der Adresse ändern" } }
                            }
                        }
                    }
                }
                if (writes) panel {
                    form(action = "$BASE/abrechnung/bank", method = FormMethod.post, encType = FormEncType.multipartFormData, classes = "panel-body") {
                        csrf(ctx)
                        h2("title-m") { +"Kontoauszug einlesen" }
                        p("muted") { +"CAMT.053 (XML) aus dem Online-Banking, oder die CSV der Bank. Gutschriften mit einer Abrechnungsnummer im Text werden gleich gebucht; was schon eingelesen war, bleibt liegen." }
                        label("field") { span { +"Datei" }; input(InputType.file, name = "datei") { accept = ".xml,.csv,text/csv,application/xml,text/xml" } }
                        button(type = ButtonType.submit, classes = "btn btn-wide") { icon("bank", "m"); +"Einlesen" }
                    }
                }
                if (open.isNotEmpty()) panel {
                    panelHead("Offen, nach Fälligkeit")
                    table("t t-tight") { tbody { for (s in open.take(10)) tr { td("fill") { twoLine(s.memberName, "${s.number} · fällig ${ctx.dayShort(s.dueDate)}") }; td("num") { span("money-s${if (s.overdue(ctx.today)) " c-error" else ""}") { +euro(s.amount) } } } } }
                }
            }
        }
    }
}

/** Das Profil im Mitglied: was nur der Server weiß. */
internal fun FlowContent.profileSection(ctx: PageContext, m: MemberLine, profile: Profile, history: List<Statement>, writes: Boolean) = div("stack-tight") {
    div("row") { span("c-muted") { icon("shield", "m") }; span("label-m") { +"Profil — bleibt am Server, die Tablets bekommen davon nichts" } }
    if (profile.email.isBlank() && profile.address.isBlank() && profile.number.isBlank()) p("cap") { +"Noch nichts hinterlegt." }
    else {
        p { +listOfNotNull(profile.number.takeIf { it.isNotBlank() }?.let { "Mitglied $it" }, profile.email.takeIf { it.isNotBlank() }).joinToString(" · ") }
        profile.address.takeIf { it.isNotBlank() }?.let { p("cap") { +it.lines().joinToString(", ") } }
        p("cap") { +(if (profile.canEmail) "E-Mail-Versand erlaubt" else "Keine Einwilligung — die Abrechnung geht in Druck") }
    }
    if (history.isNotEmpty()) div {
        span("label-m") { +"Abrechnungen" }
        table("t t-tight t-flush") { tbody { for (s in history) tr { td("fill") { twoLine(s.number, listOfNotNull(if (s.status == StatementStatus.PAID) "bezahlt" else if (s.status == StatementStatus.CANCELLED) "storniert" else "offen bis ${ctx.dayShort(s.dueDate)}").joinToString()) }; td("num") { a(href = "$BASE/abrechnung/${s.id}.pdf", classes = "link") { +euro(s.amount) } } } } }
    }
    if (writes) dialog("$BASE/mitglieder/${m.id}/profil", "btn", "Profil ändern") {
        postForm(ctx, "$BASE/mitglieder/${m.id}/profil", "stack-tight") {
            label("field") { span { +"Mitgliedsnummer" }; input(InputType.text, name = "nummer") { value = profile.number; maxLength = "20" } }
            label("field") { span { +"E-Mail" }; input(InputType.email, name = "email") { value = profile.email; maxLength = "120" } }
            label("check") { input(InputType.checkBox, name = "einwilligung") { value = "1"; checked = profile.consentEmail }; span { +"Darf Abrechnungen per E-Mail bekommen" } }
            label("field") { span { +"Anschrift (eine Zeile je Zeile)" }; textArea(classes = "input") { name = "anschrift"; rows = "3"; +profile.address } }
            label("field") { span { +"Notizen" }; input(InputType.text, name = "notizen") { value = profile.notes; maxLength = "1000" } }
            button(type = ButtonType.submit, classes = "btn btn-primary") { +"Speichern" }
        }
    }
}
