package com.example.vereins_kassensystem.server.web

import com.example.vereins_kassensystem.data.entity.StockTracking
import com.example.vereins_kassensystem.data.stock.Inventory
import com.example.vereins_kassensystem.ui.format.Quantity
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.div
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.img
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

internal fun Route.warePages(web: Web) {
    get("/lager") {
        call.guarded(web, Area.STOCK) { ctx ->
            val stock = web.reads.stock()
            call.html { stockPage(ctx, stock) }
        }
    }
    get("/einkauf") {
        call.guarded(web, Area.PURCHASES) { ctx ->
            val all = web.reads.deliveries(60)
            val selected = uuidOrNull(call.request.queryParameters["b"])?.let { id -> all.firstOrNull { it.id == id } }
            val shown = selected ?: all.firstOrNull()
            call.html { purchasesPage(ctx, all, shown, selected != null, shown?.let { web.reads.deliveryPositions(it.id) }.orEmpty()) }
        }
    }
}

// -------------------------------------------------------------------- Lager

private fun HTML.stockPage(ctx: PageContext, stock: List<StockLine>) {
    val low = stock.filter { it.low }
    val priced = stock.filter { it.value != null }
    val open = stock.mapNotNull { line -> Inventory.openContainer(line.state)?.let { line to it } }

    shell(ctx, Area.STOCK, "Lager", "Was die Tablets führen, von oben gesehen: Bestand, Fässer, was fehlt") {
        panel {
            div("figures") {
                figure("Lagerwert") {
                    span("money-m") { +(if (priced.isEmpty()) "—" else euro(priced.sumOf { it.value ?: 0.0 })) }
                    span("cap") {
                        +when {
                            priced.isEmpty() -> "noch kein Wareneingang mit Betrag"
                            priced.size < stock.size -> "zu Einstandspreisen · ${priced.size} von ${stock.size} Artikeln haben einen"
                            else -> "zu Einstandspreisen aus den Wareneingängen"
                        }
                    }
                }
                figure("Unter Mindestbestand") {
                    span("money-m${if (low.isNotEmpty()) " c-warning" else ""}") { +"${low.size} Artikel" }
                    span("cap") { +(low.joinToString(", ") { it.state.item.name }.ifEmpty { "nichts fehlt" }) }
                }
                figure("Am Hahn") {
                    span("money-m") { +"${open.size}" }
                    span("cap") { +(open.joinToString(", ") { it.first.state.item.name }.ifEmpty { "kein Fass angestochen" }) }
                }
                figure("Artikel") {
                    span("money-m") { +"${stock.size}" }
                    span("cap") { +"geführt am Tablet, hier nur gelesen" }
                }
            }
        }
        div("cols cols-side") {
            panel {
                panelHead("Bestand") { span("cap") { +"Eingänge minus Abgänge — kein Zähler, den zwei Theken gleichzeitig fortschreiben" } }
                if (stock.isEmpty()) p("empty") { +"Noch keine Lagerartikel. Sie entstehen am Tablet und kommen mit dem Abgleich." }
                else table("t") {
                    thead { tr { th { +"Artikel" }; th(classes = "num") { +"Bestand" }; th(classes = "num hide-sm") { +"Mindestbestand" }; th(classes = "num hide-sm") { +"Wert" }; th { +"Lage" } } }
                    tbody {
                        for (line in stock) tr {
                            val item = line.state.item
                            val full = Inventory.fullContainers(line.state).filter { it.second > 0 }.joinToString(" · ") { "${it.second}× ${it.first.label}" }
                            td { twoLine(item.name, if (item.tracking == StockTracking.CONTAINER) full.ifEmpty { "kein volles Gebinde" } else item.unit) }
                            td("num") { span("money-s") { +"${if (item.tracking == StockTracking.CONTAINER) "ca. " else ""}${Quantity.format(line.available)} ${item.unit}" } }
                            td("num c-muted tnum hide-sm") { +"${Quantity.format(item.minLevel)} ${item.unit}" }
                            td("num tnum hide-sm") { +(line.value?.let(::euro) ?: "—") }
                            td {
                                when {
                                    line.available < 0 -> chip("im Minus", "error", "alert")
                                    line.low -> chip("fehlen ${Quantity.format(item.minLevel - line.available)} ${item.unit}", "warn")
                                    else -> chip("reicht")
                                }
                            }
                        }
                    }
                }
            }
            div("stack") {
                for ((line, tapped) in open) kegPanel(ctx, line, tapped)
                val learned = stock.flatMap { line -> Inventory.yields(line.state).map { line to it } }
                if (learned.isNotEmpty()) panel {
                    panelHead("Fasserträge, von der App gelernt")
                    table("t t-tight") {
                        tbody {
                            for ((line, estimate) in learned) tr {
                                td { +"${line.state.item.name}, ${estimate.containerType.label}" }
                                td("num") { span("money-s") { +"${Quantity.format(estimate.perContainer)} ${line.state.item.unit}" } }
                                td("num c-muted cap") { +(if (estimate.isLearned) "${estimate.observations} Fässer" else "Schätzwert") }
                            }
                        }
                    }
                }
                if (low.isNotEmpty()) panel {
                    panelHead("Was fehlt")
                    table("t t-tight") {
                        tbody {
                            for (line in low) tr {
                                td { +line.state.item.name }
                                td("num") { span("money-s") { +"${Quantity.format(line.state.item.minLevel - line.available)} ${line.state.item.unit}" } }
                            }
                        }
                    }
                    div("panel-foot cap") { +"Bis zum Mindestbestand. Lieferanten je Artikel und der Bestellvorschlag kommen mit der Stammdatenpflege." }
                }
            }
        }
    }
}

