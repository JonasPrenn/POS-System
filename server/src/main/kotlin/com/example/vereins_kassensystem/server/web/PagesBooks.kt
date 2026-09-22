package com.example.vereins_kassensystem.server.web

import com.example.vereins_kassensystem.ui.format.Money
import io.ktor.http.ContentType
import io.ktor.http.encodeURLParameter
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
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
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.hiddenInput
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.ul

/** Welches Rechnungsjahr die Seite zeigt: das gewünschte, sonst das laufende; die Auswahl reicht bis zur ersten Buchung zurück. */
private class YearChoice(val year: Int, val choices: List<Int>)

private fun yearChoice(ctx: PageContext, web: Web, wanted: String?): YearChoice {
    val start = ctx.verein.fiscalStartMonth
    val current = if (ctx.today.monthValue >= start) ctx.today.year else ctx.today.year - 1
    val year = wanted?.toIntOrNull()?.takeIf { it in 2000..current } ?: current
    val first = web.reads.firstBookingYear()?.let { if (start > 1) it - 1 else it } ?: current
    return YearChoice(year, (maxOf(first, current - 9)..current).toList().reversed())
}

internal fun Route.bookPages(web: Web) {
    get("/buecher") {
        call.guarded(web, Area.BOOKS) { ctx ->
            val choice = yearChoice(ctx, web, call.request.queryParameters["jahr"])
            val start = ctx.verein.fiscalStartMonth
            val year = web.books.fiscalYear(choice.year, start)
            val books = web.books.year(year, web.books.fiscalYear(choice.year - 1, start))
            val asOf = if (year.contains(ctx.today)) ctx.today else year.to.minusDays(1)
            val stockValue = web.reads.stock().mapNotNull { it.value }.takeIf { it.isNotEmpty() }?.sum()
            val assets = web.books.assets(asOf, ctx.today, web.books.bankBalance(choice.year), stockValue, web.purchases.depositValue())
            call.html { booksPage(ctx, choice, books, assets, call.request.queryParameters["hinweis"], call.request.queryParameters["fehler"]) }
        }
    }
    get("/buecher/ear.csv") {
        call.guarded(web, Area.BOOKS) { ctx ->
            val choice = yearChoice(ctx, web, call.request.queryParameters["jahr"])
            val start = ctx.verein.fiscalStartMonth
            val books = web.books.year(web.books.fiscalYear(choice.year, start), web.books.fiscalYear(choice.year - 1, start))
            val sb = StringBuilder("﻿Konto;Bezeichnung;Bereich;Art;Rechnungsjahr ${choice.year};Vorjahr\n")
            for (l in books.lines) sb.append(csv(l.code, l.name, Books.AREAS[l.area] ?: l.area, if (l.income) "Einnahme" else "Ausgabe", Money.formatPlain(l.amount), Money.formatPlain(l.previous)))
            sb.append(csv("", "Einnahmen gesamt", "", "", Money.formatPlain(books.income), Money.formatPlain(books.incomePrevious)))
            sb.append(csv("", "Ausgaben gesamt", "", "", Money.formatPlain(books.expense), Money.formatPlain(books.expensePrevious)))
            sb.append(csv("", "Überschuss", "", "", Money.formatPlain(books.income - books.expense), Money.formatPlain(books.incomePrevious - books.expensePrevious)))
            call.response.header("Content-Disposition", "attachment; filename=\"einnahmen-ausgaben-${choice.year}.csv\"")
            call.respondText(sb.toString(), ContentType.Text.CSV.withParameter("charset", "utf-8"))
        }
    }
    get("/buecher/journal.csv") {
        call.guarded(web, Area.BOOKS) { ctx ->
            val choice = yearChoice(ctx, web, call.request.queryParameters["jahr"])
            val year = web.books.fiscalYear(choice.year, ctx.verein.fiscalStartMonth)
            val sb = StringBuilder("﻿Datum;Konto;Bereich;Text;Betrag;Beleg\n")
            for (e in web.books.journal(year)) sb.append(csv(e.day.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")), e.account, Books.AREAS[e.area] ?: e.area, e.text, Money.formatPlain(e.amount), e.reference))
            call.response.header("Content-Disposition", "attachment; filename=\"journal-${choice.year}.csv\"")
            call.respondText(sb.toString(), ContentType.Text.CSV.withParameter("charset", "utf-8"))
        }
    }
    get("/buecher/mappe.pdf") {
        call.guarded(web, Area.BOOKS) { ctx ->
            val choice = yearChoice(ctx, web, call.request.queryParameters["jahr"])
            val start = ctx.verein.fiscalStartMonth
            val year = web.books.fiscalYear(choice.year, start)
            val books = web.books.year(year, web.books.fiscalYear(choice.year - 1, start))
            val asOf = if (year.contains(ctx.today)) ctx.today else year.to.minusDays(1)
            val stockValue = web.reads.stock().mapNotNull { it.value }.takeIf { it.isNotEmpty() }?.sum()
            val assets = web.books.assets(asOf, ctx.today, web.books.bankBalance(choice.year), stockValue, web.purchases.depositValue())
            val pdf = AuditBundlePdf.render(AuditBundlePdf.Input(
                club = ctx.verein.name, year = books, assets = assets,
                cashBook = web.cash.book(year.from, year.to.minusDays(1)),
                documents = web.purchases.documentsBetween(year.from, year.to),
                runs = web.statements.runs().filter { it.to >= year.from && it.to < year.to },
                createdBy = ctx.user.displayName, createdAt = ctx.today, day = { ctx.day(it) }, time = { ctx.time(it) },
            ))
            web.audit.record(ctx.user, "books.bundle", choice.year.toString())
            call.response.header("Content-Disposition", "inline; filename=\"pruefermappe-${choice.year}.pdf\"")
            call.respondBytes(pdf, ContentType.Application.Pdf)
        }
    }
    post("/buecher/bank") {
        call.guardedPost(web, Area.BOOKS) { ctx, form ->
            if (!ctx.user.role.writesMembers) return@guardedPost call.forbidden(ctx, "Den Bankstand trägt der Kassier ein.")
            val year = form["jahr"]?.toIntOrNull() ?: return@guardedPost call.respondRedirect("$BASE/buecher")
            val outcome = try { web.books.saveBankBalance(ctx.user, year, form["betrag"].orEmpty()); "hinweis=" + "Bankstand gespeichert.".encodeURLParameter() } catch (e: AccountProblem) { "fehler=" + e.message.orEmpty().encodeURLParameter() }
            call.respondRedirect("$BASE/buecher?jahr=$year&$outcome")
        }
    }
}

private fun csv(vararg cells: String): String = cells.joinToString(";") { "\"" + it.replace("\"", "\"\"") + "\"" } + "\n"

private fun HTML.booksPage(ctx: PageContext, choice: YearChoice, books: YearBooks, assets: Assets, notice: String?, problem: String?) {
    val y = books.year
    val running = y.contains(ctx.today)
    val span = "${ctx.dayShort(y.from)} bis ${ctx.dayShort(y.to.minusDays(1))}"
    shell(ctx, Area.BOOKS, "Bücher", "Einnahmen-Ausgaben-Rechnung und Vermögensübersicht — aus den Buchungen und Belegen, nicht aus einer zweiten Buchhaltung", actions = {
        form(action = "$BASE/buecher", method = FormMethod.get, classes = "row") {
            label("field") {
                span("sr") { +"Rechnungsjahr" }
                select { name = "jahr"; for (c in choice.choices) option { value = c.toString(); if (c == choice.year) selected = true; +(if (ctx.verein.fiscalStartMonth == 1) "$c" else "$c/${(c + 1) % 100}") } }
            }
            button(type = ButtonType.submit, classes = "btn") { +"Zeigen" }
        }
        a(href = "$BASE/buecher/ear.csv?jahr=${choice.year}", classes = "btn btn-quiet") { icon("download", "m"); +"E/A als CSV" }
        a(href = "$BASE/buecher/journal.csv?jahr=${choice.year}", classes = "btn btn-quiet") { icon("download", "m"); +"Journal als CSV" }
        a(href = "$BASE/buecher/mappe.pdf?jahr=${choice.year}", classes = "btn btn-primary") { icon("printer", "m"); +"Prüfermappe (PDF)" }
    }) {
        flash(notice, problem)
        div("cols cols-side") {
            panel {
                panelHead("Einnahmen-Ausgaben-Rechnung ${choice.year}") { span("cap") { +(if (running) "$span · läuft noch" else span) } }
                table("t") {
                    thead { tr { th { +"Konto" }; th(classes = "num") { +choice.year.toString() }; th(classes = "num hide-sm") { +"Vorjahr" } } }
                    tbody {
                        for ((income, heading) in listOf(true to "Einnahmen", false to "Ausgaben")) {
                            tr("group") { td { attributes["colspan"] = "3"; span("label-s") { +heading } } }
                            for ((area, areaName) in Books.AREAS) {
                                val lines = books.lines.filter { it.income == income && it.area == area }
                                if (lines.isEmpty()) continue
                                for (l in lines) tr {
                                    td("fill") { twoLine(l.name, "$areaName · ${l.code}") }
                                    money(l.amount, "money-s${if (income) " c-primary" else ""}")
                                    money(l.previous, "tnum c-muted", "num hide-sm")
                                }
                            }
                            tr("sum") {
                                td { +"$heading gesamt" }
                                money(if (income) books.income else books.expense, "money-s")
                                money(if (income) books.incomePrevious else books.expensePrevious, "tnum c-muted", "num hide-sm")
                            }
                        }
                        val result = books.income - books.expense
                        tr("sum") {
                            td { +(if (result >= 0) "Überschuss" else "Abgang") }
                            money(result, "money-m${if (result >= 0) " c-primary" else " c-warning"}")
                            money(books.incomePrevious - books.expensePrevious, "tnum c-muted", "num hide-sm")
                        }
                    }
                }
                div("panel-foot cap") { +"Auf den Deckel geschrieben, ohne Zufluss: ${euro(books.onTab)} (Vorjahr ${euro(books.onTabPrevious)}). Das wird zur Einnahme, wenn das Mitglied auflädt oder die Abrechnung zahlt." }
            }
            div("stack") {
                panel {
                    panelHead("Vermögensübersicht") { span("cap") { +"zum ${ctx.dayShort(assets.asOf)}" } }
                    div("panel-body stack-tight") {
                        assetRow("Kassabestand", assets.cash, assets.cashDetail)
                        assetRow("Bankstand", assets.bank, if (assets.bank == null) "nicht eingetragen" else "laut Kontoauszug, eingetragen")
                        assetRow("Lagerwert", assets.stockValue, if (assets.stockValue == null) (if (assets.asOf >= ctx.today) "noch kein Wareneingang mit Betrag" else "nur für heute rechenbar") else "zu Einstandspreisen, Stand heute")
                        assetRow("Pfand beim Lieferanten", assets.deposits, "gehaltene Gebinde mal Pfand je Stück")
                        assetRow("Forderungen", assets.receivables, "Deckel im Minus")
                        assetRow("Verbindlichkeiten: Guthaben", -assets.memberCredits, "Deckel im Plus — der Verein schuldet es")
                        assetRow("Verbindlichkeiten: offene Belege", -assets.openInvoices, "Lieferantenbelege, noch nicht bezahlt")
                        div("row-between") { span("title-s") { +"Reinvermögen" }; span("money-m${if (assets.total >= 0) "" else " c-warning"}") { +euro(assets.total) } }
                    }
                    if (ctx.user.role.writesMembers) div("panel-foot") {
                        postForm(ctx, "$BASE/buecher/bank", "row wrap") {
                            hiddenInput(name = "jahr") { value = choice.year.toString() }
                            label("field") { span { +"Bankstand zum Stichtag laut Kontoauszug" }; input(InputType.text, name = "betrag") { value = assets.bank?.let { Money.formatPlain(it).replace('.', ',') }.orEmpty(); placeholder = "1250,40"; attributes["inputmode"] = "decimal" } }
                            button(type = ButtonType.submit, classes = "btn") { +"Eintragen" }
                        }
                    }
                }
                panel {
                    panelHead("So wird gerechnet")
                    div("panel-body") {
                        ul("cap stack-tight") {
                            li { +"Einnahme ist, was bar, mit Karte oder per Überweisung eingegangen ist — zum Tag des Eingangs. Verkäufe auf den Deckel sind Forderungen, bis sie bezahlt sind." }
                            li { +"Ausgabe ist ein bezahlter Beleg zum Zahltag. Belegzeilen tragen ihr Konto; Lagerzeilen zählen als Getränkeeinkauf. Speisen und Sonstiges als eigene Belegzeile erfassen." }
                            li { +"Ein Wareneingang vom Tablet ohne Beleg in der Verwaltung gilt mit seinem Betrag als bar bezahlt am Tag des Eingangs." }
                            li { +"Entnahmen aus der Lade sind keine Ausgaben — der Beleg dazu ist es. Trinkgeld steht als eigene Einnahme." }
                            li { +"Keine doppelte Buchführung, keine Abschreibung, kein Lohn. Das Journal als CSV ist das, was der Steuerberater bekommt; die Prüfermappe als PDF das, was die Rechnungsprüfer bekommen: Rechnung, Vermögen, Kassabuch, Belegliste, Abrechnungen." }
                        }
                    }
                }
            }
        }
    }
}

private fun FlowContent.assetRow(label: String, amount: Double?, note: String) = div("row-between") {
    twoLine(label, note)
    span("money-s${if (amount != null && amount < 0) " c-muted" else ""}") { +(amount?.let(::euroSigned) ?: "—") }
}
