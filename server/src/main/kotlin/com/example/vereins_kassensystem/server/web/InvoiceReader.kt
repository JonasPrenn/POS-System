package com.example.vereins_kassensystem.server.web

import com.example.vereins_kassensystem.ui.format.Money
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Liest eine Eingangsrechnung aus der Textebene eines PDF (Konzept 4.4): Lieferant, Nummer,
 * Datum, Fälligkeit, Brutto, Umsatzsteuer und die Positionen. Kein Modell, keine Cloud —
 * Muster, wie sie Brauereien und Getränkehändler in ihre Rechnungen schreiben, gebaut an
 * echten Belegen: die Position mit Artikelnummer voran und der Menge vor den Beträgen
 * („10020 gold spezial Fass (20 Liter) 6 56,20 -20% 44,96 4,80 49,76 298,56“) genauso wie die
 * einfache („2 Fass Helles 50 l 142,00 284,00“). Was der Leser nicht findet, bleibt leer und
 * der Kassier trägt es ein; was er findet, steht als Vorschlag im Formular und wird geprüft,
 * bevor es ein Beleg wird. Ein Scan ohne Textebene ergibt nichts — das sagt die Seite dann auch.
 */
object InvoiceReader {

    /**
     * Eine Position. [total] ist brutto, so wie der Beleg am Ende bezahlt wird; [net] steht
     * dabei, wenn die Rechnung die Zeilen netto ausweist und der Leser sie hochgerechnet hat.
     * [article] ist die Artikelnummer des Lieferanten, wenn die Zeile eine trägt — der
     * stabilste Schlüssel fürs Gedächtnis.
     */
    class Line(val article: String?, val description: String, val quantity: Double?, val unitPrice: Double?, val total: Double, val net: Double?, val raw: String) {
        val key: String get() = articleKey(listOfNotNull(article, description).joinToString(" "))
    }

    class Extract(
        val supplier: String?, val number: String?, val date: LocalDate?, val dueDate: LocalDate?,
        val gross: Double?, val vat: Double?, val vatRate: Double?, val lines: List<Line>, val hasText: Boolean,
    ) {
        val foundAnything: Boolean get() = supplier != null || number != null || date != null || gross != null || lines.isNotEmpty()
        val linesAreNet: Boolean get() = lines.any { it.net != null }
    }

    fun read(pdf: ByteArray, knownSuppliers: List<String>, ownName: String = "", today: LocalDate = LocalDate.now()): Extract {
        val text = runCatching { Loader.loadPDF(pdf).use { doc -> PDFTextStripper().apply { sortByPosition = true }.getText(doc) } }.getOrNull().orEmpty()
        return parse(text, knownSuppliers, ownName, today)
    }

    /** Getrennt vom PDF, damit sich die Muster ohne Datei prüfen lassen. [ownName] ist der Verein selbst — er steht als Empfänger drauf, nie als Lieferant. */
    fun parse(text: String, knownSuppliers: List<String>, ownName: String = "", today: LocalDate = LocalDate.now()): Extract {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return Extract(null, null, null, null, null, null, null, emptyList(), hasText = false)
        val joined = lines.joinToString("\n")

        val supplier = supplier(lines, joined, knownSuppliers, ownName)
        val number = NUMBER.find(joined)?.groupValues?.get(1)?.trim()?.takeIf { it.length in 2..40 }
        val date = labelled(joined, DATE_LABEL) ?: DATE.findAll(joined).mapNotNull { parseDate(it.value) }.firstOrNull { !it.isAfter(today.plusDays(1)) }
        val dueDate = labelled(joined, DUE_LABEL)
            ?: DAYS.find(joined)?.groupValues?.get(1)?.toIntOrNull()?.let { days -> date?.plusDays(days.toLong()) }

        // Die Schlusszeile: Endbetrag, Rechnungsbetrag, zu zahlen — der letzte Betrag darauf ist brutto. Steht
        // sie als „Netto USt Brutto“ da, ist die Mitte die Steuer.
        val finalLine = lines.lastOrNull { FINAL_LABEL.containsMatchIn(it) && AMOUNT.containsMatchIn(it) }
            ?: lines.lastOrNull { SOFT_LABEL.containsMatchIn(it) && AMOUNT.containsMatchIn(it) }
        val finalAmounts = finalLine?.let { AMOUNT.findAll(it).mapNotNull { m -> parseAmount(m.value) }.toList() }.orEmpty()
        val gross = finalAmounts.lastOrNull() ?: AMOUNT.findAll(joined).mapNotNull { parseAmount(it.value) }.maxOrNull()
        val vat = finalAmounts.takeIf { it.size >= 3 && near(it[it.size - 3] + it[it.size - 2], it.last()) }?.let { it[it.size - 2] }
            ?: VAT.findAll(joined).mapNotNull { parseAmount(it.groupValues[1]) }.takeIf { it.any() }?.sum()
        val vatRate = RATE.findAll(joined).map { it.groupValues[1].replace(',', '.').toDouble() }.filter { it in 1.0..30.0 }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

        val items = items(lines, gross)
        val extras = extras(lines)
        // Netto ausgewiesen? Dann ergibt erst die Summe mal Steuersatz den Endbetrag — und die Zeilen werden hochgerechnet.
        val itemsNet = items.sumOf { it.total }
        val extrasGross = extras.sumOf { if (it.net != null || vatRate == null) it.total else it.total * (1 + vatRate / 100) }
        val scaled = if (gross != null && vatRate != null && items.isNotEmpty() && itemsNet + extras.sumOf { it.total } < gross - 0.5 && near(itemsNet * (1 + vatRate / 100) + extrasGross, gross, tolerance = maxOf(1.0, gross * 0.01))) {
            items.map { Line(it.article, it.description, it.quantity, it.unitPrice?.let { u -> Money.cents(u * (1 + vatRate / 100)) }, Money.cents(it.total * (1 + vatRate / 100)), it.total, it.raw) } +
                extras.map { if (it.net != null) it else Line(it.article, it.description, it.quantity, null, Money.cents(it.total * (1 + vatRate / 100)), it.total, it.raw) }
        } else items + extras
        return Extract(supplier, number, date, dueDate?.takeIf { d -> date == null || !d.isBefore(date) }, gross, vat, vatRate, scaled, hasText = true)
    }