private fun FlowContent.kegPanel(ctx: PageContext, line: StockLine, tapped: com.example.vereins_kassensystem.data.entity.TappedContainer) = panel {
    val type = line.state.containerTypes.first { it.id == tapped.containerTypeId }
    val estimate = Inventory.yieldFor(type, line.state.tapped)
    val left = maxOf(0.0, estimate.perContainer - tapped.drawn)
    val unit = line.state.item.unit
    div("panel-body") {
        div("row-between") {
            h2("title-m") { +"Am Hahn" }
            chip("seit ${ctx.friendly(java.time.Instant.ofEpochMilli(tapped.openedAt))}")
        }
        div("row") {
            span("c-secondary") { icon("keg", "l") }
            twoLine("${line.state.item.name}, ${type.label}", "${Quantity.format(tapped.drawn)} $unit gezapft · noch ca. ${Quantity.format(left)} $unit")
        }
        bar(listOf(minOf(100.0, tapped.drawn / estimate.perContainer * 100) to "f-secondary"), "Anteil gezapft")
        span("cap") {
            +(if (estimate.isLearned) "Gerechnet mit dem gelernten Ertrag von ${Quantity.format(estimate.perContainer)} $unit, nicht mit dem Nennwert."
            else "Gerechnet mit dem Schätzwert von ${Quantity.format(estimate.perContainer)} $unit — noch kein Fass dieser Größe gemessen.")
        }
    }
}

// ------------------------------------------------------------------ Einkauf

private fun HTML.purchasesPage(ctx: PageContext, all: List<DeliveryLine>, shown: DeliveryLine?, chosen: Boolean, positions: List<DeliveryPosition>) {
    val thisMonth = all.filter { java.time.YearMonth.from(it.at.atZone(ctx.zone)) == java.time.YearMonth.from(ctx.today) }
    shell(ctx, Area.PURCHASES, "Einkauf", "Wareneingänge, wie sie am Tablet gebucht wurden — mit Belegfoto") {
        panel {
            div("figures") {
                figure("Wareneingang diesen Monat") { span("money-m") { +euro(thisMonth.sumOf { it.total ?: 0.0 }) }; span("cap") { +"${thisMonth.size} Belege" } }
                figure("Mit Foto") { span("money-m") { +"${all.count { it.photoKey != null }} von ${all.size}" }; span("cap") { +"Fotos kommen beim Abgleich mit" } }
                figure("Ohne Betrag") { span("money-m") { +"${all.count { it.total == null }}" }; span("cap") { +"Bonsumme am Tablet nicht eingetragen" } }
                figure("Noch nicht hier") { span("title-s") { +"Fälligkeit, Zahlung, Konten" }; span("cap") { +"kommt mit der Stammdatenpflege" } }
            }
        }
        div("cols cols-side split${if (chosen) " has-sel" else ""}") {
            panel("split-list") {
                panelHead("Eingangsbelege")
                if (all.isEmpty()) p("empty") { +"Noch kein Wareneingang gebucht. Er entsteht am Tablet unter Lagerbestand → Wareneingang." }
                else table("t") {
                    thead { tr { th { +"Lieferant" }; th(classes = "hide-sm") { +"Positionen" }; th(classes = "num") { +"Bonsumme" } } }
                    tbody {
                        for (d in all) tr("pick") {
                            if (chosen && d.id == shown?.id) attributes["aria-selected"] = "true"
                            td {
                                div("row") {
                                    span("c-muted") { icon(if (d.photoKey != null) "camera" else "filein") }
                                    a(href = "$BASE/einkauf?b=${d.id}", classes = "cover two") {
                                        span("title-s") { +d.supplier.ifBlank { "Ohne Lieferant" } }
                                        span("cap") { +ctx.friendly(d.at) }
                                    }
                                }
                            }
                            td("c-muted tnum hide-sm") { +"${d.positions}" }
                            td("num") { span("money-s") { +(d.total?.let(::euro) ?: "—") } }
                        }
                    }
                }
            }
            div("split-detail stack") {
                a(href = "$BASE/einkauf", classes = "back") { icon("back", "m"); +"Alle Belege" }
                if (shown != null) panel {
                    div("panel-body") {
                        div("row-between") {
                            div("two") { h2("title-m") { +shown.supplier.ifBlank { "Ohne Lieferant" } }; span("muted") { +ctx.dayYear(shown.at) } }
                            span("money-m") { +(shown.total?.let(::euro) ?: "—") }
                        }
                        if (shown.photoKey != null) a(href = "$BASE/einkauf/foto/${shown.photoKey}") { img(alt = "Belegfoto", src = "$BASE/einkauf/foto/${shown.photoKey}", classes = "photo") }
                        else div("photo-none") { icon("camera", "l"); span("cap") { +"Kein Foto zu diesem Beleg" } }
                        shown.note?.takeIf { it.isNotBlank() }?.let { p("muted") { +it } }
                        div {
                            h3("title-s") { +"Positionen" }
                            if (positions.isEmpty()) p("cap") { +"Keine Lagerpositionen — nur der Beleg." }
                            else table("t t-tight t-flush") {
                                tbody {
                                    for (pos in positions) tr {
                                        td { twoLine(pos.item, "${Quantity.formatSigned(pos.quantity)} ${pos.unit}${if (pos.source == "CORRECTION") " · Korrektur" else ""}") }
                                        td("num") { span("money-s") { +(pos.cost?.let(::euro) ?: "—") } }
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
