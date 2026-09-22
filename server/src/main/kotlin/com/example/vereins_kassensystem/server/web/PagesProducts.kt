package com.example.vereins_kassensystem.server.web

import io.ktor.http.encodeURLParameter
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.dataList
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.h2
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
import java.util.UUID

/** Sortiment und Mitgliederkategorien: Stammdaten, die die Tablets beim nächsten Abgleich übernehmen. */
internal fun Route.productPages(web: Web) {
    get("/sortiment") {
        call.guarded(web, Area.PRODUCTS) { ctx ->
            val products = web.products.list()
            val shown = uuidOrNull(call.request.queryParameters["p"])?.let { id -> products.firstOrNull { it.id == id } }
            call.html { productsPage(ctx, products, shown, web.products.stockItems(), call.request.queryParameters["hinweis"], call.request.queryParameters["fehler"]) }
        }
    }
    post("/sortiment") {
        call.guardedPost(web, Area.PRODUCTS) { ctx, form ->
            if (!ctx.user.role.writesProducts) return@guardedPost call.forbidden(ctx, "Das Sortiment pflegen Kassier und Budenwart.")
            val target = try {
                val id = web.products.create(ctx.user, form["name"].orEmpty(), form["preis"].orEmpty(), form["kategorie"].orEmpty(), form["groesse"].orEmpty())
                "p=$id&hinweis=" + "Angelegt. Die Tablets zeigen die Kachel nach dem nächsten Abgleich.".encodeURLParameter()
            } catch (e: AccountProblem) { "fehler=" + e.message.orEmpty().encodeURLParameter() }
            call.respondRedirect("$BASE/sortiment?$target")
        }
    }
    productPost(web, "/sortiment/{id}") { ctx, id, form -> web.products.update(ctx.user, id, form["name"].orEmpty(), form["preis"].orEmpty(), form["kategorie"].orEmpty(), form["groesse"].orEmpty()); "Gespeichert. Die Tablets übernehmen es beim nächsten Abgleich." }
    productPost(web, "/sortiment/{id}/entfernen", stay = false) { ctx, id, _ -> web.products.retire(ctx.user, id); "Aus dem Sortiment genommen. Die Buchungen behalten den Namen." }
    productPost(web, "/sortiment/{id}/variante") { ctx, id, form -> web.products.addVariant(ctx.user, id, form["name"].orEmpty(), form["preis"].orEmpty(), form["groesse"].orEmpty()); "Variante angelegt." }
    productPost(web, "/sortiment/{id}/variante/{vid}") { ctx, id, form ->
        val vid = uuidOrNull(call.parameters["vid"]) ?: throw AccountProblem("Diese Variante gibt es nicht mehr.")
        web.products.updateVariant(ctx.user, id, vid, form["name"].orEmpty(), form["preis"].orEmpty(), form["groesse"].orEmpty()); "Variante gespeichert."
    }
    productPost(web, "/sortiment/{id}/variante/{vid}/entfernen") { ctx, id, _ ->
        val vid = uuidOrNull(call.parameters["vid"]) ?: throw AccountProblem("Diese Variante gibt es nicht mehr.")
        web.products.removeVariant(ctx.user, id, vid); "Variante entfernt."
    }
    productPost(web, "/sortiment/{id}/rezeptur") { ctx, id, form ->
        val item = uuidOrNull(form["artikel"]) ?: throw AccountProblem("Bitte einen Lagerartikel wählen.")
        web.products.setComponent(ctx.user, id, item, form["menge"].orEmpty()); "Rezeptur gespeichert."
    }
    productPost(web, "/sortiment/{id}/rezeptur/{cid}/entfernen") { ctx, id, _ ->
        val cid = uuidOrNull(call.parameters["cid"]) ?: throw AccountProblem("Diese Zeile gibt es nicht mehr.")
        web.products.removeComponent(ctx.user, id, cid); "Zeile aus der Rezeptur entfernt."
    }

    get("/mitglieder/kategorien") {
        call.guarded(web, Area.MEMBERS) { ctx -> call.html { categoriesPage(ctx, web.products.categories(), call.request.queryParameters["hinweis"], call.request.queryParameters["fehler"]) } }
    }
    post("/mitglieder/kategorien") {
        call.guardedPost(web, Area.MEMBERS) { ctx, form ->
            if (!ctx.user.role.writesMembers) return@guardedPost call.forbidden(ctx, "Kategorien pflegt der Kassier.")
            val outcome = try { web.products.createCategory(ctx.user, form["name"].orEmpty(), form["limit"].orEmpty()); "hinweis=" + "Angelegt.".encodeURLParameter() } catch (e: AccountProblem) { "fehler=" + e.message.orEmpty().encodeURLParameter() }
            call.respondRedirect("$BASE/mitglieder/kategorien?$outcome")
        }
    }
    post("/mitglieder/kategorien/{id}") {
        call.guardedPost(web, Area.MEMBERS) { ctx, form ->
            if (!ctx.user.role.writesMembers) return@guardedPost call.forbidden(ctx, "Kategorien pflegt der Kassier.")
            val id = uuidOrNull(call.parameters["id"]) ?: return@guardedPost call.respondRedirect("$BASE/mitglieder/kategorien")
            val outcome = try {
                if (form["entfernen"] == "1") { web.products.removeCategory(ctx.user, id); "hinweis=" + "Entfernt.".encodeURLParameter() }
                else { web.products.updateCategory(ctx.user, id, form["name"].orEmpty(), form["limit"].orEmpty()); "hinweis=" + "Gespeichert. Die Tablets übernehmen das Limit beim nächsten Abgleich.".encodeURLParameter() }
            } catch (e: AccountProblem) { "fehler=" + e.message.orEmpty().encodeURLParameter() }
            call.respondRedirect("$BASE/mitglieder/kategorien?$outcome")
        }
    }
}

