package com.example.vereins_kassensystem.server.web

import com.example.vereins_kassensystem.ui.format.Money
import kotlinx.html.ButtonType
import kotlinx.html.FORM
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.TR
import kotlinx.html.Tag
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.hiddenInput
import kotlinx.html.lang
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.td
import kotlinx.html.title
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

const val BASE = "/verwaltung"

/** Was jede Seite weiß: wer angemeldet ist, wie der Verein heißt, welche Zeit gilt. */
class PageContext(val session: WebSession, val verein: VereinSettings.Values, val zone: ZoneId, val now: Instant) {
    val user get() = session.user
    val today: LocalDate get() = now.atZone(zone).toLocalDate()
}

// ------------------------------------------------------------------- Icons
// Dieselben Material-Pfade wie in der App (MaterialIcons, erzeugt aus VdIcons.kt) — gefüllt, wie
// dort. Nur das Vereinszeichen ist eine eigene Strichzeichnung.

object Icons {
    private const val MARK = """<rect x="3" y="3" width="18" height="18" rx="4.5" fill="none" stroke="currentColor" stroke-width="1.75"/><path d="M7.5 8v8M10.5 8v8M13.5 8v8M16.5 8v8M6 14.8l12-5.6" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"/>"""

    fun svg(name: String, size: String = ""): String {
        val body = if (name == "mark") MARK else "<path d=\"${MaterialIcons.paths.getValue(name)}\"/>"
        return """<svg class="ic${if (size.isEmpty()) "" else " ic-$size"}" viewBox="0 0 24 24" aria-hidden="true">$body</svg>"""
    }
}

/**
 * Fertiges Markup einsetzen — nur für SVG, das dieser Code selbst zusammensetzt. Alles, was
 * aus der Datenbank kommt, geht durch `+text` und wird dabei maskiert.
 */
fun Tag.raw(html: String) = consumer.onTagContentUnsafe { +html }

fun FlowContent.icon(name: String, size: String = "") = raw(Icons.svg(name, size))

// -------------------------------------------------------------- Navigation

class NavEntry(val area: Area, val label: String, val icon: String, val path: String)

val NAV: List<Pair<String?, List<NavEntry>>> = listOf(
    null to listOf(NavEntry(Area.OVERVIEW, "Übersicht", "grid", BASE)),
    "Mitglieder und Geld" to listOf(
        NavEntry(Area.MEMBERS, "Mitglieder", "users", "$BASE/mitglieder"),
        NavEntry(Area.STATEMENTS, "Abrechnung", "receipt", "$BASE/abrechnung"),
        NavEntry(Area.CASH, "Kasse", "cash", "$BASE/kasse"),
        NavEntry(Area.REPORTS, "Berichte", "book", "$BASE/berichte"),
        NavEntry(Area.BOOKS, "Bücher", "ledger", "$BASE/buecher"),
    ),
    "Ware" to listOf(
        NavEntry(Area.STOCK, "Lager", "box", "$BASE/lager"),
        NavEntry(Area.PRODUCTS, "Sortiment", "tag", "$BASE/sortiment"),
        NavEntry(Area.PURCHASES, "Einkauf", "filein", "$BASE/einkauf"),
    ),
    "System" to listOf(
        NavEntry(Area.DEVICES, "Geräte", "tablet", "$BASE/geraete"),
        NavEntry(Area.USERS, "Benutzer und Rollen", "shield", "$BASE/benutzer"),
        NavEntry(Area.AUDIT, "Protokoll", "history", "$BASE/protokoll"),
        NavEntry(Area.SETTINGS, "Einstellungen", "gear", "$BASE/einstellungen"),
    ),
)

/** Wohin jemand nach der Anmeldung kommt: der erste Bereich, den die Rolle darf. */
fun homeOf(role: Role): String = NAV.flatMap { it.second }.first { role.may(it.area) }.path

/** Die untere Leiste am Telefon: was man unterwegs wissen will. Der Rest liegt unter „Mehr". */
private val PHONE_TABS = listOf(Area.OVERVIEW, Area.MEMBERS, Area.REPORTS)

// ------------------------------------------------------------------ Gerüst

/** Das Stylesheet samt Kürzel aus seinem Inhalt: Nach einem Update holt jeder Browser das neue Blatt, statt eine Stunde lang das alte zu zeigen. */
object Stylesheet {
    val css: String = checkNotNull(Stylesheet::class.java.getResource("/web/app.css")) { "web/app.css fehlt im Klassenpfad" }.readText()
    val version: String = java.security.MessageDigest.getInstance("SHA-256").digest(css.toByteArray()).take(6).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

fun HTML.document(pageTitle: String, content: FlowContent.() -> Unit) {
    lang = "de"
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1, viewport-fit=cover")
        meta(name = "robots", content = "noindex")
        title("$pageTitle · VereinsDeckel")
        link(rel = "stylesheet", href = "$BASE/assets/app.css?v=${Stylesheet.version}")
        link(rel = "stylesheet", href = "$BASE/assets/verein.css")
        link(rel = "icon", href = "$BASE/assets/icon.svg", type = "image/svg+xml")
    }
    body { content() }
}

