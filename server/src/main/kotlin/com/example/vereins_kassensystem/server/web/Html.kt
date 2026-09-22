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
// Eigene Strichzeichnungen im 24er-Raster, dieselben wie im Entwurf.

object Icons {
    private val paths = mapOf(
        "mark" to """<rect x="3" y="3" width="18" height="18" rx="4.5"/><path d="M7.5 8v8M10.5 8v8M13.5 8v8M16.5 8v8M6 14.8l12-5.6"/>""",
        "grid" to """<rect x="4" y="4" width="7" height="7" rx="1.5"/><rect x="13" y="4" width="7" height="5" rx="1.5"/><rect x="13" y="11" width="7" height="9" rx="1.5"/><rect x="4" y="13" width="7" height="7" rx="1.5"/>""",
        "users" to """<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20c0-3.6 2.9-6 6.5-6s6.5 2.4 6.5 6"/><path d="M16 4.6a3.5 3.5 0 0 1 0 6.8"/><path d="M17.5 14.3c2.4.6 4 2.6 4 5.7"/>""",
        "book" to """<path d="M12 6c-2-1.5-5-2-8-1.5v13c3-.5 6 0 8 1.5 2-1.5 5-2 8-1.5v-13c-3-.5-6 0-8 1.5z"/><path d="M12 6v13"/>""",
        "tag" to """<path d="M3 12V4h8l9 9-8 8-9-9z"/><circle cx="7.5" cy="8.5" r="1.5"/>""",
        "box" to """<path d="M3.5 7.5 12 3l8.5 4.5v9L12 21l-8.5-4.5z"/><path d="M3.5 7.5 12 12l8.5-4.5M12 12v9"/>""",
        "filein" to """<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5M12 11v6M9.5 14.5 12 17l2.5-2.5"/>""",
        "tablet" to """<rect x="5" y="2.5" width="14" height="19" rx="2.5"/><path d="M11 18h2"/>""",
        "shield" to """<path d="M12 3 5 6v5c0 4.5 2.9 8.3 7 10 4.1-1.7 7-5.5 7-10V6z"/><circle cx="12" cy="10" r="2"/><path d="M8.8 16c.6-1.6 1.8-2.5 3.2-2.5s2.6.9 3.2 2.5"/>""",
        "history" to """<path d="M4 12a8 8 0 1 0 2.6-5.9"/><path d="M4 4v4.5h4.5M12 8v4.5l3 1.8"/>""",
        "gear" to """<circle cx="12" cy="12" r="3"/><path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1"/>""",
        "search" to """<circle cx="11" cy="11" r="6.5"/><path d="M16 16l4.5 4.5"/>""",
        "plus" to """<path d="M12 5v14M5 12h14"/>""",
        "check" to """<path d="M5 12.5l4.5 4.5L19 7.5"/>""",
        "alert" to """<path d="M12 4 2.8 20h18.4z"/><path d="M12 10v4.5M12 17.4v.1"/>""",
        "cloudoff" to """<path d="M7 18a4.5 4.5 0 0 1-.6-8.96A6 6 0 0 1 18 10.5a3.75 3.75 0 0 1-.5 7.5z"/><path d="M4 4l16 16"/>""",
        "lock" to """<rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>""",
        "chevron" to """<path d="M9 6l6 6-6 6"/>""",
        "back" to """<path d="M15 6l-6 6 6 6"/>""",
        "logout" to """<path d="M10 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h4M15 8l4 4-4 4M19 12H9"/>""",
        "keg" to """<path d="M7 3h10M7 21h10M6.5 3c-1.5 3-1.5 15 0 18M17.5 3c1.5 3 1.5 15 0 18M5.5 9h13M5.5 15h13"/>""",
        "camera" to """<rect x="3" y="6" width="18" height="14" rx="2"/><circle cx="12" cy="13" r="3.5"/><path d="M8 6l1.5-2.5h5L16 6"/>""",
        "close" to """<path d="M6 6l12 12M18 6L6 18"/>""",
        "cash" to """<rect x="3" y="6" width="18" height="12" rx="2"/><circle cx="12" cy="12" r="2.5"/><path d="M6.5 9.5v.01M17.5 14.5v.01"/>""",
        "receipt" to """<path d="M6 3h12v18l-3-2-3 2-3-2-3 2z"/><path d="M9 8h6M9 12h6"/>""",
        "mail" to """<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M3.5 7l8.5 6 8.5-6"/>""",
        "printer" to """<path d="M7 8V3h10v5M7 17H5a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"/><rect x="7" y="14" width="10" height="7"/>""",
        "bank" to """<path d="M3 9.5 12 4l9 5.5M5 10v8M9.5 10v8M14.5 10v8M19 10v8M3 20h18"/>""",
        "download" to """<path d="M12 4v12M7.5 11.5 12 16l4.5-4.5M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2"/>""",
        "more" to """<circle cx="5" cy="12" r="1.4"/><circle cx="12" cy="12" r="1.4"/><circle cx="19" cy="12" r="1.4"/>""",
    )

    fun svg(name: String, size: String = ""): String =
        """<svg class="ic${if (size.isEmpty()) "" else " ic-$size"}" viewBox="0 0 24 24" aria-hidden="true">${paths.getValue(name)}</svg>"""
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

fun HTML.document(pageTitle: String, content: FlowContent.() -> Unit) {
    lang = "de"
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1, viewport-fit=cover")
        meta(name = "robots", content = "noindex")
        title("$pageTitle · VereinsDeckel")
        link(rel = "stylesheet", href = "$BASE/assets/app.css")
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

fun FlowContent.twoLine(top: String, bottom: String) = span("two") {
    span("title-s") { +top }
    span("cap") { +bottom }
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
