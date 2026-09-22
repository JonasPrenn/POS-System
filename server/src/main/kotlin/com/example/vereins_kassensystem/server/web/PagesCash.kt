package com.example.vereins_kassensystem.server.web

import com.example.vereins_kassensystem.ui.format.Money
import io.ktor.http.ContentType
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import java.time.LocalDate

private fun dateParam(text: String?, fallback: LocalDate): LocalDate = text?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: fallback

internal fun Route.cashPages(web: Web) {
    get("/kasse") {
        call.guarded(web, Area.CASH) { ctx ->
            val day = dateParam(call.request.queryParameters["tag"], ctx.today)
            val from = dateParam(call.request.queryParameters["von"], ctx.today.minusDays(30))
            val to = dateParam(call.request.queryParameters["bis"], ctx.today)
            call.html { cashPage(ctx, day, from, to, web.cash.dayReport(day), web.cash.sessions(from, to), web.cash.book(from, to), web.statements.allBankTransactions(40)) }
        }
    }
    get("/kasse/kassenbuch.csv") {
        call.guarded(web, Area.CASH) { ctx ->
            val from = dateParam(call.request.queryParameters["von"], ctx.today.minusDays(30))
            val to = dateParam(call.request.queryParameters["bis"], ctx.today)
            val sb = StringBuilder("﻿Datum;Zeit;Gerät;Bewegung;Details;Art;Betrag;Bestand\n")
            for (e in web.cash.book(from, to)) {
                sb.append(listOf(ctx.dayYear(e.at), ctx.time(e.at), e.device, e.text, e.detail, e.kind, e.amount?.let(Money::formatPlain) ?: "", e.balance?.let(Money::formatPlain) ?: "").joinToString(";") { "\"" + it.replace("\"", "\"\"") + "\"" }).append('\n')
            }
            call.response.header("Content-Disposition", "attachment; filename=\"kassenbuch-$from-$to.csv\"")
            call.respondText(sb.toString(), ContentType.Text.CSV.withParameter("charset", "utf-8"))
        }
    }
}