/** Ein Formular am Produkt: Rolle prüfen, ausführen, mit Hinweis oder Fehler zur Seite zurück — beim Produkt, außer es gibt es nicht mehr. */
private fun Route.productPost(web: Web, path: String, stay: Boolean = true, block: suspend io.ktor.server.routing.RoutingContext.(PageContext, UUID, io.ktor.http.Parameters) -> String) {
    post(path) {
        call.guardedPost(web, Area.PRODUCTS) { ctx, form ->
            if (!ctx.user.role.writesProducts) return@guardedPost call.forbidden(ctx, "Das Sortiment pflegen Kassier und Budenwart.")
            val id = uuidOrNull(call.parameters["id"]) ?: return@guardedPost call.respondRedirect("$BASE/sortiment")
            val outcome = try { "hinweis=" + block(ctx, id, form).encodeURLParameter() } catch (e: AccountProblem) { "fehler=" + e.message.orEmpty().encodeURLParameter() }
            call.respondRedirect(if (stay) "$BASE/sortiment?p=$id&$outcome" else "$BASE/sortiment?$outcome")
        }
    }
}

// ---------------------------------------------------------------- Sortiment

private fun HTML.productsPage(ctx: PageContext, products: List<ProductLine>, shown: ProductLine?, stockItems: List<StockItemOption>, notice: String?, problem: String?) {
    val writes = ctx.user.role.writesProducts
    val categories = products.map { it.category }.filter { it.isNotBlank() }.distinct()
    shell(ctx, Area.PRODUCTS, "Sortiment", "${count(products.size, "Produkt", "Produkte")} in ${count(categories.size, "Kategorie", "Kategorien")} · was die Kacheln der Tablets zeigen", actions = {
        if (writes) details {
            summary("btn btn-primary") { icon("plus", "m"); +"Produkt anlegen" }
            postForm(ctx, "$BASE/sortiment", "stack-tight confirm") { productFields(null, categories); button(type = ButtonType.submit, classes = "btn btn-primary") { +"Anlegen" } }
        }
    }) {
        flash(notice, problem)
        div("cols cols-side split${if (shown != null) " has-sel" else ""}") {
            panel("split-list") {
                if (products.isEmpty()) p("empty") { +"Noch kein Produkt. Es entsteht hier oder am Tablet und kommt mit dem Abgleich." }
                else table("t") {
                    thead { tr { th { +"Produkt" }; th(classes = "num") { +"Preis" }; th(classes = "hide-sm") { +"Rezeptur" }; th(classes = "num hide-sm") { +"90 Tage" } } }
                    tbody {
                        var lastCategory: String? = null
                        for (p in products) {
                            if (p.category != lastCategory) {
                                tr("group") { td { attributes["colspan"] = "4"; span("label-s") { +p.category.ifBlank { "Ohne Kategorie" } } } }
                                lastCategory = p.category
                            }
                            tr("pick") {
                                if (p.id == shown?.id) attributes["aria-selected"] = "true"
                                td("fill") { a(href = "$BASE/sortiment?p=${p.id}", classes = "cover two") { span("title-s") { +p.name }; span("cap") { +(if (p.hasVariants) p.variants.joinToString(" · ") { "${it.name} ${euro(it.price)}" } else "Ausschank ${plain(p.servingSize)}") } } }
                                td("num") { span("money-s") { +(if (p.hasVariants) "ab ${euro(p.variants.minOf { it.price })}" else euro(p.price)) } }
                                td("c-muted hide-sm") { +(p.components.joinToString(", ") { "${plain(it.quantityPerUnit)} ${it.unit} ${it.stockItem}" }.ifEmpty { "—" }) }
                                td("num c-muted tnum hide-sm") { +p.soldRecently.toString() }
                            }
                        }
                    }
                }
                div("panel-foot cap") { +"Preise gelten ab dem nächsten Abgleich der Tablets; was schon gebucht ist, behält seinen Preis." }
            }
            div("split-detail stack") {
                a(href = "$BASE/sortiment", classes = "back") { icon("back", "m"); +"Alle Produkte" }
                if (shown == null) panel { p("empty") { +"Links ein Produkt wählen." } }
                else productDetail(ctx, shown, categories, stockItems, writes)
            }
        }
    }
}

