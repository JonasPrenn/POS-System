package com.example.vereins_kassensystem.server.web

import com.example.vereins_kassensystem.server.devices.Tokens
import com.example.vereins_kassensystem.ui.format.Money
import com.example.vereins_kassensystem.ui.format.Quantity
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
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
import kotlinx.html.dataList
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.hiddenInput
import kotlinx.html.img
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
import kotlinx.io.readByteArray
import java.time.LocalDate
import java.util.UUID

/** Ein abgeschicktes Formular mit Datei: die Felder und, wenn dabei, die Datei. */
private class Upload(val fields: Map<String, String>, val fileName: String?, val contentType: ContentType?, val bytes: ByteArray?)

/**
 * Wie [guardedPost], aber für `multipart/form-data`: Das Sitzungsgeheimnis steckt in einem
 * Feld zwischen den Teilen, die Datei bleibt im Speicher, bis feststeht, dass sie klein genug ist.
 */
private suspend fun ApplicationCall.guardedUpload(web: Web, area: Area, block: suspend (PageContext, Upload) -> Unit) = guarded(web, area) { ctx ->
    val fields = HashMap<String, String>()
    var fileName: String? = null
    var contentType: ContentType? = null
    var bytes: ByteArray? = null
    var tooBig = false
    receiveMultipart(formFieldLimit = 64 * 1024).forEachPart { part ->
        when (part) {
            is PartData.FormItem -> fields[part.name.orEmpty()] = part.value
            is PartData.FileItem -> if (part.originalFileName.orEmpty().isNotEmpty()) {
                val data = part.provider().readRemaining(web.receipts.maxBytes + 1).readByteArray()
                if (data.size > web.receipts.maxBytes) tooBig = true
                else { fileName = part.originalFileName; contentType = part.contentType; bytes = data }
            }
            else -> Unit
        }
        part.dispose()
    }
    if (!Tokens.constantTimeEquals(fields["_csrf"].orEmpty(), ctx.session.csrf)) {
        return@guarded forbidden(ctx, "Das Formular ist abgelaufen. Bitte die Seite neu laden und noch einmal versuchen.")
    }
    if (tooBig) return@guarded respondRedirect("$BASE/einkauf?fehler=${"Die Datei ist größer als 20 MB.".encodeURLParameter()}")
    block(ctx, Upload(fields, fileName, contentType, bytes))
}

private fun dateOrNull(text: String?): LocalDate? = text?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