private fun HTML.cashPage(ctx: PageContext, day: LocalDate, from: LocalDate, to: LocalDate, report: List<DeviceDay>, sessions: List<CashSessionLine>, book: List<CashBookEntry>, bank: List<BankTransaction>) {
    val open = sessions.filter { it.closedAt == null }
    val total = report.fold(Revenue()) { a, d -> a + d.revenue }
    shell(ctx, Area.CASH, "Kasse", "Schichten, Zählungen, Entnahmen — was die Tablets melden, hier als Kassenbuch", actions = {
        form(action = "$BASE/kasse", method = FormMethod.get, classes = "row") {
            label("field") { span("sr") { +"Tag" }; input(InputType.date, name = "tag") { value = day.toString() } }
            button(type = kotlinx.html.ButtonType.submit, classes = "btn") { +"Tagesbericht" }
        }
    }) {
        div("cols cols-side") {
            panel {
                panelHead("Tagesbericht, ${ctx.longDateOf(day)}") { span("cap") { +"Aufladungen bar ${euro(report.sumOf { it.topUpCash })} · Stornos ${euro(report.sumOf { it.refunds })}" } }
                if (report.isEmpty()) p("empty") { +"An diesem Tag wurde nichts gebucht." }
                else table("t") {
                    thead { tr { th { +"Gerät" }; th(classes = "num") { +"Bar" }; th(classes = "num hide-sm") { +"Karte" }; th(classes = "num hide-sm") { +"Deckel" }; th(classes = "num") { +"Gesamt" } } }
                    tbody {
                        for (d in report) tr {
                            td("fill") { +d.device }
                            money(d.revenue.cash); money(d.revenue.card, cell = "num hide-sm"); money(d.revenue.tab, cell = "num hide-sm"); money(d.revenue.total, "money-s")
                        }
                        tr("sum") { td { +"Gesamt" }; money(total.cash, "money-s c-primary"); money(total.card, "money-s c-tertiary", "num hide-sm"); money(total.tab, "money-s c-secondary", "num hide-sm"); money(total.total, "money-m") }
                    }
                }
            }
            div("stack") {
                if (open.isEmpty()) panel { div("panel-body") { h2t("Keine Schicht offen"); p("cap") { +"Geöffnet und gezählt wird am Tablet, unter Übersicht → Kasse." } } }
                for (s in open) panel {
                    div("panel-body") {
                        div("row-between") { h2t("Laufende Schicht · ${s.device}"); chip("offen seit ${ctx.time(s.openedAt)}", "ok") }
                        p("cap") { +"Geöffnet von ${s.openedBy}. Gezählt wird am Tablet, nicht hier." }
                        rows(listOf("Anfangsbestand, gezählt" to s.openingCount, "Bareinnahmen bisher" to s.cashIn, "Einlagen" to s.deposits, "Entnahmen" to -s.withdrawals))
                        div("row-between") { span("title-s") { +"Müsste in der Lade sein" }; span("money-m") { +euro(s.expected) } }
                    }
                }
            }
        }
        panel {
            panelHead("Kassenbuch") {
                div("row wrap") {
                    form(action = "$BASE/kasse", method = FormMethod.get, classes = "row") {
                        input(InputType.hidden, name = "tag") { value = day.toString() }
                        label("field") { span("sr") { +"Von" }; input(InputType.date, name = "von") { value = from.toString() } }
                        label("field") { span("sr") { +"Bis" }; input(InputType.date, name = "bis") { value = to.toString() } }
                        button(type = kotlinx.html.ButtonType.submit, classes = "btn") { +"Zeigen" }
                    }
                    a(href = "$BASE/kasse/kassenbuch.csv?von=$from&bis=$to", classes = "btn btn-quiet") { icon("download", "m"); +"CSV" }
                }
            }
            div("panel-note row") { span("c-muted") { icon("lock", "s") }; span("cap") { +"Nachträglich nicht änderbar. Ein Fehler wird am Tablet mit einer Gegenbuchung berichtigt, mit Namen und Grund." } }
            if (book.isEmpty()) p("empty") { +"Keine abgeschlossene Schicht im Zeitraum." }
            else table("t") {
                thead { tr { th { +"Zeit" }; th { +"Bewegung" }; th(classes = "hide-sm") { +"Art" }; th(classes = "num") { +"Betrag" }; th(classes = "num") { +"Bestand" } } }
                tbody {
                    for (e in book) tr {
                        td("c-muted tnum nowrap cap") { +"${ctx.dayShort(e.at.atZone(ctx.zone).toLocalDate())} ${ctx.time(e.at)}" }
                        td("fill") { twoLine(e.text, listOfNotNull(e.device, e.detail.takeIf { it.isNotBlank() }).joinToString(" · ")) }
                        td("hide-sm") {
                            when (e.kind) {
                                "SALES" -> chip("Einnahme", "bar"); "WITHDRAWAL" -> chip("Entnahme"); "DEPOSIT" -> chip("Einlage")
                                "DIFFERENCE" -> chip("Differenz", "warn", "alert"); else -> chip("Zählung")
                            }
                        }
                        td("num") { e.amount?.let { span("money-s${if (it > 0) " c-primary" else if (e.kind == "DIFFERENCE") " c-warning" else ""}") { +euroSigned(it) } } ?: span("c-muted") { +"—" } }
                        td("num c-muted tnum") { +(e.balance?.let(::euro) ?: "") }
                    }
                }
            }
        }
        panel {
            panelHead("Bankbuch") { if (ctx.user.role.may(Area.STATEMENTS)) more("Abrechnung", "$BASE/abrechnung") }
            div("panel-note cap") { +"Die eingelesenen Kontoauszüge, zugeordnet zu Abrechnungen. Was hier fehlt, wurde noch nicht eingelesen." }
            if (bank.isEmpty()) p("empty") { +"Noch kein Kontoauszug eingelesen." }
            else table("t") {
                thead { tr { th { +"Datum" }; th { +"Gegenseite und Text" }; th { +"Status" }; th(classes = "num") { +"Betrag" } } }
                tbody {
                    for (b in bank) tr {
                        td("c-muted tnum nowrap cap") { +ctx.dayShort(b.bookingDate) }
                        td("fill") { twoLine(b.counterparty.ifBlank { "Unbekannt" }, b.reference) }
                        td { when (b.status) { "MATCHED" -> chip("Abrechnung bezahlt", "ok", "check"); "IGNORED" -> chip("abgelegt"); else -> chip("offen", "warn") } }
                        td("num") { span("money-s${if (b.amount > 0) " c-primary" else ""}") { +euroSigned(b.amount) } }
                    }
                }
            }
        }
    }
}

private fun FlowContent.h2t(text: String) = h2("title-m") { +text }

private fun FlowContent.rows(items: List<Pair<String, Double>>) = div("stack-tight") {
    for ((label, value) in items) div("row-between") { span("muted") { +label }; span("money-s") { +euroSigned(value) } }
}

internal fun PageContext.longDateOf(day: LocalDate): String = day.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", AT))