private fun FlowContent.productDetail(ctx: PageContext, p: ProductLine, categories: List<String>, stockItems: List<StockItemOption>, writes: Boolean) {
    panel {
        div("panel-body stack-tight") {
            div("row-between") {
                div("two") { h2("title-m") { +p.name }; span("cap") { +listOf(p.category.ifBlank { "Ohne Kategorie" }, "Ausschank ${plain(p.servingSize)}", "${p.soldRecently} verkauft in 90 Tagen").joinToString(" · ") } }
                span("money-l") { +(if (p.hasVariants) "ab ${euro(p.variants.minOf { it.price })}" else euro(p.price)) }
            }
            if (writes) div("row wrap") {
                details {
                    summary("btn") { +"Ändern" }
                    postForm(ctx, "$BASE/sortiment/${p.id}", "stack-tight confirm confirm-left") { productFields(p, categories); button(type = ButtonType.submit, classes = "btn btn-primary") { +"Speichern" } }
                }
                details {
                    summary("btn btn-quiet") { +"Aus dem Sortiment nehmen" }
                    postForm(ctx, "$BASE/sortiment/${p.id}/entfernen", "stack-tight confirm confirm-left") {
                        p("cap") { +"Die Kachel verschwindet von den Tablets. Was gebucht ist, bleibt mit Namen und Preis stehen." }
                        button(type = ButtonType.submit, classes = "btn btn-danger") { +"Ja, aus dem Sortiment nehmen" }
                    }
                }
            }
        }
    }
    panel {
        panelHead("Varianten") { span("cap") { +"Ein Produkt mit Varianten fragt an der Theke nach Größe oder Sorte; jede hat ihren Preis." } }
        if (p.variants.isEmpty()) p("empty") { +"Keine Varianten — die Kachel verkauft zum Grundpreis." }
        else table("t") {
            thead { tr { th { +"Variante" }; th(classes = "num") { +"Preis" }; th(classes = "hide-sm") { +"Ausschank" }; if (writes) th { +"" } } }
            tbody {
                for (v in p.variants) tr {
                    td("fill") { +v.name }
                    td("num") { span("money-s") { +euro(v.price) } }
                    td("c-muted hide-sm") { +(v.servingSize?.let(::plain) ?: "wie Produkt") }
                    if (writes) td("num") {
                        div("row wrap") {
                            details {
                                summary("btn btn-quiet") { +"Ändern" }
                                postForm(ctx, "$BASE/sortiment/${p.id}/variante/${v.id}", "stack-tight confirm") {
                                    label("field") { span { +"Name" }; input(InputType.text, name = "name") { value = v.name; required = true; maxLength = "80" } }
                                    priceField(v.price)
                                    sizeField(v.servingSize, "leer: wie das Produkt")
                                    button(type = ButtonType.submit, classes = "btn btn-primary") { +"Speichern" }
                                }
                            }
                            postForm(ctx, "$BASE/sortiment/${p.id}/variante/${v.id}/entfernen") { button(type = ButtonType.submit, classes = "btn btn-quiet") { attributes["aria-label"] = "Variante ${v.name} entfernen"; icon("close", "m") } }
                        }
                    }
                }
            }
        }
        if (writes) div("panel-foot") {
            details {
                summary("btn") { icon("plus", "m"); +"Variante anlegen" }
                postForm(ctx, "$BASE/sortiment/${p.id}/variante", "stack-tight confirm confirm-left") {
                    label("field") { span { +"Name (etwa 0,3 l oder Groß)" }; input(InputType.text, name = "name") { required = true; maxLength = "80" } }
                    priceField(null)
                    sizeField(null, "leer: wie das Produkt")
                    button(type = ButtonType.submit, classes = "btn btn-primary") { +"Anlegen" }
                }
            }
        }
    }
    panel {
        panelHead("Rezeptur") { span("cap") { +"Was ein Verkauf dem Lager entnimmt, je Einheit der Ausschankgröße. Ein Radler zieht vom Bierfass und vom Limonadenfass." } }
        if (p.components.isEmpty()) p("empty") { +"Keine Rezeptur — dieser Verkauf bewegt keinen Lagerbestand." }
        else table("t") {
            thead { tr { th { +"Lagerartikel" }; th(classes = "num") { +"Menge je Einheit" }; if (writes) th { +"" } } }
            tbody {
                for (cmp in p.components) tr {
                    td("fill") { +cmp.stockItem }
                    td("num tnum") { +"${plain(cmp.quantityPerUnit)} ${cmp.unit}" }
                    if (writes) td("num") { postForm(ctx, "$BASE/sortiment/${p.id}/rezeptur/${cmp.id}/entfernen") { button(type = ButtonType.submit, classes = "btn btn-quiet") { attributes["aria-label"] = "${cmp.stockItem} aus der Rezeptur entfernen"; icon("close", "m") } } }
                }
            }
        }
        if (writes) div("panel-foot") {
            if (stockItems.isEmpty()) p("cap") { +"Noch kein Lagerartikel — Artikel entstehen am Tablet unter Lagerbestand." }
            else details {
                summary("btn") { icon("plus", "m"); +"Artikel zuordnen" }
                postForm(ctx, "$BASE/sortiment/${p.id}/rezeptur", "stack-tight confirm confirm-left") {
                    label("field") { span { +"Lagerartikel" }; select { name = "artikel"; for (s in stockItems) option { value = s.id.toString(); +"${s.name} (${s.unit})" } } }
                    label("field") { span { +"Menge je Einheit (etwa 0,5 für einen halben Liter)" }; input(InputType.text, name = "menge") { required = true; placeholder = "0,5"; attributes["inputmode"] = "decimal" } }
                    button(type = ButtonType.submit, classes = "btn btn-primary") { +"Zuordnen" }
                }
            }
        }
    }
}

