package com.example.vereins_kassensystem.server.web

import com.example.vereins_kassensystem.data.entity.StockTracking
import com.example.vereins_kassensystem.data.stock.Inventory
import com.example.vereins_kassensystem.ui.format.Quantity
import io.ktor.server.routing.Route
import com.example.vereins_kassensystem.ui.format.Money
import io.ktor.http.encodeURLParameter
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.ButtonType
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.details
import kotlinx.html.hiddenInput
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.select
import kotlinx.html.summary
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
            call.html { stockPage(ctx, stock, web.reads.lastSuppliers(), web.purchases.depositKinds(), web.purchases.depositMovements(limit = 12), call.request.queryParameters["hinweis"], call.request.queryParameters["fehler"]) }
        }
    }
    post("/lager/pfand") {
        call.guardedPost(web, Area.STOCK) { ctx, form ->
            if (!ctx.user.role.writesPurchases) return@guardedPost call.forbidden(ctx, "Pfand buchen Kassier und Budenwart.")
            val outcome = try {
                if (form["neu"] == "1") {
                    web.purchases.createDepositKind(ctx.user, form["name"].orEmpty(), uuidOrNull(form["lieferant"]), "", Money.parse(form["pfand"].orEmpty()) ?: throw AccountProblem("Das Pfand je Gebinde bitte als Zahl, etwa 36,00."))
                    "hinweis=" + "Gebinde angelegt.".encodeURLParameter()
                } else {
                    val kind = uuidOrNull(form["gebinde"]) ?: throw AccountProblem("Bitte ein Gebinde wählen.")
                    web.purchases.recordDeposit(ctx.user, kind, null, java.time.LocalDate.parse(form["tag"].orEmpty().ifBlank { ctx.today.toString() }), form["geliefert"]?.toIntOrNull() ?: 0, form["zurueck"]?.toIntOrNull() ?: 0, form["notiz"].orEmpty())
                    "hinweis=" + "Pfand gebucht.".encodeURLParameter()
                }
            } catch (e: AccountProblem) { "fehler=" + e.message.orEmpty().encodeURLParameter() } catch (e: java.time.format.DateTimeParseException) { "fehler=" + "Das Datum fehlt.".encodeURLParameter() }
            call.respondRedirect("$BASE/lager?$outcome")
        }
    }
    purchasePages(web)
}

// -------------------------------------------------------------------- Lager

private fun HTML.stockPage(ctx: PageContext, stock: List<StockLine>, suppliers: Map<String, String>, kinds: List<Purchases.DepositKind>, moves: List<Purchases.DepositMovement>, notice: String?, problem: String?) {
    val low = stock.filter { it.low }
    val priced = stock.filter { it.value != null }
    val open = stock.mapNotNull { line -> Inventory.openContainer(line.state)?.let { line to it } }

    shell(ctx, Area.STOCK, "Lager", "Was die Tablets führen, von oben gesehen: Bestand, Fässer, was fehlt — und das Pfand beim Lieferanten") {
        flash(notice, problem)
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
            div("stack") {
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
            depositPanel(ctx, kinds, moves)
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
                    panelHead("Bestellvorschlag") { span("cap") { +"nach dem Lieferanten der letzten Lieferung" } }
                    // Bestellt wird auf das Doppelte des Mindestbestands, bei Fässern in ganzen Gebinden — eine Zahl zum Anrufen, keine Bestellung.
                    for ((supplier, lines) in low.groupBy { suppliers[it.state.item.id] ?: "" }.toSortedMap(compareBy({ it.isEmpty() }, { it }))) {
                        div("panel-note") { span("title-s") { +supplier.ifEmpty { "Ohne bekannten Lieferanten" } } }
                        table("t t-tight") {
                            tbody {
                                for (line in lines) tr {
                                    val item = line.state.item
                                    val missing = item.minLevel - line.available
                                    val target = maxOf(0.0, 2 * item.minLevel - line.available)
                                    val keg = line.state.containerTypes.maxByOrNull { it.nominalSize }
                                    val proposal = if (item.tracking == StockTracking.CONTAINER && keg != null && keg.nominalSize > 0) "${kotlin.math.ceil(target / keg.nominalSize).toInt()}× ${keg.label}" else "${Quantity.format(kotlin.math.ceil(target))} ${item.unit}"
                                    td { twoLine(item.name, "fehlen ${Quantity.format(missing)} ${item.unit} bis zum Mindestbestand") }
                                    td("num") { span("money-s") { +proposal } }
                                }
                            }
                        }
                    }
                    div("panel-foot cap") { +"Vorschlag: auf das Doppelte des Mindestbestands auffüllen, Fässer in ganzen Gebinden. Bestellt wird beim Lieferanten, nicht hier." }
                }
            }
        }
    }
}

