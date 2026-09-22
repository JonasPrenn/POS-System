package com.example.vereins_kassensystem.server.web

import com.example.vereins_kassensystem.ui.format.Money
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Die Mappe für die Rechnungsprüfer (Konzept 4.6, 2.3): ein PDF mit der Einnahmen-Ausgaben-
 * Rechnung, der Vermögensübersicht, dem Kassabuch des Jahres, der Belegliste und den
 * Abrechnungsläufen — alles aus denselben Abfragen wie die Seiten, nur untereinander und
 * auf Papier. Die Belegdateien selbst liegen in der Verwaltung unter Einkauf; die Liste
 * nennt Nummer und Datum, damit man sie dort findet.
 */
object AuditBundlePdf {

    class Input(
        val club: String, val year: YearBooks, val assets: Assets, val cashBook: List<CashBookEntry>,
        val documents: List<Document>, val runs: List<StatementRun>, val createdBy: String, val createdAt: LocalDate,
        val day: (java.time.Instant) -> String, val time: (java.time.Instant) -> String,
    )

    fun render(input: Input): ByteArray {
        val html = buildString {
            append("<!DOCTYPE html><html lang=\"de\"><head><meta charset=\"utf-8\"/><title>Prüfermappe</title><style>").append(CSS).append("</style></head><body>")
            append(cover(input))
            append(books(input))
            append(cashBook(input))
            append(documents(input))
            append(runs(input))
            append("</body></html>")
        }
        val out = ByteArrayOutputStream()
        PdfRendererBuilder().useFastMode().withHtmlContent(html, null).toStream(out).run()
        return out.toByteArray()
    }

    private fun cover(i: Input): String {
        val y = i.year.year
        return """
            <div class="cover">
              <div class="club">${esc(i.club)}</div>
              <h1>Prüfermappe ${y.label}</h1>
              <div class="sub">Rechnungsjahr ${d(y.from)} bis ${d(y.to.minusDays(1))}</div>
              <p class="soft">Erstellt am ${d(i.createdAt)} von ${esc(i.createdBy)} aus der Verwaltung von VereinsDeckel. Die Zahlen sind aus den Buchungen der Tablets und den Belegen der Verwaltung hergeleitet; nichts davon ist von Hand nachgetragen. Einnahme ist, was bar, mit Karte oder per Überweisung eingegangen ist; ein Verkauf auf den Deckel ist eine Forderung, bis er bezahlt ist. Ausgabe ist ein bezahlter Beleg zum Zahltag.</p>
              <table class="toc"><tr><td>1</td><td>Einnahmen-Ausgaben-Rechnung und Vermögensübersicht</td></tr><tr><td>2</td><td>Kassabuch</td></tr><tr><td>3</td><td>Belegliste</td></tr><tr><td>4</td><td>Abrechnungen an Mitglieder</td></tr></table>
            </div>
            <div class="break"></div>
        """.trimIndent()
    }