internal fun Route.purchasePages(web: Web) {
    get("/einkauf") {
        call.guarded(web, Area.PURCHASES) { ctx ->
            val all = web.purchases.documents(80)
            val selected = uuidOrNull(call.request.queryParameters["b"])?.let { id -> all.firstOrNull { it.id == id } ?: web.purchases.document(id) }
            val shown = selected ?: all.firstOrNull()
            val fresh = call.request.queryParameters["neu"] == "1"
            call.html {
                purchasesPage(
                    ctx, all, shown, selected != null || fresh, fresh, web.purchases.openDocuments(),
                    shown?.let { web.purchases.stockLines(it.deliveryId) }.orEmpty(), shown?.takeIf { it.hasDocument }?.let { web.purchases.expenseLines(it.id) }.orEmpty(),
                    web.purchases.suppliers(), web.purchases.expenseAccounts(), web.purchases.stockChoices(),
                    call.request.queryParameters["hinweis"], call.request.queryParameters["fehler"]
                )
            }
        }
    }
    get("/einkauf/datei/{key}") {
        call.guarded(web, Area.PURCHASES) {
            val key = call.parameters["key"].orEmpty()
            val stored = web.receipts.find(key)?.takeIf { web.purchases.fileKeyExists(key) || web.reads.photoKeyExists(key) }
                ?: return@guarded call.respondText("Keine Datei unter diesem Schlüssel.", status = HttpStatusCode.NotFound)
            call.response.header("Cache-Control", "private, max-age=3600")
            call.response.header("Content-Disposition", "inline; filename=\"beleg-${key.take(8)}.${key.substringAfterLast('.')}\"")
            call.respond(LocalFileContent(stored.path.toFile(), stored.contentType))
        }
    }
    // Belegdaten — neu (ohne id) oder zu einem vorhandenen Beleg/Wareneingang; mit oder ohne Datei.
    post("/einkauf/beleg") {
        call.guardedUpload(web, Area.PURCHASES) { ctx, up ->
            if (!ctx.user.role.writesPurchases) return@guardedUpload call.forbidden(ctx, "Belege erfassen Kassier und Budenwart.")
            val id = uuidOrNull(up.fields["id"])
            val outcome = try {
                val fileKey = up.bytes?.let { bytes ->
                    val extension = web.receipts.extensionFor(up.contentType) ?: throw AccountProblem("Als Datei gehen PDF, JPG, PNG, WEBP und HEIC.")
                    web.receipts.store(bytes, extension)
                }
                val head = Purchases.Head(
                    supplier = up.fields["lieferant"].orEmpty(), number = up.fields["nummer"].orEmpty(),
                    date = dateOrNull(up.fields["datum"]) ?: throw AccountProblem("Das Belegdatum fehlt."),
                    dueDate = dateOrNull(up.fields["faellig"]),
                    gross = up.fields["brutto"]?.takeIf { it.isNotBlank() }?.let { Money.parse(it) ?: throw AccountProblem("Den Bruttobetrag bitte als Zahl.") },
                    vat = up.fields["ust"]?.takeIf { it.isNotBlank() }?.let { Money.parse(it) ?: throw AccountProblem("Die Umsatzsteuer bitte als Zahl.") } ?: 0.0,
                    payment = Payment.entries.firstOrNull { it.name == up.fields["zahlung"] } ?: Payment.OPEN,
                    paidAt = dateOrNull(up.fields["bezahlt"]), note = up.fields["notiz"].orEmpty(),
                )
                val saved = web.purchases.saveHead(ctx.user, id, head, fileKey)
                "b=$saved&hinweis=" + "Beleg gespeichert.".encodeURLParameter()
            } catch (e: AccountProblem) {
                (if (id != null) "b=$id&" else "neu=1&") + "fehler=" + e.message.orEmpty().encodeURLParameter()
            }
            call.respondRedirect("$BASE/einkauf?$outcome")
        }
    }
    post("/einkauf/{id}/bezahlt") {
        call.guardedPost(web, Area.PURCHASES) { ctx, form ->
            if (!ctx.user.role.writesPurchases) return@guardedPost call.forbidden(ctx, "Belege erfassen Kassier und Budenwart.")
            val id = uuidOrNull(call.parameters["id"]) ?: return@guardedPost call.respondRedirect("$BASE/einkauf")
            val outcome = try {
                web.purchases.markPaid(ctx.user, id, Payment.entries.firstOrNull { it.name == form["zahlung"] } ?: Payment.BANK, dateOrNull(form["am"]) ?: ctx.today)
                "hinweis=" + "Als bezahlt gebucht.".encodeURLParameter()
            } catch (e: AccountProblem) { "fehler=" + e.message.orEmpty().encodeURLParameter() }
            call.respondRedirect("$BASE/einkauf?b=$id&$outcome")
        }
    }
    post("/einkauf/{id}/lager") {
        call.guardedPost(web, Area.PURCHASES) { ctx, form ->
            if (!ctx.user.role.writesPurchases) return@guardedPost call.forbidden(ctx, "Belege erfassen Kassier und Budenwart.")
            val id = uuidOrNull(call.parameters["id"]) ?: return@guardedPost call.respondRedirect("$BASE/einkauf")
            val outcome = try {
                val item = uuidOrNull(form["artikel"]) ?: throw AccountProblem("Bitte einen Lagerartikel wählen.")
                val quantity = Money.parse(form["menge"].orEmpty()) ?: throw AccountProblem("Die Menge bitte als Zahl.")
                val cost = form["kosten"]?.takeIf { it.isNotBlank() }?.let { Money.parse(it) ?: throw AccountProblem("Die Kosten bitte als Zahl.") }
                web.purchases.addStockLine(ctx.user, id, item, uuidOrNull(form["gebinde"]), quantity, cost)
                "hinweis=" + "Wareneingang gebucht. Die Tablets sehen den Bestand beim nächsten Abgleich.".encodeURLParameter()
            } catch (e: AccountProblem) { "fehler=" + e.message.orEmpty().encodeURLParameter() }
            call.respondRedirect("$BASE/einkauf?b=${web.purchases.document(id)?.id ?: id}&$outcome")
        }
    }
    post("/einkauf/{id}/zeile") {
        call.guardedPost(web, Area.PURCHASES) { ctx, form ->
            if (!ctx.user.role.writesPurchases) return@guardedPost call.forbidden(ctx, "Belege erfassen Kassier und Budenwart.")
            val id = uuidOrNull(call.parameters["id"]) ?: return@guardedPost call.respondRedirect("$BASE/einkauf")
            val outcome = try {
                uuidOrNull(form["entfernen"])?.let { web.purchases.removeExpenseLine(ctx.user, it); "hinweis=" + "Zeile entfernt.".encodeURLParameter() }
                    ?: run {
                        val amount = Money.parse(form["betrag"].orEmpty()) ?: throw AccountProblem("Den Betrag bitte als Zahl.")
                        web.purchases.addExpenseLine(ctx.user, id, form["text"].orEmpty(), amount, uuidOrNull(form["konto"]) ?: throw AccountProblem("Bitte ein Konto wählen."))
                        "hinweis=" + "Zeile zugeordnet.".encodeURLParameter()
                    }
            } catch (e: AccountProblem) { "fehler=" + e.message.orEmpty().encodeURLParameter() }
            call.respondRedirect("$BASE/einkauf?b=$id&$outcome")
        }
    }
    post("/einkauf/lieferant/{id}") {
        call.guardedPost(web, Area.PURCHASES) { ctx, form ->
            if (!ctx.user.role.writesPurchases) return@guardedPost call.forbidden(ctx, "Lieferanten pflegen Kassier und Budenwart.")
            uuidOrNull(call.parameters["id"])?.let { web.purchases.updateSupplier(ctx.user, it, form["kontakt"].orEmpty(), form["kundennummer"].orEmpty()) }
            call.respondRedirect("$BASE/einkauf?hinweis=${"Lieferant gespeichert.".encodeURLParameter()}")
        }
    }
}