private fun FlowContent.productFields(p: ProductLine?, categories: List<String>) {
    label("field") { span { +"Name" }; input(InputType.text, name = "name") { value = p?.name.orEmpty(); required = true; maxLength = "80" } }
    priceField(p?.price)
    label("field") {
        span { +"Kategorie (die Reiter an der Theke)" }
        input(InputType.text, name = "kategorie") { value = p?.category.orEmpty(); maxLength = "40"; attributes["list"] = "kategorien" }
        dataList { attributes["id"] = "kategorien"; for (c in categories) option { value = c } }
    }
    sizeField(p?.servingSize, "Vorgabe 1 — in der Einheit der Rezeptur, etwa 0,5 für ein Halbes")
}

private fun FlowContent.priceField(price: Double?) = label("field") {
    span { +"Preis in Euro" }
    input(InputType.text, name = "preis") { value = price?.let(::plainMoney).orEmpty(); required = true; placeholder = "4,20"; attributes["inputmode"] = "decimal" }
}

private fun FlowContent.sizeField(size: Double?, hint: String) = label("field") {
    span { +"Ausschankgröße ($hint)" }
    input(InputType.text, name = "groesse") { value = size?.let(::plain).orEmpty(); attributes["inputmode"] = "decimal" }
}

// ------------------------------------------------------------- Kategorien