    private fun books(i: Input): String = buildString {
        val b = i.year
        append("<h1>1 · Einnahmen-Ausgaben-Rechnung ${b.year.label}</h1><div class=\"sub\">${d(b.year.from)} bis ${d(b.year.to.minusDays(1))}</div>")
        append("<table class=\"lines\"><tr class=\"head\"><td>Konto</td><td>Bereich</td><td class=\"num\">${b.year.label}</td><td class=\"num\">Vorjahr</td></tr>")
        for ((income, heading) in listOf(true to "Einnahmen", false to "Ausgaben")) {
            append("<tr class=\"group\"><td colspan=\"4\">$heading</td></tr>")
            for (l in b.lines.filter { it.income == income }) {
                append("<tr><td>${esc(l.name)} <span class=\"soft\">${esc(l.code)}</span></td><td>${esc(Books.AREAS[l.area] ?: l.area)}</td><td class=\"num\">${euro(l.amount)}</td><td class=\"num soft\">${euro(l.previous)}</td></tr>")
            }
            val sum = if (income) b.income else b.expense; val prev = if (income) b.incomePrevious else b.expensePrevious
            append("<tr class=\"sum\"><td colspan=\"2\">$heading gesamt</td><td class=\"num\">${euro(sum)}</td><td class=\"num soft\">${euro(prev)}</td></tr>")
        }
        val result = b.income - b.expense
        append("<tr class=\"sum strong\"><td colspan=\"2\">${if (result >= 0) "Überschuss" else "Abgang"}</td><td class=\"num\">${euro(result)}</td><td class=\"num soft\">${euro(b.incomePrevious - b.expensePrevious)}</td></tr></table>")
        append("<p class=\"soft\">Auf den Deckel geschrieben, ohne Zufluss: ${euro(b.onTab)} (Vorjahr ${euro(b.onTabPrevious)}).</p>")
        val a = i.assets
        append("<h2>Vermögensübersicht zum ${d(a.asOf)}</h2><table class=\"lines\">")
        append(row("Kassabestand", a.cash, a.cashDetail))
        append(row("Bankstand", a.bank, if (a.bank == null) "nicht eingetragen" else "laut Kontoauszug, vom Kassier eingetragen"))
        append(row("Lagerwert", a.stockValue, if (a.stockValue == null) "nicht rechenbar für diesen Stichtag" else "zu Einstandspreisen"))
        append(row("Pfand beim Lieferanten", a.deposits, "gehaltene Gebinde mal Pfand je Stück"))
        append(row("Forderungen", a.receivables, "Deckel im Minus"))
        append(row("Verbindlichkeiten: Guthaben", -a.memberCredits, "Deckel im Plus"))
        append(row("Verbindlichkeiten: offene Belege", -a.openInvoices, "Lieferantenbelege, nicht bezahlt"))
        append("<tr class=\"sum strong\"><td colspan=\"2\">Reinvermögen</td><td class=\"num\">${euro(a.total)}</td></tr></table>")
        append("<div class=\"break\"></div>")
    }

    private fun row(label: String, amount: Double?, note: String) = "<tr><td>${esc(label)}</td><td class=\"soft\">${esc(note)}</td><td class=\"num\">${amount?.let(::euroSigned) ?: "—"}</td></tr>"

    private fun cashBook(i: Input): String = buildString {
        append("<h1>2 · Kassabuch ${i.year.year.label}</h1>")
        if (i.cashBook.isEmpty()) append("<p class=\"soft\">Keine abgeschlossene Schicht im Rechnungsjahr.</p>")
        else {
            append("<table class=\"lines small\"><tr class=\"head\"><td>Zeit</td><td>Gerät</td><td>Bewegung</td><td class=\"num\">Betrag</td><td class=\"num\">Bestand</td></tr>")
            for (e in i.cashBook) {
                append("<tr><td class=\"d\">${i.day(e.at)} ${i.time(e.at)}</td><td>${esc(e.device)}</td><td>${esc(e.text)}${if (e.detail.isNotBlank()) " <span class=\"soft\">${esc(e.detail)}</span>" else ""}</td>")
                append("<td class=\"num\">${e.amount?.let(::euroSigned) ?: ""}</td><td class=\"num\">${e.balance?.let(::euro) ?: ""}</td></tr>")
            }
            append("</table>")
        }
        append("<div class=\"break\"></div>")
    }