fun HTML.shell(
    ctx: PageContext,
    active: Area?,
    title: String,
    subtitle: String,
    actions: FlowContent.() -> Unit = {},
    content: FlowContent.() -> Unit,
) = document(title) {
    val role = ctx.user.role
    div("app") {
        nav("side") {
            attributes["aria-label"] = "Verwaltung"
            div("brand") {
                span("brand-mark") { icon("mark", "l") }
                div("brand-text") {
                    span("title-s") { +ctx.verein.name.ifBlank { "VereinsDeckel" } }
                    span("cap") { +"VereinsDeckel · Verwaltung" }
                }
            }
            for ((heading, entries) in NAV) {
                val allowed = entries.filter { role.may(it.area) }
                if (allowed.isEmpty()) continue
                div("nav-group") {
                    heading?.let { span("label-s") { +it } }
                    for (entry in allowed) {
                        a(href = entry.path, classes = "nav-item") {
                            if (entry.area == active) attributes["aria-current"] = "page"
                            icon(entry.icon)
                            span { +entry.label }
                        }
                    }
                }
            }
            div("grow")
            div("who") {
                span("avatar") { +ctx.user.initials }
                div("who-text") {
                    span("title-s") { +ctx.user.displayName }
                    span("cap") { +role.label }
                }
                postForm(ctx, "$BASE/abmelden") {
                    button(type = ButtonType.submit, classes = "icon-btn") {
                        attributes["aria-label"] = "Abmelden"
                        icon("logout")
                    }
                }
            }
        }
        main {
            div("page-head") {
                div {
                    h1("headline") { +title }
                    p("muted") { +subtitle }
                }
                div("actions") { actions() }
            }
            content()
        }
        nav("tabs") {
            attributes["aria-label"] = "Schnellzugriff"
            val entries = NAV.flatMap { it.second }.filter { it.area in PHONE_TABS && role.may(it.area) }
            for (entry in entries) {
                a(href = entry.path, classes = "tab") {
                    if (entry.area == active) attributes["aria-current"] = "page"
                    span("pip") { icon(entry.icon) }
                    span { +entry.label }
                }
            }
            a(href = "$BASE/mehr", classes = "tab") {
                if (active == null || active !in PHONE_TABS) attributes["aria-current"] = "page"
                span("pip") { icon("more") }
                span { +"Mehr" }
            }
        }
    }
}

// --------------------------------------------------------------- Bausteine

/**
 * Ein Dialog über der Seite, ohne Skript: der Auslöser ist ein Knopf mit `popovertarget`, der
 * Dialog ein Element mit `popover` — der Browser legt es zentriert in die oberste Ebene, dunkelt
 * dahinter ab, schließt mit Esc, mit dem Kreuz oder mit einem Klick daneben. Wo ein Browser das
 * nicht kennt, steht der Inhalt unter dem Knopf, wie früher. [key] muss auf der Seite eindeutig
 * sein; daraus wird die id.
 */
fun FlowContent.dialog(key: String, trigger: String, label: String, title: String = label, triggerIcon: String? = null, body: FlowContent.() -> Unit) {
    val id = "dlg-" + key.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    button(type = ButtonType.button, classes = trigger) {
        attributes["popovertarget"] = id
        triggerIcon?.let { icon(it, "m") }
        +label
    }
    div("dialog") {
        attributes["id"] = id
        attributes["popover"] = ""
        div("dialog-head") {
            if (title.isNotBlank()) h2("title-m") { +title } else span { }
            button(type = ButtonType.button, classes = "icon-btn") {
                attributes["popovertarget"] = id
                attributes["popovertargetaction"] = "hide"
                attributes["aria-label"] = "Schließen"
                icon("close")
            }
        }
        div("dialog-body") { body() }
    }
}

fun FlowContent.panel(extra: String = "", block: FlowContent.() -> Unit) = section("panel $extra".trim()) { block() }

fun FlowContent.panelHead(title: String, right: FlowContent.() -> Unit = {}) = div("panel-head") {
    h2("title-m") { +title }
    right()
}

fun FlowContent.chip(text: String, kind: String = "neutral", iconName: String? = null) = span("chip chip-$kind") {
    iconName?.let { icon(it, "s") }
    +text
}

fun FlowContent.more(text: String, href: String) = a(href = href, classes = "link") {
    +text
    icon("chevron", "s")
}

/** Zwei Zeilen, Titel und Unterzeile; [phoneOnly] steht am Telefon als dritte Zeile darunter — etwa ein Abzeichen, dessen Spalte dort nicht mehr Platz hat. */
fun FlowContent.twoLine(top: String, bottom: String, phoneOnly: (FlowContent.() -> Unit)? = null) = span("two") {
    span("title-s") { +top }
    span("cap") { +bottom }
    phoneOnly?.let { span("only-sm") { it() } }
}

