package com.example.vereins_kassensystem.server.web

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Liest eine Eingangsrechnung aus der Textebene eines PDF (Konzept 4.4): Lieferant, Nummer,
 * Datum, Fälligkeit, Brutto, Umsatzsteuer und die Positionen. Kein Modell, keine Cloud —
 * Muster, wie sie Getränkehändler und Metzgereien in ihre Rechnungen schreiben. Was der
 * Leser nicht findet, bleibt leer und der Kassier trägt es ein; was er findet, steht als
 * Vorschlag im Formular und wird geprüft, bevor es ein Beleg wird. Ein Scan ohne Textebene
 * ergibt nichts — das sagt die Seite dann auch.
 */
object InvoiceReader {

    class Line(val description: String, val quantity: Double?, val unitPrice: Double?, val total: Double, val raw: String)

    class Extract(
        val supplier: String?, val number: String?, val date: LocalDate?, val dueDate: LocalDate?,
        val gross: Double?, val vat: Double?, val lines: List<Line>, val hasText: Boolean,
    ) {
        val foundAnything: Boolean get() = supplier != null || number != null || date != null || gross != null || lines.isNotEmpty()
    }

    fun read(pdf: ByteArray, knownSuppliers: List<String>, today: LocalDate = LocalDate.now()): Extract {
        val text = runCatching { Loader.loadPDF(pdf).use { doc -> PDFTextStripper().apply { sortByPosition = true }.getText(doc) } }.getOrNull().orEmpty()
        return parse(text, knownSuppliers, today)
    }

    /** Getrennt vom PDF, damit sich die Muster ohne Datei prüfen lassen. */
    fun parse(text: String, knownSuppliers: List<String>, today: LocalDate = LocalDate.now()): Extract {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return Extract(null, null, null, null, null, null, emptyList(), hasText = false)
        val joined = lines.joinToString("\n")

        val supplier = knownSuppliers.firstOrNull { known -> known.length >= 3 && joined.contains(known, ignoreCase = true) }
            ?: lines.take(6).firstOrNull { it.length in 4..60 && !DATE.containsMatchIn(it) && !AMOUNT_ONLY.matches(it) && !it.startsWith("Rechnung", ignoreCase = true) }

        val number = NUMBER.find(joined)?.groupValues?.get(1)?.trim()?.takeIf { it.length in 2..40 }
        val date = labelled(joined, DATE_LABEL) ?: DATE.findAll(joined).mapNotNull { parseDate(it.value) }.firstOrNull { !it.isAfter(today.plusDays(1)) }
        val dueDate = labelled(joined, DUE_LABEL)
            ?: DAYS.find(joined)?.groupValues?.get(1)?.toIntOrNull()?.let { days -> date?.plusDays(days.toLong()) }
        val gross = GROSS.findAll(joined).mapNotNull { parseAmount(it.groupValues[1]) }.lastOrNull()
            ?: AMOUNT.findAll(joined).mapNotNull { parseAmount(it.value) }.maxOrNull()
        val vat = VAT.findAll(joined).mapNotNull { parseAmount(it.groupValues[1]) }.takeIf { it.any() }?.sum()

        val items = lines.mapNotNull { line -> item(line) }.filter { it.total > 0 && it.total <= (gross ?: Double.MAX_VALUE) + 0.005 }
        return Extract(supplier, number, date, dueDate?.takeIf { d -> date == null || !d.isBefore(date) }, gross, vat, items, hasText = true)
    }

    /**
     * Eine Position: beginnt mit einer Menge, endet mit einem Betrag, dazwischen der Artikel —
     * und davor meist der Einzelpreis. „2 Fass Helles 50 l 142,00 284,00“ ist eine, eine
     * Summenzeile oder ein Datum ist keine.
     */
    private fun item(line: String): Line? {
        if (SUM_WORDS.containsMatchIn(line)) return null
        val amounts = AMOUNT.findAll(line).toList()
        if (amounts.size < 2 || !line.endsWith(amounts.last().value)) return null
        val lead = QUANTITY.find(line) ?: return null
        val quantity = lead.groupValues[1].replace('.', ',').replace(',', '.').toDoubleOrNull() ?: return null
        val total = parseAmount(amounts.last().value) ?: return null
        val unitPrice = parseAmount(amounts[amounts.size - 2].value)
        var description = line.substring(lead.range.last + 1, amounts[amounts.size - 2].range.first).trim()
        if (description.length < 3) description = line.substring(lead.range.last + 1, amounts.last().range.first).trim()
        description = description.replace(Regex("\\s+"), " ").trim(' ', '-', '·', ':')
        if (description.length < 3 || DATE.containsMatchIn(description)) return null
        if (quantity <= 0 || quantity > 10_000) return null
        return Line(description.take(120), quantity, unitPrice, total, line)
    }