    private fun documents(i: Input): String = buildString {
        append("<h1>3 · Belegliste ${i.year.year.label}</h1><p class=\"soft\">Die Dateien zu den Belegen liegen in der Verwaltung unter Einkauf, auffindbar über Nummer und Datum.</p>")
        if (i.documents.isEmpty()) append("<p class=\"soft\">Keine Belege im Rechnungsjahr.</p>")
        else {
            append("<table class=\"lines small\"><tr class=\"head\"><td>Datum</td><td>Lieferant</td><td>Nummer</td><td>Zahlung</td><td class=\"num\">Brutto</td></tr>")
            for (doc in i.documents) {
                val payment = if (doc.paidAt != null) "${doc.payment.label}, ${d(doc.paidAt)}" else doc.payment.label
                append("<tr><td class=\"d\">${d(doc.date)}</td><td>${esc(doc.supplier.ifBlank { "—" })}</td><td>${esc(doc.number.ifBlank { "—" })}${if (doc.fileKey != null || doc.photoKey != null) " <span class=\"soft\">(Datei)</span>" else ""}</td><td>${esc(payment)}</td><td class=\"num\">${doc.gross?.let(::euro) ?: "—"}</td></tr>")
            }
            append("<tr class=\"sum\"><td colspan=\"4\">${i.documents.size} Belege</td><td class=\"num\">${euro(i.documents.sumOf { it.gross ?: 0.0 })}</td></tr></table>")
        }
        append("<div class=\"break\"></div>")
    }

    private fun runs(i: Input): String = buildString {
        append("<h1>4 · Abrechnungen an Mitglieder ${i.year.year.label}</h1>")
        if (i.runs.isEmpty()) append("<p class=\"soft\">Kein Abrechnungslauf im Rechnungsjahr.</p>")
        else {
            append("<table class=\"lines small\"><tr class=\"head\"><td>Lauf</td><td>Zeitraum</td><td class=\"num\">Abrechnungen</td><td class=\"num\">Bezahlt</td><td class=\"num\">Summe</td><td class=\"num\">Eingegangen</td></tr>")
            for (r in i.runs) append("<tr><td>${esc(r.label)}</td><td>${d(r.from)} – ${d(r.to)}</td><td class=\"num\">${r.count}</td><td class=\"num\">${r.paid}</td><td class=\"num\">${euro(r.amount)}</td><td class=\"num\">${euro(r.paidAmount)}</td></tr>")
            append("</table>")
        }
    }

    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private fun d(day: LocalDate) = day.format(DAY)
    // Die eingebaute Schrift kennt weder das geschützte Leerzeichen noch das echte Minus.
    private fun euro(v: Double) = Money.format(v)
    private fun euroSigned(v: Double) = Money.formatSigned(v).replace('−', '-')
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private const val CSS = """
        @page { size: A4; margin: 18mm 18mm 16mm 20mm; }
        body { font-family: Helvetica, Arial, sans-serif; font-size: 10pt; color: #191D1A; line-height: 1.4; }
        .break { page-break-after: always; }
        .cover { margin-top: 40mm; }
        .club { font-weight: bold; font-size: 13pt; margin-bottom: 4mm; }
        h1 { font-size: 16pt; margin: 0 0 1mm 0; }
        h2 { font-size: 12.5pt; margin: 8mm 0 2mm 0; }
        .sub { margin-bottom: 6mm; color: #414942; }
        .soft { color: #414942; }
        table.toc { margin-top: 10mm; border-collapse: collapse; }
        table.toc td { padding: 1mm 4mm 1mm 0; }
        table.lines { width: 100%; border-collapse: collapse; margin-bottom: 6mm; }
        table.lines td { padding: 1.3mm 1mm; border-bottom: 0.2pt solid #C1C9C0; vertical-align: top; }
        table.lines.small td { font-size: 9pt; padding: 1mm 1mm; }
        table.lines tr.head td { font-weight: bold; border-bottom: 0.6pt solid #191D1A; }
        table.lines tr.group td { padding-top: 3mm; font-weight: bold; color: #414942; text-transform: uppercase; font-size: 8.5pt; border-bottom: 0; }
        table.lines tr.sum td { border-top: 0.6pt solid #191D1A; border-bottom: 0; font-weight: bold; }
        table.lines tr.strong td { font-size: 11.5pt; }
        td.num { text-align: right; white-space: nowrap; }
        td.d { white-space: nowrap; }
    """
}