fun FlowContent.figure(label: String, block: FlowContent.() -> Unit) = div("figure") {
    span("label-m") { +label }
    block()
}

/** Ein Balken aus Anteilen (0–100), als SVG — berechnete Breiten ohne Inline-Style. */
fun FlowContent.bar(parts: List<Pair<Double, String>>, label: String, thin: Boolean = false) {
    val total = parts.sumOf { it.first }
    val sb = StringBuilder("""<svg class="bar${if (thin) " bar-thin" else ""}" viewBox="0 0 100 8" preserveAspectRatio="none" role="img" aria-label="${label.escaped()}"><rect class="f-track" width="100" height="8"/>""")
    var x = 0.0
    for ((value, fill) in parts) {
        val w = if (total > 100) value / total * 100 else value
        if (w <= 0) continue
        sb.append("""<rect class="$fill" x="${x.svg()}" width="${w.svg()}" height="8"/>""")
        x += w
    }
    raw(sb.append("</svg>").toString())
}

fun FORM.csrf(ctx: PageContext) = hiddenInput(name = "_csrf") { value = ctx.session.csrf }

fun FlowContent.postForm(ctx: PageContext, action: String, extra: String = "", block: FORM.() -> Unit) =
    form(action = action, method = FormMethod.post, classes = extra.ifEmpty { null }) {
        csrf(ctx)
        block()
    }

/** Eine Betragszelle: rechtsbündig, Tabellenziffern. [cell] trägt etwa `hide-sm` für das Telefon. */
fun TR.money(amount: Double, voice: String = "tnum", cell: String = "num", signed: Boolean = false) =
    td(cell) { span(voice) { +(if (signed) euroSigned(amount) else euro(amount)) } }

// ------------------------------------------------------------------ Format

/**
 * Beträge wie in der App (`Money` aus `:core`), für den Satz im Browser nachgeschärft: ein
 * geschütztes Leerzeichen vor dem Eurozeichen und ein echtes Minus statt des Bindestrichs.
 */
fun euro(amount: Double): String = Money.format(amount).replace(' ', ' ').replace('-', '−')

fun euroSigned(amount: Double): String = Money.formatSigned(amount).replace(' ', ' ')

/** Österreich: Jänner, nicht Januar. */
val AT: Locale = Locale.forLanguageTag("de-AT")

private val DAY = DateTimeFormatter.ofPattern("dd.MM.", AT)
private val DAY_YEAR = DateTimeFormatter.ofPattern("dd.MM.yyyy", AT)
private val TIME = DateTimeFormatter.ofPattern("HH:mm", AT)
private val WEEKDAY = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", AT)

fun PageContext.day(at: Instant): String = at.atZone(zone).format(DAY)
fun PageContext.dayYear(at: Instant): String = at.atZone(zone).format(DAY_YEAR)
fun PageContext.time(at: Instant): String = at.atZone(zone).format(TIME)
fun PageContext.longDate(): String = now.atZone(zone).format(WEEKDAY)

/** „heute 21:14", „gestern", sonst das Datum — so, wie man es am Tresen sagen würde. */
fun PageContext.friendly(at: Instant?): String {
    if (at == null) return "—"
    val date = at.atZone(zone).toLocalDate()
    return when (date) {
        today -> "heute ${time(at)}"
        today.minusDays(1) -> "gestern ${time(at)}"
        else -> if (date.year == today.year) day(at) else dayYear(at)
    }
}

/** „vor 3 Minuten", „vor 2 Tagen" — für „zuletzt gesehen". */
fun PageContext.ago(at: Instant?): String {
    if (at == null) return "noch nie"
    val minutes = java.time.Duration.between(at, now).toMinutes()
    return when {
        minutes < 1 -> "gerade eben"
        minutes == 1L -> "vor 1 Minute"
        minutes < 60 -> "vor $minutes Minuten"
        minutes < 120 -> "vor 1 Stunde"
        minutes < 24 * 60 -> "vor ${minutes / 60} Stunden"
        minutes < 48 * 60 -> "gestern ${time(at)}"
        else -> "vor ${minutes / (24 * 60)} Tagen"
    }
}

/** „1 Mitglied“, „3 Mitglieder“. */
fun count(n: Int, one: String, many: String): String = "$n ${if (n == 1) one else many}"

fun String.escaped(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

fun Double.svg(): String = String.format(Locale.ROOT, "%.2f", this)

fun initialsOf(name: String): String =
    name.split(' ').filter { it.isNotBlank() && it.first().isLetter() && !it.endsWith('.') && it != "DI" }
        .let { parts -> listOfNotNull(parts.firstOrNull(), parts.drop(1).lastOrNull()) }
        .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