private fun HTML.categoriesPage(ctx: PageContext, categories: List<Products.CategoryLine>, notice: String?, problem: String?) {
    val writes = ctx.user.role.writesMembers
    shell(ctx, Area.MEMBERS, "Kategorien und Limits", "Fuchs, Bursch, Alter Herr, Gast — und wie weit jeder Deckel ins Minus darf", actions = {
        a(href = "$BASE/mitglieder", classes = "btn btn-quiet") { icon("back", "m"); +"Mitglieder" }
        if (writes) details {
            summary("btn btn-primary") { icon("plus", "m"); +"Kategorie anlegen" }
            postForm(ctx, "$BASE/mitglieder/kategorien", "stack-tight confirm") { categoryFields(null); button(type = ButtonType.submit, classes = "btn btn-primary") { +"Anlegen" } }
        }
    }) {
        flash(notice, problem)
        panel {
            if (categories.isEmpty()) p("empty") { +"Noch keine Kategorie. Ein Mitglied ohne Kategorie darf nicht ins Minus." }
            else table("t") {
                thead { tr { th { +"Kategorie" }; th(classes = "num") { +"Limit" }; th(classes = "num hide-sm") { +"Mitglieder" }; if (writes) th { +"" } } }
                tbody {
                    for (c in categories) tr {
                        td("fill") { +c.name }
                        td("num") { span("money-s${if (c.limit < 0) " c-secondary" else ""}") { +(if (c.limit < 0) euro(c.limit) else "kein Anschreiben") } }
                        td("num tnum c-muted hide-sm") { +c.members.toString() }
                        if (writes) td("num") {
                            div("row wrap") {
                                details {
                                    summary("btn btn-quiet") { +"Ändern" }
                                    postForm(ctx, "$BASE/mitglieder/kategorien/${c.id}", "stack-tight confirm") { categoryFields(c); button(type = ButtonType.submit, classes = "btn btn-primary") { +"Speichern" } }
                                }
                                if (c.members == 0) postForm(ctx, "$BASE/mitglieder/kategorien/${c.id}") {
                                    hiddenInput(name = "entfernen") { value = "1" }
                                    button(type = ButtonType.submit, classes = "btn btn-quiet") { attributes["aria-label"] = "Kategorie ${c.name} entfernen"; icon("close", "m") }
                                }
                            }
                        }
                    }
                }
            }
            div("panel-foot cap") { +"Das Limit ist die Grenze fürs Anschreiben an der Theke: −50,00 € heißt, der Deckel darf bis 50 Euro ins Minus. Es gilt auf den Tablets ab dem nächsten Abgleich." }
        }
    }
}

private fun FlowContent.categoryFields(c: Products.CategoryLine?) {
    label("field") { span { +"Name" }; input(InputType.text, name = "name") { value = c?.name.orEmpty(); required = true; maxLength = "80" } }
    label("field") { span { +"Limit (Minus oder 0)" }; input(InputType.text, name = "limit") { value = c?.limit?.let(::plainMoney).orEmpty(); placeholder = "−50"; attributes["inputmode"] = "decimal" } }
}

/** Zahl fürs Formular: 0,5 statt 0,5000, 1 statt 1,0 — mit Komma, wie man es eintippt. */
internal fun plain(value: Double): String {
    val text = if (value == Math.rint(value)) value.toLong().toString() else "%.3f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')
    return text.replace('.', ',')
}

private fun plainMoney(value: Double): String = "%.2f".format(java.util.Locale.ROOT, value).replace('.', ',').replace("-", "−")