    /**
     * Der Lieferant: ein bekannter, wenn sein Name irgendwo steht — sonst die Firma mit Rechtsform
     * (GmbH, eGen, KG …) oder Branche (Brauerei, Getränke…), die nicht der Verein selbst ist; die
     * steht oft erst in der Fußzeile. Zuletzt die erste brauchbare Zeile oben, außer sie ist der Empfänger.
     */
    private fun supplier(lines: List<String>, joined: String, known: List<String>, ownName: String): String? {
        val own = ownName.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 }.map { it.lowercase() }
        fun isRecipient(line: String) = own.any { line.lowercase().contains(it) } || RECIPIENT.containsMatchIn(line)
        known.firstOrNull { it.length >= 3 && joined.contains(it, ignoreCase = true) }?.let { return it }
        lines.firstOrNull { COMPANY.containsMatchIn(it) && !isRecipient(it) }?.let { line ->
            return line.substringBefore(',').substringBefore(" | ").replace(Regex("^\\W+"), "").trim().take(80)
        }
        return lines.take(6).firstOrNull { it.length in 4..60 && !isRecipient(it) && !DATE.containsMatchIn(it) && !AMOUNT_ONLY.matches(it) && !it.startsWith("Rechnung", ignoreCase = true) }
    }

    /**
     * Die Positionen: bis zur ersten Schlusszeile (Warenwert, Netto, Endbetrag …) — was danach kommt,
     * sind Steuertabellen, Pfandabrechnung und auf einer Brauereirechnung die Lieferaufstellung, die
     * alles noch einmal aufzählt. Eine umbrochene Bezeichnung („Container (20“ / „Liter)“) wird
     * wieder zusammengesetzt.
     */
    private fun items(lines: List<String>, gross: Double?): List<Line> {
        val result = ArrayList<Line>()
        for ((index, line) in lines.withIndex()) {
            if (STOP.containsMatchIn(line) && result.isNotEmpty()) break
            if (STOP.containsMatchIn(line) && (SUM_WORDS.containsMatchIn(line) || FINAL_LABEL.containsMatchIn(line))) continue
            var item = item(line) ?: continue
            val next = lines.getOrNull(index + 1)
            if (next != null && !AMOUNT.containsMatchIn(next) && next.length <= 40 && (next.startsWith("(") || item.description.count { it == '(' } > item.description.count { it == ')' })) {
                item = Line(item.article, "${item.description} $next".replace(Regex("\\s+"), " "), item.quantity, item.unitPrice, item.total, item.net, item.raw)
            }
            if (item.total > 0 && item.total <= (gross ?: Double.MAX_VALUE) + 0.005) result += item
        }
        return result
    }

    /** Eine Zeile mit Beträgen am Ende: die Menge steht davor — als letzte Zahl vor den Beträgen oder ganz vorn. */
    private fun item(line: String): Line? {
        if (SUM_WORDS.containsMatchIn(line) || FINAL_LABEL.containsMatchIn(line)) return null
        val amounts = AMOUNT.findAll(line).toList()
        if (amounts.isEmpty() || !line.endsWith(amounts.last().value)) return null
        val total = parseAmount(amounts.last().value) ?: return null
        val unitPrice = if (amounts.size >= 2) parseAmount(amounts[amounts.size - 2].value) else null
        val head = line.substring(0, amounts.first().range.first).trim()
        val tokens = head.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        if (tokens.size < 2) return null
        var quantity: Double? = null
        var article: String? = null
        val first = tokens.first()
        val leading = first.takeIf { LEADING_COUNT.matches(it) }?.let { parseAmount(it.trimEnd('x', '×')) }
        if (leading != null && leading <= 500) {
            // Einfaches Layout: „2 Fass Helles 50 l 142,00 284,00“ — die Menge steht vorn.
            quantity = leading; tokens.removeAt(0)
        } else {
            // Brauereilayout: Artikelnummer vorn, die Menge ist die letzte Zahl vor den Beträgen („10020 gold spezial Fass (20 Liter) 6 …“).
            if (ARTICLE.matches(first)) { article = first; tokens.removeAt(0) }
            val trailing = tokens.lastOrNull()?.takeIf { COUNT.matches(it) }?.let { parseAmount(it) }
            if (trailing != null && tokens.size >= 2) { quantity = trailing; tokens.removeAt(tokens.size - 1) }
        }
        if (quantity == null) return null
        val description = tokens.joinToString(" ").trim(' ', '-', '·', ':')
        if (description.length < 3 || DATE.containsMatchIn(description) || description.all { !it.isLetter() }) return null
        if (kotlin.math.abs(quantity) > 10_000 || quantity == 0.0) return null
        return Line(article, description.take(120), quantity, unitPrice, total, null, line)
    }

    /** Was neben den Positionen noch Geld kostet und einen eigenen Block hat: der Gebindesaldo (Pfand), Fracht, Zustellung. */
    private fun extras(lines: List<String>): List<Line> = lines.mapNotNull { line ->
        val m = EXTRA.find(line) ?: return@mapNotNull null
        val amounts = AMOUNT.findAll(line).mapNotNull { parseAmount(it.value) }.toList()
        if (amounts.isEmpty() || !line.endsWith(AMOUNT.findAll(line).last().value)) return@mapNotNull null
        val total = amounts.last()
        if (total <= 0) return@mapNotNull null
        // „193,20 20,00% 38,64 231,84“: netto, Steuer, brutto — dann steht brutto schon da.
        val net = amounts.takeIf { it.size >= 3 && near(it[it.size - 3] + it[it.size - 2], total) }?.let { it[it.size - 3] }
        Line(null, m.groupValues[1].trim().take(120), null, null, total, net, line)
    }

    /** Ein Artikel als Schlüssel fürs Gedächtnis: klein, ohne doppelte Leerzeichen, ohne Satzzeichen am Rand. */
    fun articleKey(description: String): String = description.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().take(120)

    private fun near(a: Double, b: Double, tolerance: Double = 0.011) = kotlin.math.abs(a - b) <= tolerance

    private fun labelled(text: String, label: Regex): LocalDate? = label.findAll(text).mapNotNull { parseDate(it.groupValues[1]) }.firstOrNull()

    private fun parseDate(raw: String): LocalDate? {
        val t = raw.trim()
        for (f in DATE_FORMATS) {
            try { return LocalDate.parse(t, f) } catch (_: DateTimeParseException) { }
        }
        return null
    }

    /** „1.234,56“, „1234,56“, „1234.56“ — Tausenderpunkt und Dezimalkomma wie auf einer österreichischen Rechnung. */
    fun parseAmount(raw: String): Double? {
        var t = raw.trim().removeSuffix("€").removeSuffix("EUR").trim().replace("−", "-")
        t = if (t.contains(',') && t.contains('.')) t.replace(".", "").replace(',', '.') else t.replace(',', '.')
        return t.toDoubleOrNull()?.takeIf { it.isFinite() && kotlin.math.abs(it) < 10_000_000 }
    }

    private val DATE_FORMATS = listOf(DateTimeFormatter.ofPattern("d.M.yyyy"), DateTimeFormatter.ofPattern("d.M.yy"), DateTimeFormatter.ofPattern("yyyy-MM-dd"), DateTimeFormatter.ofPattern("d. MMMM yyyy", java.util.Locale.GERMAN))
    private const val D = "\\d{1,2}\\.\\s?\\d{1,2}\\.\\s?\\d{2,4}|\\d{4}-\\d{2}-\\d{2}"
    private val DATE = Regex("\\b(?:$D)\\b|\\b\\d{1,2}\\. (?:Jänner|Januar|Februar|März|April|Mai|Juni|Juli|August|September|Oktober|November|Dezember) \\d{4}\\b")
    private val DATE_LABEL = Regex("(?i)(?:Rechnungsdatum|Belegdatum|Datum)\\s*:?\\s*($D)")
    private val DUE_LABEL = Regex("(?i)(?:zahlbar[^\\n]{0,40}?bis(?:\\s+zum)?|fällig am|fällig bis|Fälligkeit|Zahlungsziel)\\s*:?\\s*($D)")
    private val DAYS = Regex("(?i)(?:innerhalb|binnen)\\s+(?:von\\s+)?(\\d{1,3})\\s+Tag")
    private val NUMBER = Regex("(?i)(?:Rechnungs?-?\\s?(?:nummer|nr\\.?)|Rechnung\\s+Nr\\.?|Beleg-?\\s?(?:nummer|nr\\.?)|Invoice\\s+(?:No\\.?|Number))\\s*:?\\s*([A-Za-z0-9][A-Za-z0-9\\-/._]*)")
    // Ein Betrag hat zwei Nachkommastellen hinter dem Komma; „15x0,50“, „20,00%“ und „10.12.2025“ sind keine.
    private val AMOUNT = Regex("(?<![\\d,.x×])-?\\d{1,3}(?:\\.\\d{3})*,\\d{2}\\b(?!\\s?%)|(?<![\\d,.x×])-?\\d+,\\d{2}\\b(?!\\s?%)")
    private val AMOUNT_ONLY = Regex("^-?[\\d.,]+\\s*(€|EUR)?$")
    private val FINAL_LABEL = Regex("(?i)\\b(?:Endbetrag|Rechnungsbetrag|Gesamtbetrag|Bruttobetrag|Gesamtsumme|Zu zahlen|Zahlbetrag|Rechnungssumme)\\b")
    private val SOFT_LABEL = Regex("(?i)\\b(?:Brutto|Gesamt|Summe|Total)\\b")
    private val VAT = Regex("(?i)(?:MwSt\\.?|USt\\.?|Umsatzsteuer|Mehrwertsteuer)[^\\d\\n]{0,30}(?:\\d{1,2}\\s?%[^\\d\\n]{0,20})?(-?\\d{1,3}(?:\\.\\d{3})*,\\d{2}|-?\\d+,\\d{2}|-?\\d+\\.\\d{2})")
    private val RATE = Regex("\\b(\\d{1,2}(?:,\\d{1,2})?)\\s?%")
    private val COUNT = Regex("^-?\\d{1,5}(?:[.,]\\d{1,3})?$")
    private val LEADING_COUNT = Regex("^\\d{1,5}(?:[.,]\\d{1,2})?(?:x|×)?$")
    private val ARTICLE = Regex("^[A-Za-z]?\\d{3,}(?:[.\\-/]\\d+)*$")
    private val SUM_WORDS = Regex("(?i)\\b(Summe|Gesamt|Brutto|Netto|MwSt|USt|Umsatzsteuer|Zwischensumme|Skonto|Übertrag|Rabatt gesamt|Zahlbar|Warenwert|Gebindewert|Steuersatz)\\b")
    private val STOP = Regex("(?i)\\b(Warenwert|Netto|Nettobetrag|Endbetrag|Gesamtbetrag|Rechnungsbetrag|Zu zahlen|Gesamtsumme|Summen|G\\s?E\\s?S\\s?A\\s?M\\s?T)\\b")
    private val EXTRA = Regex("(?i)^((?:Gebindesaldo|Pfandsaldo|Leergutsaldo|Pfand gesamt|Fracht|Zustellung|Transportkosten|Versandkosten)[^\\d\\n]{0,40})")
    private val COMPANY = Regex("\\b(?:GmbH|eGen|e\\.U\\.|KG|OG|AG|GesmbH|Ges\\.m\\.b\\.H\\.|Genossenschaft|Brauerei|Getränke\\w*|Metzgerei|Bäckerei|Handels\\w*)\\b")
    private val RECIPIENT = Regex("(?i)\\b(?:Kundenkennzeichen|Kundennummer|Kunden-Nr|zH|z\\.H\\.|Lieferadresse|Geliefert an|Rechnungsadresse)\\b")
}
