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
    // Die Grenze gilt bei Ktor auch fürs Suchen der nächsten Teilgrenze, also für die Datei — deshalb so groß wie die Datei sein darf, nicht so groß wie ein Feld.
    receiveMultipart(formFieldLimit = web.receipts.maxBytes.toLong() + 64 * 1024).forEachPart { part ->
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

/** Was nach dem Lesen einer Datei schon im Formular steht, mit der Datei, die bereits liegt. */
private class Prefill(val fileKey: String, val supplier: String, val number: String, val date: String, val dueDate: String, val gross: String, val vat: String)

/** Kein Fehler: Der Beleg ist noch nicht komplett, das Formular kommt vorbelegt zurück. */
private class Prefilled(val query: String) : RuntimeException()

private fun dateOrNull(text: String?): LocalDate? = text?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

internal fun Route.purchasePages(web: Web) {
    get("/einkauf") {
        call.guarded(web, Area.PURCHASES) { ctx ->
            val all = web.purchases.documents(80)
            val selected = uuidOrNull(call.request.queryParameters["b"])?.let { id -> all.firstOrNull { it.id == id } ?: web.purchases.document(id) }
            val shown = selected ?: all.firstOrNull()
            val fresh = call.request.queryParameters["neu"] == "1"
            val stockLines = shown?.let { web.purchases.stockLines(it.deliveryId) }.orEmpty()
            val expenseLines = shown?.takeIf { it.hasDocument }?.let { web.purchases.expenseLines(it.id) }.orEmpty()
            val choices = web.purchases.stockChoices()
            // Die Positionen aus der Datei: solange der Beleg keine hat, oder auf Wunsch noch einmal.
            val reading = shown?.takeIf { it.hasDocument && it.fileKey?.endsWith(".pdf") == true && (call.request.queryParameters["lesen"] == "1" || (stockLines.isEmpty() && expenseLines.isEmpty())) }
                ?.let { doc -> web.receipts.find(doc.fileKey!!)?.let { stored -> InvoiceReader.read(java.nio.file.Files.readAllBytes(stored.path), web.purchases.suppliers().map { it.name }, ctx.verein.name, ctx.today) } }
            val suggestions = reading?.let { web.purchases.suggest(it, shown!!, choices, web.purchases.containerSizes()) }
            val intake = web.intake.rows(30)
            val prefill = call.request.queryParameters.let { q -> if (fresh && q["datei"] != null) Prefill(q["datei"]!!, q["lieferant"].orEmpty(), q["nummer"].orEmpty(), q["datum"].orEmpty(), q["faellig"].orEmpty(), q["brutto"].orEmpty(), q["ust"].orEmpty()) else null }
            call.html {
                purchasesPage(
                    ctx, all, shown, selected != null || fresh, fresh, web.purchases.openDocuments(),
                    stockLines, expenseLines,
                    web.purchases.suppliers(), web.purchases.expenseAccounts(), choices,
                    call.request.queryParameters["hinweis"], call.request.queryParameters["fehler"], reading, suggestions, prefill, web.purchases.depositKinds(), shown?.takeIf { it.hasDocument }?.let { web.purchases.depositMovements(it.id) }.orEmpty(),
                    intake, call.request.queryParameters["post"] == "1" || intake.any { it.status == "NEW" }, web.intake.trustedSenders()
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
            var read: InvoiceReader.Extract? = null
            val outcome = try {
                val extension = up.bytes?.let { web.receipts.extensionFor(up.contentType) ?: throw AccountProblem("Als Datei gehen PDF, JPG, PNG, WEBP und HEIC.") }
                // Ein neuer Beleg mit PDF: erst lesen, dann speichern — was der Kassier eingetragen hat, gilt; Leeres füllt die Datei.
                if (id == null && extension == "pdf") read = InvoiceReader.read(up.bytes!!, web.purchases.suppliers().map { it.name }, ctx.verein.name, ctx.today)
                val fileKey = up.bytes?.let { web.receipts.store(it, extension!!) }
                    ?: up.fields["datei"]?.takeIf { it.isNotBlank() && web.receipts.find(it) != null && !web.purchases.fileKeyExists(it) }
                fun field(name: String, fromFile: String?): String = up.fields[name].orEmpty().ifBlank { fromFile.orEmpty() }
                val supplier = field("lieferant", read?.supplier); val date = dateOrNull(field("datum", read?.date?.toString()))
                if (read != null && (supplier.isBlank() || date == null)) {
                    // Nicht genug gelesen: zurück ins Formular, mit dem, was da ist, und der Datei schon gespeichert.
                    val q = listOf("datei" to fileKey.orEmpty(), "lieferant" to supplier, "nummer" to field("nummer", read.number), "datum" to (date?.toString() ?: ""), "faellig" to field("faellig", read.dueDate?.toString()), "brutto" to field("brutto", read.gross?.let(Money::formatPlain)), "ust" to field("ust", read.vat?.let(Money::formatPlain)))
                        .joinToString("&") { (k, v) -> "$k=" + v.encodeURLParameter() }
                    throw Prefilled("neu=1&$q&hinweis=" + (if (read.hasText) "Aus der Datei gelesen, aber ${if (supplier.isBlank()) "der Lieferant" else "das Datum"} fehlt — bitte ergänzen." else "Die Datei hat keine Textebene (Scan?). Bitte die Belegdaten eintragen.").encodeURLParameter())
                }
                val head = Purchases.Head(
                    supplier = supplier, number = field("nummer", read?.number),
                    date = date ?: throw AccountProblem("Das Belegdatum fehlt."),
                    dueDate = dateOrNull(field("faellig", read?.dueDate?.toString())),
                    gross = field("brutto", read?.gross?.let(Money::formatPlain)).takeIf { it.isNotBlank() }?.let { Money.parse(it) ?: throw AccountProblem("Den Bruttobetrag bitte als Zahl.") },
                    vat = field("ust", read?.vat?.let(Money::formatPlain)).takeIf { it.isNotBlank() }?.let { Money.parse(it) ?: throw AccountProblem("Die Umsatzsteuer bitte als Zahl.") } ?: 0.0,
                    payment = Payment.entries.firstOrNull { it.name == up.fields["zahlung"] } ?: Payment.OPEN,
                    paidAt = dateOrNull(up.fields["bezahlt"]), note = up.fields["notiz"].orEmpty(),
                )
                val saved = web.purchases.saveHead(ctx.user, id, head, fileKey)
                val note = when {
                    read == null -> "Beleg gespeichert."
                    !read.hasText -> "Beleg gespeichert. Die Datei hat keine Textebene (Scan?) — Positionen bitte von Hand."
                    read.lines.isEmpty() -> "Beleg gespeichert, Kopfdaten aus der Datei. Positionen hat der Leser keine erkannt — bitte von Hand."
                    else -> "Beleg gespeichert, aus der Datei gelesen: ${count(read.lines.size, "Position", "Positionen")} erkannt — unten prüfen und übernehmen."
                }
                "b=$saved&lesen=1&hinweis=" + note.encodeURLParameter()
            } catch (e: Prefilled) {
                e.query
            } catch (e: AccountProblem) {
                (if (id != null) "b=$id&" else "neu=1&") + "fehler=" + e.message.orEmpty().encodeURLParameter()
            }
            call.respondRedirect("$BASE/einkauf?$outcome")
        }
    }
    post("/einkauf/post/abrufen") {
        call.guardedPost(web, Area.PURCHASES) { ctx, _ ->
            if (!ctx.user.role.writesPurchases) return@guardedPost call.forbidden(ctx, "Den Posteingang holen Kassier und Budenwart ab.")
            val result = web.intake.poll()
            val outcome = result.error?.let { "fehler=" + it.encodeURLParameter() } ?: ("hinweis=" + "Abgerufen: ${count(result.fetched, "neue Mail", "neue Mails")}, ${count(result.recorded, "aufgenommen", "aufgenommen")}.".encodeURLParameter())
            call.respondRedirect("$BASE/einkauf?post=1&$outcome")
        }
    }
    post("/einkauf/post/{id}") {
        call.guardedPost(web, Area.PURCHASES) { ctx, form ->
            if (!ctx.user.role.writesPurchases) return@guardedPost call.forbidden(ctx, "Den Posteingang bearbeiten Kassier und Budenwart.")
            val id = uuidOrNull(call.parameters["id"]) ?: return@guardedPost call.respondRedirect("$BASE/einkauf?post=1")
            val outcome = try {
                when (form["aktion"]) {
                    "ablehnen" -> { web.intake.reject(ctx.user, id); "post=1&hinweis=" + "Abgelehnt. Die Datei bleibt beim Eintrag, es entsteht kein Beleg.".encodeURLParameter() }
                    "sperren" -> { web.intake.untrust(ctx.user, form["absender"].orEmpty()); "post=1&hinweis=" + "Absender gesperrt: Seine Mails warten künftig im Posteingang.".encodeURLParameter() }
                    else -> { val doc = web.intake.accept(ctx.user, id, trust = form["freigeben"] == "1"); "b=$doc&lesen=1&hinweis=" + "Beleg aus der Mail angelegt — ${web.intake.row(id)?.note.orEmpty()}".encodeURLParameter() }
                }
            } catch (e: AccountProblem) { "post=1&fehler=" + e.message.orEmpty().encodeURLParameter() }
            call.respondRedirect("$BASE/einkauf?$outcome")
        }
    }
    post("/einkauf/{id}/positionen") {
        call.guardedPost(web, Area.PURCHASES) { ctx, form ->
            if (!ctx.user.role.writesPurchases) return@guardedPost call.forbidden(ctx, "Positionen ordnen Kassier und Budenwart zu.")
            val id = uuidOrNull(call.parameters["id"]) ?: return@guardedPost call.respondRedirect("$BASE/einkauf")
            val outcome = try {
                val n = form["n"]?.toIntOrNull()?.coerceIn(0, 200) ?: 0
                val chosen = (0 until n).mapNotNull { i ->
                    val choice = form["wahl_$i"].orEmpty()
                    if (choice.isBlank()) return@mapNotNull null
                    val description = form["text_$i"].orEmpty().trim().take(120).ifEmpty { throw AccountProblem("Eine Zeile ohne Text.") }
                    val amount = Money.parse(form["betrag_$i"].orEmpty()) ?: throw AccountProblem("Den Betrag von „$description“ bitte als Zahl.")
                    val quantity = form["menge_$i"]?.takeIf { it.isNotBlank() }?.let { it.replace(',', '.').toDoubleOrNull() ?: throw AccountProblem("Die Menge von „$description“ bitte als Zahl.") }
                    val parts = choice.split(':')
                    Purchases.ChosenLine(
                        key = form["key_$i"].orEmpty().ifBlank { InvoiceReader.articleKey(description) }, description = description,
                        invoiceQuantity = form["orig_$i"]?.replace(',', '.')?.toDoubleOrNull(),
                        itemId = if (parts[0] == "item") uuidOrNull(parts.getOrNull(1)) else null,
                        containerTypeId = if (parts[0] == "item") uuidOrNull(parts.getOrNull(2)) else null,
                        accountId = if (parts[0] == "acct") uuidOrNull(parts.getOrNull(1)) else null,
                        quantity = quantity, amount = amount,
                        depositKindId = if (parts[0] == "pfand") uuidOrNull(parts.getOrNull(1)) else null,
                        newDepositName = if (choice == "pfand:neu") description else null,
                        delivered = form["gel_$i"]?.toIntOrNull(), returned = form["ret_$i"]?.toIntOrNull(),
                    )
                }
                val booked = web.purchases.applyLines(ctx.user, id, chosen)
                "hinweis=" + (if (booked == 0) "Nichts übernommen — jede Zeile stand auf „nicht übernehmen“." else "${count(booked, "Position", "Positionen")} übernommen. Beim nächsten Beleg dieses Lieferanten liegen sie von selbst richtig.").encodeURLParameter()
            } catch (e: AccountProblem) {
                "lesen=1&fehler=" + e.message.orEmpty().encodeURLParameter()
            }
            call.respondRedirect("$BASE/einkauf?b=$id&$outcome")
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
    notice: String?, problem: String?, reading: InvoiceReader.Extract? = null, suggestions: List<Purchases.Suggestion>? = null, prefill: Prefill? = null,
    depositKinds: List<Purchases.DepositKind> = emptyList(), deposits: List<Purchases.DepositMovement> = emptyList(),
    intake: List<IntakeRow> = emptyList(), showIntake: Boolean = false, senders: List<Pair<String, String>> = emptyList(),
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
                if (showIntake || intake.isNotEmpty()) intakePanel(ctx, intake, senders, writes)
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
                    fresh -> documentForm(ctx, null, suppliers, prefill)
                    shown == null -> panel { p("empty") { +"Noch kein Beleg." } }
                    else -> {
                        documentDetail(ctx, shown, stockLines, expenseLines, suppliers, accounts, choices, writes)
                        if (deposits.isNotEmpty()) panel {
                            div("panel-body") {
                                h3("title-s") { +"Pfand zu diesem Beleg" }
                                table("t t-tight t-flush") { tbody { for (m in deposits) tr { td("fill") { twoLine(m.kind, m.note) }; td("num tnum") { +listOfNotNull(m.delivered.takeIf { it > 0 }?.let { "+$it" }, m.returned.takeIf { it > 0 }?.let { "−$it" }).joinToString(" / ") } } } }
                                p("cap") { +"Geliefert und zurück je Gebinde; der Bestand steht unter Lager → Pfand und Leergut." }
                            }
                        }
                        if (reading != null && suggestions != null) readingPanel(ctx, shown, reading, suggestions, accounts, choices, depositKinds, writes)
                    }
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
private fun FlowContent.documentForm(ctx: PageContext, d: Document?, suppliers: List<Supplier>, prefill: Prefill? = null) = panel {
    form(action = "$BASE/einkauf/beleg", method = FormMethod.post, encType = FormEncType.multipartFormData, classes = "panel-body") {
        csrf(ctx)
        d?.let { hiddenInput(name = "id") { value = it.id.toString() } }
        prefill?.let { hiddenInput(name = "datei") { value = it.fileKey } }
        h2("title-m") { +(if (d == null) "Beleg erfassen" else if (d.hasDocument) "Belegdaten ändern" else "Belegdaten ergänzen") }
        if (d != null && !d.hasDocument) p("muted") { +"Der Wareneingang kam vom Tablet. Nummer, Fälligkeit und Zahlung kennt nur die Verwaltung." }
        div("form-grid") {
            label("field") {
                span { +"Lieferant" }
                input(InputType.text, name = "lieferant") { value = prefill?.supplier ?: d?.supplier.orEmpty(); required = true; maxLength = "80"; list = "lieferanten" }
                dataList { attributes["id"] = "lieferanten"; for (s in suppliers) option { value = s.name } }
            }
            label("field") { span { +"Belegnummer" }; input(InputType.text, name = "nummer") { value = prefill?.number ?: d?.number.orEmpty(); maxLength = "60" } }
            label("field") { span { +"Belegdatum" }; input(InputType.date, name = "datum") { value = prefill?.date?.ifBlank { null } ?: (d?.date ?: ctx.today).toString(); required = true } }
            label("field") { span { +"Fällig am (leer: sofort oder bar)" }; input(InputType.date, name = "faellig") { value = prefill?.dueDate ?: d?.dueDate?.toString().orEmpty() } }
            label("field") { span { +"Brutto in Euro" }; input(InputType.text, name = "brutto") { value = prefill?.gross ?: d?.gross?.let(Money::formatPlain).orEmpty(); placeholder = "684,00"; attributes["inputmode"] = "decimal" } }
            label("field") { span { +"Enthaltene USt (nur zur Information)" }; input(InputType.text, name = "ust") { value = prefill?.vat ?: d?.vat?.takeIf { it != 0.0 }?.let(Money::formatPlain).orEmpty(); attributes["inputmode"] = "decimal" } }
            label("field") {
                span { +"Zahlung" }
                select { name = "zahlung"; for (p in Payment.entries) option { value = p.name; if (p == (d?.payment ?: Payment.OPEN)) selected = true; +p.label } }
            }
            label("field") { span { +"Bezahlt am (leer: am Belegdatum)" }; input(InputType.date, name = "bezahlt") { value = d?.paidAt?.toString().orEmpty() } }
        }
        label("field") { span { +"Notiz" }; input(InputType.text, name = "notiz") { value = d?.note.orEmpty(); maxLength = "500" } }
        label("field") {
            span { +(if (d?.fileKey != null) "Datei ersetzen (PDF oder Foto, bis 20 MB)" else if (prefill != null) "Datei liegt schon — nur zum Ersetzen" else "Datei (PDF oder Foto, bis 20 MB) — aus einem PDF mit Textebene liest die Verwaltung Lieferant, Nummer, Datum, Betrag und Positionen") }
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

/** Der Posteingang: Mails mit PDF, die ein Beleg werden wollen — bekannte Absender sind es schon, unbekannte warten. */
private fun FlowContent.intakePanel(ctx: PageContext, rows: List<IntakeRow>, senders: List<Pair<String, String>>, writes: Boolean) = panel {
    val open = rows.filter { it.status == "NEW" }
    panelHead("Posteingang") {
        div("row wrap") {
            if (open.isNotEmpty()) chip("${count(open.size, "Mail wartet", "Mails warten")}", "warn")
            if (writes) postForm(ctx, "$BASE/einkauf/post/abrufen") { button(type = ButtonType.submit, classes = "btn btn-quiet") { icon("mail", "m"); +"Jetzt abrufen" } }
        }
    }
    div("panel-note cap") { +"${if (ctx.verein.imap.enabled) "Das Postfach wird alle 10 Minuten abgerufen" else "Der Abruf ist unter Einstellungen nicht eingeschaltet"}${ctx.verein.mailLastPoll?.let { " · zuletzt ${ctx.friendly(it)}" } ?: ""}${ctx.verein.mailLastError?.let { " · $it" } ?: ""}. Von freigegebenen Absendern (${senders.size}) wird jede Mail mit PDF gleich ein Beleg; die anderen warten hier." }
    if (rows.isEmpty()) p("empty") { +"Noch keine Mail eingegangen." }
    else table("t") {
        thead { tr { th { +"Mail" }; th(classes = "hide-sm") { +"Eingang" }; th { +"Stand" }; if (writes) th { +"" } } }
        tbody {
            for (r in rows) tr {
                td("fill") { twoLine(r.subject.ifBlank { "(ohne Betreff)" }, listOfNotNull(r.sender, r.fileName.takeIf { it.isNotBlank() }).joinToString(" · ")) }
                td("c-muted nowrap hide-sm") { +ctx.friendly(r.receivedAt) }
                td {
                    when (r.status) {
                        "NEW" -> chip("wartet", "warn")
                        "DONE" -> r.documentId?.let { a(href = "$BASE/einkauf?b=$it&lesen=1", classes = "link") { chip("Beleg", "ok", "check") } } ?: chip("Beleg", "ok")
                        "REJECTED" -> chip("abgelehnt")
                        "FAILED" -> chip("nicht angelegt", "error", "alert")
                        else -> chip("kein PDF")
                    }
                    if (r.note.isNotBlank()) span("cap") { +" ${r.note}" }
                }
                if (writes) td("num") {
                    if (r.status == "NEW") div("row wrap") {
                        r.fileKey?.let { a(href = "$BASE/einkauf/datei/$it", classes = "btn btn-quiet") { +"PDF" } }
                        postForm(ctx, "$BASE/einkauf/post/${r.id}", "row wrap") {
                            hiddenInput(name = "aktion") { value = "uebernehmen" }
                            label("check") { input(InputType.checkBox, name = "freigeben") { value = "1"; checked = true }; span { +"Absender freigeben" } }
                            button(type = ButtonType.submit, classes = "btn btn-brass") { +"Übernehmen" }
                        }
                        postForm(ctx, "$BASE/einkauf/post/${r.id}") { hiddenInput(name = "aktion") { value = "ablehnen" }; button(type = ButtonType.submit, classes = "btn btn-quiet") { +"Ablehnen" } }
                    }
                }
            }
        }
    }
    if (writes && senders.isNotEmpty()) div("panel-foot") {
        details {
            summary("btn btn-quiet") { +"Freigegebene Absender (${senders.size})" }
            div("stack-tight") {
                for ((address, supplier) in senders) div("row-between") {
                    twoLine(address, supplier.ifBlank { "Lieferant aus der Datei" })
                    postForm(ctx, "$BASE/einkauf/post/${java.util.UUID(0, 0)}") { hiddenInput(name = "aktion") { value = "sperren" }; hiddenInput(name = "absender") { value = address }; button(type = ButtonType.submit, classes = "btn btn-quiet") { +"Sperren" } }
                }
            }
        }
    }
}

/** Was der Leser in der Datei gefunden hat: je Zeile ein Vorschlag, den der Kassier bestätigt, ändert oder auslässt. */
private fun FlowContent.readingPanel(ctx: PageContext, d: Document, reading: InvoiceReader.Extract, suggestions: List<Purchases.Suggestion>, accounts: List<Account>, choices: List<StockChoice>, depositKinds: List<Purchases.DepositKind>, writes: Boolean) = panel {
    div("panel-body") {
        div("row-between") {
            h3("title-s") { +"Aus der Rechnung gelesen" }
            span("cap") { +(if (reading.hasText) "${count(reading.lines.size, "Position", "Positionen")} erkannt" else "keine Textebene") }
        }
        when {
            !reading.hasText -> p("cap") { +"Die Datei hat keine Textebene — ein Scan oder Foto. Positionen bitte oben von Hand erfassen." }
            reading.lines.isEmpty() -> p("cap") { +"Kopfdaten gelesen, aber keine Positionen erkannt: Der Leser sucht Zeilen, die mit einer Menge beginnen und mit einem Betrag enden. Positionen bitte von Hand." }
            !writes -> table("t t-tight t-flush") { tbody { for (sg in suggestions) tr { td("fill") { +sg.line.description }; td("num") { span("money-s") { +euro(sg.line.total) } } } } }
            else -> postForm(ctx, "$BASE/einkauf/${d.id}/positionen", "stack-tight") {
                val pending = suggestions.filter { !it.done }
                if (pending.size < suggestions.size) p("cap") { +"${count(suggestions.size - pending.size, "Zeile ist", "Zeilen sind")} schon übernommen und stehen hier nicht mehr." }
                hiddenInput(name = "n") { value = pending.size.toString() }
                p("cap") { +"Je Zeile: Lagerartikel (mit Gebinde) oder ein Konto für Zeilen ohne Lager — oder auslassen. Menge ist die Lagermenge: bei einer Kiste zu 20 Flaschen also 20 je Kiste; die Verwaltung merkt sich das Verhältnis für den nächsten Beleg.${if (reading.linesAreNet) " Die Rechnung weist die Zeilen netto aus; die Beträge hier sind brutto hochgerechnet." else ""}" }
                pending.forEachIndexed { i, sg ->
                    val isDeposit = sg.line.kind == InvoiceReader.Kind.DEPOSIT
                    val selected = sg.mapping?.let { m -> if (m.itemId != null) "item:${m.itemId}${m.containerTypeId?.let { ":$it" } ?: ""}" else if (m.depositKindId != null) "pfand:${m.depositKindId}" else m.accountId?.let { "acct:$it" } } ?: (if (isDeposit) "pfand:neu" else "")
                    div("sub stack-tight") {
                        hiddenInput(name = "key_$i") { value = sg.key }
                        hiddenInput(name = "orig_$i") { value = sg.line.quantity?.let(Money::formatPlain).orEmpty() }
                        sg.line.delivered?.let { hiddenInput(name = "gel_$i") { value = it.toString() } }
                        sg.line.returned?.let { hiddenInput(name = "ret_$i") { value = it.toString() } }
                        label("field") {
                            span { +listOfNotNull("Zeile ${i + 1}", if (isDeposit) "Pfand" else null, sg.line.article?.let { "Art. $it" }, if (isDeposit) "${sg.line.delivered} geliefert, ${sg.line.returned} zurück" else sg.line.quantity?.let { "${Money.formatPlain(it).removeSuffix(",00")} laut Rechnung" }, sg.line.unitPrice?.let { "à ${euro(it)}" }, sg.line.net?.let { "netto ${euro(it)}${reading.vatRate?.let { r -> " + ${Money.formatPlain(r).removeSuffix(",00")} % USt" } ?: ""}" }, if (sg.learned) "gemerkt vom letzten Beleg" else null).joinToString(" · ") }
                            input(InputType.text, name = "text_$i") { value = sg.line.description; maxLength = "120" }
                        }
                        div("form-grid") {
                            label("field") {
                                span { +"Wird zu" }
                                select {
                                    name = "wahl_$i"
                                    option { value = ""; if (selected.isEmpty()) this.selected = true; +"— nicht übernehmen —" }
                                    for (c in choices) {
                                        if (c.containerTypes.isEmpty()) option { value = "item:${c.id}"; if (selected == "item:${c.id}") this.selected = true; +"${c.name} (${c.unit})" }
                                        for ((typeId, label) in c.containerTypes) option { value = "item:${c.id}:$typeId"; if (selected == "item:${c.id}:$typeId") this.selected = true; +"${c.name}: $label" }
                                    }
                                    for (a in accounts) option { value = "acct:${a.id}"; if (selected == "acct:${a.id}") this.selected = true; +"Konto: ${a.name}" }
                                    for (k in depositKinds) option { value = "pfand:${k.id}"; if (selected == "pfand:${k.id}") this.selected = true; +"Pfand: ${k.name}${k.supplier.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}" }
                                    option { value = "pfand:neu"; if (selected == "pfand:neu") this.selected = true; +"Pfand: neues Gebinde „${sg.line.description.take(40)}“" }
                                }
                            }
                            label("field") { span { +(if (isDeposit) "Saldo (geliefert − zurück)" else "Lagermenge") }; input(InputType.text, name = "menge_$i") { value = sg.quantity?.let { Money.formatPlain(it).removeSuffix(",00") }.orEmpty(); attributes["inputmode"] = "decimal" } }
                            label("field") { span { +"Betrag in Euro" }; input(InputType.text, name = "betrag_$i") { value = Money.formatPlain(sg.line.total); attributes["inputmode"] = "decimal" } }
                        }
                    }
                }
                div("row wrap") {
                    button(type = ButtonType.submit, classes = "btn btn-brass") { icon("check", "m"); +"Positionen übernehmen" }
                    span("cap") { +"Gelesen: ${listOfNotNull(reading.supplier?.let { "Lieferant „$it“" }, reading.number?.let { "Nr. $it" }, reading.gross?.let { "brutto ${euro(it)}" }).joinToString(", ").ifEmpty { "nur die Zeilen" }}" }
                }
            }
        }
    }
}

internal fun areaName(area: String) = when (area) { "BUDE" -> "Budenbetrieb"; "VEREIN" -> "Vereinsleben"; else -> "Veranstaltungen" }