// ------------------------------------------------------------------- Seite

private fun HTML.purchasesPage(
    ctx: PageContext, all: List<Document>, shown: Document?, chosen: Boolean, fresh: Boolean, open: List<Document>,
    stockLines: List<StockLine2>, expenseLines: List<ExpenseLine>, suppliers: List<Supplier>, accounts: List<Account>, choices: List<StockChoice>,
    notice: String?, problem: String?,
) {
    val writes = ctx.user.role.writesPurchases
    val thisMonth = all.filter { java.time.YearMonth.from(it.date) == java.time.YearMonth.from(ctx.today) }
    val overdue = open.count { it.overdue(ctx.today) }

    shell(ctx, Area.PURCHASES, "Einkauf", "Eingangsbelege: Datei, Lieferant, Fälligkeit, Zahlung — und was davon ins Lager geht", actions = {
        if (writes) a(href = "$BASE/einkauf?neu=1", classes = "btn btn-primary") { icon("plus", "m"); +"Beleg erfassen" }
    }) {
        flash(notice, problem)
        panel {
            div("figures") {
                figure("Offene Belege") {
                    span("money-m${if (overdue > 0) " c-warning" else ""}") { +euro(open.sumOf { it.gross ?: 0.0 }) }
                    span("cap") { +"${count(open.size, "Beleg", "Belege")}${if (overdue > 0) " · $overdue überfällig" else open.firstOrNull { it.dueDate != null }?.let { " · nächster fällig ${ctx.dayShort(it.dueDate!!)}" }.orEmpty()}" }
                }
                figure("Einkauf diesen Monat") { span("money-m") { +euro(thisMonth.sumOf { it.gross ?: 0.0 }) }; span("cap") { +count(thisMonth.size, "Beleg", "Belege") } }
                figure("Ohne Belegdaten") {
                    val bare = all.count { !it.hasDocument }
                    span("money-m${if (bare > 0) " c-warning" else ""}") { +"$bare" }
                    span("cap") { +"Wareneingänge vom Tablet, zu denen Nummer und Fälligkeit fehlen" }
                }
                figure("Bar aus der Kasse") {
                    span("money-m") { +euro(thisMonth.filter { it.payment == Payment.CASH }.sumOf { it.gross ?: 0.0 }) }
                    span("cap") { +"diesen Monat · landet im Kassenbuch" }
                }
            }
        }
        div("cols cols-side split${if (chosen) " has-sel" else ""}") {
            div("split-list stack") {
                panel {
                    panelHead("Belege")
                    if (all.isEmpty()) p("empty") { +"Noch kein Beleg. Wareneingänge vom Tablet erscheinen hier von selbst; alles andere über „Beleg erfassen“." }
                    else table("t") {
                        thead { tr { th { +"Lieferant und Beleg" }; th(classes = "hide-sm") { +"Datum" }; th { +"Zahlung" }; th(classes = "num") { +"Brutto" } } }
                        tbody {
                            for (d in all) tr("pick") {
                                if (chosen && !fresh && d.id == shown?.id) attributes["aria-selected"] = "true"
                                td("fill") {
                                    div("row") {
                                        span("c-muted") { icon(if (d.fileKey != null) "filein" else "camera") }
                                        a(href = "$BASE/einkauf?b=${d.id}", classes = "cover two") {
                                            span("title-s") { +d.supplier.ifBlank { "Ohne Lieferant" } }
                                            span("cap") { +listOfNotNull(d.number.takeIf { it.isNotBlank() }, "${count(d.stockLines, "Lagerposition", "Lagerpositionen")}${if (d.expenseLines > 0) ", ${d.expenseLines} ohne" else ""}", if (!d.hasDocument) "Belegdaten fehlen" else null).joinToString(" · ") }
                                        }
                                    }
                                }
                                td("c-muted tnum nowrap hide-sm") { +ctx.dayShort(d.date) }
                                td { paymentChip(ctx, d) }
                                td("num") { span("money-s") { +(d.gross?.let(::euro) ?: "—") } }
                            }
                        }
                    }
                }
                if (suppliers.isNotEmpty()) panel {
                    panelHead("Lieferanten") { span("cap") { +"entstehen mit dem ersten Beleg" } }
                    table("t t-tight") {
                        tbody {
                            for (s in suppliers) tr {
                                td("fill") { twoLine(s.name, listOfNotNull(s.customerNumber.takeIf { it.isNotBlank() }?.let { "Kundennummer $it" }, s.contact.takeIf { it.isNotBlank() }).joinToString(" · ").ifEmpty { count(s.documents, "Beleg", "Belege") }) }
                                td("num") {
                                    if (writes) details {
                                        summary("btn") { +"Ändern" }
                                        postForm(ctx, "$BASE/einkauf/lieferant/${s.id}", "stack-tight confirm") {
                                            label("field") { span { +"Kundennummer" }; input(InputType.text, name = "kundennummer") { value = s.customerNumber } }
                                            label("field") { span { +"Kontakt (Telefon, E-Mail, Ansprechperson)" }; input(InputType.text, name = "kontakt") { value = s.contact } }
                                            button(type = ButtonType.submit, classes = "btn btn-primary") { +"Speichern" }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            div("split-detail stack") {
                a(href = "$BASE/einkauf", classes = "back") { icon("back", "m"); +"Alle Belege" }
                when {
                    fresh -> documentForm(ctx, null, suppliers)
                    shown == null -> panel { p("empty") { +"Noch kein Beleg." } }
                    else -> documentDetail(ctx, shown, stockLines, expenseLines, suppliers, accounts, choices, writes)
                }
            }
        }
    }
}

private fun FlowContent.paymentChip(ctx: PageContext, d: Document) = when {
    d.paidAt != null || d.payment != Payment.OPEN -> chip("bezahlt${d.paidAt?.let { " ${ctx.dayShort(it)}" }.orEmpty()}", "ok", "check")
    d.dueDate == null -> chip(if (d.hasDocument) "offen" else "ohne Belegdaten", "neutral")
    d.dueDate.isBefore(ctx.today) -> chip("überfällig seit ${ctx.dayShort(d.dueDate)}", "error", "alert")
    !d.dueDate.isAfter(ctx.today.plusDays(7)) -> chip("fällig ${ctx.dayShort(d.dueDate)}", "warn")
    else -> chip("fällig ${ctx.dayShort(d.dueDate)}", "neutral")
}

internal fun PageContext.dayShort(date: LocalDate): String =
    if (date.year == today.year) "%02d.%02d.".format(date.dayOfMonth, date.monthValue) else "%02d.%02d.%d".format(date.dayOfMonth, date.monthValue, date.year)

/** Kopfdaten eines Belegs — leer für einen neuen, sonst vorbelegt. Mit Datei, deshalb multipart. */
private fun FlowContent.documentForm(ctx: PageContext, d: Document?, suppliers: List<Supplier>) = panel {
    form(action = "$BASE/einkauf/beleg", method = FormMethod.post, encType = FormEncType.multipartFormData, classes = "panel-body") {
        csrf(ctx)
        d?.let { hiddenInput(name = "id") { value = it.id.toString() } }
        h2("title-m") { +(if (d == null) "Beleg erfassen" else if (d.hasDocument) "Belegdaten ändern" else "Belegdaten ergänzen") }
        if (d != null && !d.hasDocument) p("muted") { +"Der Wareneingang kam vom Tablet. Nummer, Fälligkeit und Zahlung kennt nur die Verwaltung." }
        div("form-grid") {
            label("field") {
                span { +"Lieferant" }
                input(InputType.text, name = "lieferant") { value = d?.supplier.orEmpty(); required = true; maxLength = "80"; list = "lieferanten" }
                dataList { attributes["id"] = "lieferanten"; for (s in suppliers) option { value = s.name } }
            }
            label("field") { span { +"Belegnummer" }; input(InputType.text, name = "nummer") { value = d?.number.orEmpty(); maxLength = "60" } }
            label("field") { span { +"Belegdatum" }; input(InputType.date, name = "datum") { value = (d?.date ?: ctx.today).toString(); required = true } }
            label("field") { span { +"Fällig am (leer: sofort oder bar)" }; input(InputType.date, name = "faellig") { value = d?.dueDate?.toString().orEmpty() } }
            label("field") { span { +"Brutto in Euro" }; input(InputType.text, name = "brutto") { value = d?.gross?.let(Money::formatPlain).orEmpty(); placeholder = "684,00"; attributes["inputmode"] = "decimal" } }
            label("field") { span { +"Enthaltene USt (nur zur Information)" }; input(InputType.text, name = "ust") { value = d?.vat?.takeIf { it != 0.0 }?.let(Money::formatPlain).orEmpty(); attributes["inputmode"] = "decimal" } }
            label("field") {
                span { +"Zahlung" }
                select { name = "zahlung"; for (p in Payment.entries) option { value = p.name; if (p == (d?.payment ?: Payment.OPEN)) selected = true; +p.label } }
            }
            label("field") { span { +"Bezahlt am (leer: am Belegdatum)" }; input(InputType.date, name = "bezahlt") { value = d?.paidAt?.toString().orEmpty() } }
        }
        label("field") { span { +"Notiz" }; input(InputType.text, name = "notiz") { value = d?.note.orEmpty(); maxLength = "500" } }
        label("field") {
            span { +(if (d?.fileKey != null) "Datei ersetzen (PDF oder Foto, bis 20 MB)" else "Datei (PDF oder Foto, bis 20 MB)") }
            input(InputType.file, name = "datei") { accept = "application/pdf,image/jpeg,image/png,image/webp,image/heic" }
        }
        div("row wrap") {
            button(type = ButtonType.submit, classes = "btn btn-primary") { +"Speichern" }
            if (d == null) a(href = "$BASE/einkauf", classes = "btn") { +"Abbrechen" }
        }
    }
}

private fun FlowContent.documentDetail(
    ctx: PageContext, d: Document, stockLines: List<StockLine2>, expenseLines: List<ExpenseLine>,
    suppliers: List<Supplier>, accounts: List<Account>, choices: List<StockChoice>, writes: Boolean,
) {
    panel {
        div("panel-body") {
            div("row-between") {
                div("two") {
                    h2("title-m") { +d.supplier.ifBlank { "Ohne Lieferant" } }
                    span("muted") { +listOfNotNull(d.number.takeIf { it.isNotBlank() }, "%02d.%02d.%d".format(d.date.dayOfMonth, d.date.monthValue, d.date.year)).joinToString(" · ") }
                }
                span("money-m") { +(d.gross?.let(::euro) ?: "—") }
            }
            div("row wrap") {
                paymentChip(ctx, d)
                if (d.vat > 0) chip("USt ${euro(d.vat)} enthalten")
            }
            d.fileKey?.let { key ->
                if (key.endsWith(".pdf")) a(href = "$BASE/einkauf/datei/$key", classes = "btn") { icon("filein", "m"); +"Rechnung als PDF öffnen" }
                else a(href = "$BASE/einkauf/datei/$key") { img(alt = "Belegdatei", src = "$BASE/einkauf/datei/$key", classes = "photo") }
            }
            d.photoKey?.let { key -> a(href = "$BASE/einkauf/datei/$key") { img(alt = "Belegfoto vom Tablet", src = "$BASE/einkauf/datei/$key", classes = "photo") } }
            if (d.fileKey == null && d.photoKey == null) div("photo-none") { icon("camera", "l"); span("cap") { +"Keine Datei zu diesem Beleg" } }
            d.note.takeIf { it.isNotBlank() }?.let { p("muted") { +it } }
            if (writes) div("row wrap") {
                if (d.open && d.hasDocument) details {
                    summary("btn btn-primary") { icon("check", "m"); +"Als bezahlt buchen" }
                    postForm(ctx, "$BASE/einkauf/${d.id}/bezahlt", "stack-tight confirm confirm-left") {
                        label("field") { span { +"Bezahlt per" }; select { name = "zahlung"; for (p in Payment.entries.filter { it != Payment.OPEN }) option { value = p.name; if (p == Payment.BANK) selected = true; +p.label } } }
                        label("field") { span { +"Am" }; input(InputType.date, name = "am") { value = ctx.today.toString() } }
                        button(type = ButtonType.submit, classes = "btn btn-primary") { +"Bezahlt" }
                    }
                }
                details {
                    summary("btn") { +(if (d.hasDocument) "Belegdaten ändern" else "Belegdaten ergänzen") }
                    div("confirm confirm-left confirm-wide") { documentForm(ctx, d, suppliers) }
                }
            }
        }
    }
    panel {
        div("panel-body") {
            h3("title-s") { +"Lagerpositionen — der Wareneingang, den die Tablets sehen" }
            if (stockLines.isEmpty()) p("cap") { +"Noch keine. Eine Position mit Lagerartikel erhöht dessen Bestand; die Produkte schöpfen daraus über ihre Rezeptur." }
            else table("t t-tight t-flush") {
                tbody {
                    for (line in stockLines) tr {
                        td("fill") { twoLine(line.item, "${Quantity.formatSigned(line.quantity)} ${line.unit}${if (line.source == "CORRECTION") " · Korrektur" else ""}") }
                        td("num") { span("money-s") { +(line.cost?.let(::euro) ?: "—") } }
                    }
                }
            }
            if (writes && choices.isNotEmpty()) postForm(ctx, "$BASE/einkauf/${d.id}/lager", "stack-tight") {
                div("form-grid") {
                    label("field") {
                        span { +"Lagerartikel" }
                        select { name = "artikel"; option { value = ""; +"— wählen —" }; for (c in choices) option { value = c.id.toString(); +"${c.name} (${c.unit})" } }
                    }
                    label("field") {
                        span { +"Gebinde (bei Fassware)" }
                        select {
                            name = "gebinde"
                            option { value = ""; +"Stück oder Einheit des Artikels" }
                            for (c in choices) for ((typeId, label) in c.containerTypes) option { value = typeId.toString(); +"${c.name}: $label" }
                        }
                    }
                    label("field") { span { +"Menge (Stück, Liter oder Gebinde)" }; input(InputType.text, name = "menge") { required = true; placeholder = "2"; attributes["inputmode"] = "decimal" } }
                    label("field") { span { +"Kosten dieser Position in Euro" }; input(InputType.text, name = "kosten") { placeholder = "284,00"; attributes["inputmode"] = "decimal" } }
                }
                div { button(type = ButtonType.submit, classes = "btn btn-brass") { icon("plus", "m"); +"Wareneingang buchen" } }
            } else if (writes) p("cap") { +"Lagerartikel entstehen am Tablet unter Lagerbestand." }
        }
    }
    panel {
        div("panel-body") {
            h3("title-s") { +"Zeilen ohne Lagerartikel — mit Konto" }
            if (expenseLines.isEmpty()) p("cap") { +"Fasspfand, Reinigung, Energie: was nicht ins Lager geht, bekommt hier sein Konto für die Bücher." }
            else table("t t-tight t-flush") {
                tbody {
                    for (line in expenseLines) tr {
                        td("fill") { twoLine(line.label, line.account) }
                        td("num") { span("money-s") { +euro(line.amount) } }
                        if (writes) td("num") {
                            postForm(ctx, "$BASE/einkauf/${d.id}/zeile") {
                                hiddenInput(name = "entfernen") { value = line.id.toString() }
                                button(type = ButtonType.submit, classes = "icon-btn") { attributes["aria-label"] = "${line.label} entfernen"; icon("close", "m") }
                            }
                        }
                    }
                }
            }
            if (writes && d.hasDocument) postForm(ctx, "$BASE/einkauf/${d.id}/zeile", "stack-tight") {
                div("form-grid") {
                    label("field") { span { +"Wofür" }; input(InputType.text, name = "text") { required = true; maxLength = "120"; placeholder = "5 × Fasspfand" } }
                    label("field") { span { +"Betrag in Euro" }; input(InputType.text, name = "betrag") { required = true; placeholder = "60,00"; attributes["inputmode"] = "decimal" } }
                    label("field") {
                        span { +"Konto" }
                        select { name = "konto"; for (a in accounts) option { value = a.id.toString(); +"${a.name} · ${areaName(a.area)}" } }
                    }
                }
                div { button(type = ButtonType.submit, classes = "btn") { icon("plus", "m"); +"Zeile zuordnen" } }
            } else if (writes) p("cap") { +"Erst die Belegdaten ergänzen, dann lassen sich Zeilen zuordnen." }
            val assigned = stockLines.sumOf { it.cost ?: 0.0 } + expenseLines.sumOf { it.amount }
            d.gross?.let { gross ->
                val rest = Money.cents(gross - assigned)
                p("cap") { +(if (kotlin.math.abs(rest) < 0.005) "Alles zugeordnet: Positionen und Zeilen ergeben den Bruttobetrag." else "Zugeordnet ${euro(assigned)} von ${euro(gross)} · ${if (rest > 0) "offen ${euro(rest)}" else "${euro(-rest)} zu viel"}") }
            }
        }
    }
}

internal fun areaName(area: String) = when (area) { "BUDE" -> "Budenbetrieb"; "VEREIN" -> "Vereinsleben"; else -> "Veranstaltungen" }