    /** Ein Artikel als Schlüssel fürs Gedächtnis: klein, ohne doppelte Leerzeichen, ohne Satzzeichen am Rand. */
    fun articleKey(description: String): String = description.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().take(120)

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
    private val DATE = Regex("\\b\\d{1,2}\\.\\s?\\d{1,2}\\.\\s?\\d{2,4}\\b|\\b\\d{4}-\\d{2}-\\d{2}\\b|\\b\\d{1,2}\\. (?:Jänner|Januar|Februar|März|April|Mai|Juni|Juli|August|September|Oktober|November|Dezember) \\d{4}\\b")
    private val DATE_LABEL = Regex("(?i)(?:Rechnungsdatum|Belegdatum|Datum)\\s*:?\\s*(\\d{1,2}\\.\\s?\\d{1,2}\\.\\s?\\d{2,4}|\\d{4}-\\d{2}-\\d{2})")
    private val DUE_LABEL = Regex("(?i)(?:zahlbar bis|fällig am|fällig bis|Fälligkeit|Zahlungsziel|Zahlbar bis zum)\\s*:?\\s*(\\d{1,2}\\.\\s?\\d{1,2}\\.\\s?\\d{2,4}|\\d{4}-\\d{2}-\\d{2})")
    private val DAYS = Regex("(?i)(?:innerhalb|binnen)\\s+(?:von\\s+)?(\\d{1,3})\\s+Tag")
    private val NUMBER = Regex("(?i)(?:Rechnungs?-?\\s?(?:nummer|nr\\.?)|Rechnung\\s+Nr\\.?|Beleg-?\\s?(?:nummer|nr\\.?)|Invoice\\s+(?:No\\.?|Number))\\s*:?\\s*([A-Za-z0-9][A-Za-z0-9\\-/._]*)")
    private val AMOUNT = Regex("-?\\d{1,3}(?:\\.\\d{3})*,\\d{2}\\b|-?\\d+,\\d{2}\\b|-?\\d+\\.\\d{2}\\b")
    private val AMOUNT_ONLY = Regex("^-?[\\d.,]+\\s*(€|EUR)?$")
    private val GROSS = Regex("(?i)(?:Rechnungsbetrag|Gesamtbetrag|Endbetrag|Bruttobetrag|Gesamt brutto|Gesamtsumme|Zu zahlen|Zahlbetrag|Brutto|Gesamt|Summe|Total)\\b[^\\d\\n-]{0,40}(-?\\d{1,3}(?:\\.\\d{3})*,\\d{2}|-?\\d+,\\d{2}|-?\\d+\\.\\d{2})")
    private val VAT = Regex("(?i)(?:MwSt\\.?|USt\\.?|Umsatzsteuer|Mehrwertsteuer)[^\\d\\n]{0,30}(?:\\d{1,2}\\s?%[^\\d\\n]{0,20})?(-?\\d{1,3}(?:\\.\\d{3})*,\\d{2}|-?\\d+,\\d{2}|-?\\d+\\.\\d{2})")
    // Nur die Zahl ist die Menge; „Fass“ oder „Kiste“ bleiben beim Artikel — das Wort sagt dem Kassier, was gezählt wurde.
    private val QUANTITY = Regex("^(\\d{1,5}(?:[.,]\\d{1,2})?)\\s*(?:x|×)?\\s+")
    private val SUM_WORDS = Regex("(?i)\\b(Summe|Gesamt|Brutto|Netto|MwSt|USt|Umsatzsteuer|Zwischensumme|Skonto|Übertrag|Rabatt gesamt|Zahlbar|Rechnungsbetrag)\\b")
}