/** Pfand und Leergut: je Gebindeart, was beim Lieferanten liegt — geliefert minus zurück, mal Pfand je Stück. */
private fun FlowContent.depositPanel(ctx: PageContext, kinds: List<Purchases.DepositKind>, moves: List<Purchases.DepositMovement>) = panel {
    val writes = ctx.user.role.writesPurchases
    panelHead("Pfand und Leergut") { span("cap") { +"je Gebindeart, unabhängig vom Inhalt" } }
    if (kinds.isEmpty()) p("empty") { +"Noch kein Pfandgebinde. Es entsteht aus einer gelesenen Rechnung (Gebindetabelle oder Pfandzeile) oder hier von Hand." }
    else table("t t-tight") {
        thead { tr { th { +"Gebinde" }; th(classes = "num") { +"Bestand" }; th(classes = "num hide-sm") { +"Pfand/Stk" }; th(classes = "num") { +"Pfandwert" } } }
        tbody {
            for (k in kinds) tr {
                td("fill") { twoLine(k.name, listOfNotNull(k.supplier.takeIf { it.isNotBlank() }, k.code.takeIf { it.isNotBlank() }?.let { "Nr. $it" }, k.lastAt?.let { "zuletzt ${ctx.dayShort(it)}" }).joinToString(" · ")) }
                td("num") { span("money-s${if (k.held < 0) " c-warning" else ""}") { +"${k.held}" } }
                td("num tnum c-muted hide-sm") { +euro(k.deposit) }
                td("num") { span("money-s") { +euro(k.value) } }
            }
            tr("sum") { td { +"Beim Lieferanten liegt" }; td { }; td("hide-sm") { }; td("num") { span("money-m") { +euro(kinds.sumOf { it.value }) } } }
        }
    }
    if (moves.isNotEmpty()) div("panel-note") {
        span("cap") { +("Zuletzt: " + moves.take(6).joinToString(" · ") { m -> "${ctx.dayShort(m.day)} ${m.kind} ${listOfNotNull(m.delivered.takeIf { it > 0 }?.let { "+$it" }, m.returned.takeIf { it > 0 }?.let { "−$it" }).joinToString("/")}" }) }
    }
    if (writes) div("panel-foot") {
        div("row wrap") {
            if (kinds.isNotEmpty()) details {
                summary("btn") { icon("plus", "m"); +"Leergut zurück / geliefert" }
                postForm(ctx, "$BASE/lager/pfand", "stack-tight confirm confirm-left") {
                    label("field") { span { +"Gebinde" }; select { name = "gebinde"; for (k in kinds) option { value = k.id.toString(); +"${k.name}${k.supplier.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}" } } }
                    div("form-grid") {
                        label("field") { span { +"Zurückgegeben" }; input(InputType.text, name = "zurueck") { placeholder = "5"; attributes["inputmode"] = "numeric" } }
                        label("field") { span { +"Geliefert (ohne Beleg)" }; input(InputType.text, name = "geliefert") { placeholder = "0"; attributes["inputmode"] = "numeric" } }
                        label("field") { span { +"Am" }; input(InputType.date, name = "tag") { value = ctx.today.toString() } }
                    }
                    label("field") { span { +"Notiz" }; input(InputType.text, name = "notiz") { maxLength = "120"; placeholder = "Leergut mitgegeben, Gutschrift folgt" } }
                    button(type = ButtonType.submit, classes = "btn btn-primary") { +"Buchen" }
                }
            }
            details {
                summary("btn btn-quiet") { +"Gebinde anlegen" }
                postForm(ctx, "$BASE/lager/pfand", "stack-tight confirm confirm-left") {
                    hiddenInput(name = "neu") { value = "1" }
                    label("field") { span { +"Name (etwa Fass 20 l, Kiste 12 × 1 l)" }; input(InputType.text, name = "name") { required = true; maxLength = "80" } }
                    label("field") { span { +"Pfand je Gebinde in Euro, brutto" }; input(InputType.text, name = "pfand") { required = true; placeholder = "36,00"; attributes["inputmode"] = "decimal" } }
                    button(type = ButtonType.submit, classes = "btn btn-primary") { +"Anlegen" }
                }
            }
        }
        p("cap") { +"Pfand aus einer Rechnung kommt über den Beleg (Zeile „Pfand: …“); hier nur, was ohne Beleg läuft — Leergut, das mit dem Fahrer zurückgeht." }
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
