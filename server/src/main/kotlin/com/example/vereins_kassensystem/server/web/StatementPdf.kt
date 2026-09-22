package com.example.vereins_kassensystem.server.web

import com.example.vereins_kassensystem.ui.format.Money
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.Base64
import java.util.Locale
import javax.imageio.ImageIO

/** Alles, was auf einen Kontoauszug mit Zahlungsaufforderung gehört. */
class StatementDocument(
    val statement: Statement, val run: StatementRun, val lines: List<StatementLine>, val profile: Profile,
    val verein: VereinSettings.Values, val zone: java.time.ZoneId,
)

/**
 * Der Kontoauszug als PDF: eine A4-Seite je Abrechnung, aus HTML gesetzt. Kein Steuerausweis —
 * der Verein ist Kleinunternehmer, das Dokument ist ein Auszug mit Bitte um Zahlung. Zum
 * Bezahlen ein EPC-QR-Code („Zahlen mit Code"), den jede österreichische Banking-App liest.
 */
object StatementPdf {

    fun render(documents: List<StatementDocument>): ByteArray {
        val html = buildString {
            append("<!DOCTYPE html><html lang=\"de\"><head><meta charset=\"utf-8\"/><title>Kontoauszug</title><style>").append(CSS).append("</style></head><body>")
            documents.forEachIndexed { i, d -> append(page(d, last = i == documents.lastIndex)) }
            append("</body></html>")
        }
        val out = ByteArrayOutputStream()
        PdfRendererBuilder().useFastMode().withHtmlContent(html, null).toStream(out).run()
        return out.toByteArray()
    }

    /**
     * Der Inhalt des QR-Codes nach dem EPC-Standard (Version 002, ohne BIC): Wer ihn in der
     * Banking-App scannt, bekommt Empfänger, IBAN, Betrag und Verwendungszweck vorausgefüllt.
     */
    fun epcPayload(bank: BankAccount, amount: Double, reference: String): String = listOf(
        "BCD", "002", "1", "SCT", bank.bic, bank.holder.take(70), bank.iban, "EUR" + "%.2f".format(Locale.ROOT, amount), "", reference.take(35), "", ""
    ).joinToString("\n")

    private fun qrPng(payload: String, size: Int = 220): String {
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8"))
        val image = BufferedImage(size, size, BufferedImage.TYPE_BYTE_BINARY)
        for (x in 0 until size) for (y in 0 until size) image.setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xFFFFFF)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun page(d: StatementDocument, last: Boolean): String {
        val s = d.statement
        val fmt = { day: LocalDate -> "%02d.%02d.%d".format(day.dayOfMonth, day.monthValue, day.year) }
        val payable = s.amount > 0
        val sb = StringBuilder()
        sb.append("<div class=\"page").append(if (last) "" else " break").append("\">")
        sb.append("<div class=\"head\"><div><div class=\"club\">").append(esc(d.verein.name.ifBlank { "VereinsDeckel" })).append("</div>")
        d.verein.address.lines().filter { it.isNotBlank() }.forEach { sb.append("<div class=\"soft\">").append(esc(it)).append("</div>") }
        sb.append("</div><div class=\"meta\"><div>Nr. <b>").append(esc(s.number)).append("</b></div><div>").append(fmt(d.run.to)).append("</div></div></div>")
        sb.append("<div class=\"addr\"><div>").append(esc(s.memberName)).append("</div>")
        d.profile.address.lines().filter { it.isNotBlank() }.forEach { sb.append("<div class=\"soft\">").append(esc(it)).append("</div>") }
        if (d.profile.number.isNotBlank()) sb.append("<div class=\"soft\">Mitglied ").append(esc(d.profile.number)).append("</div>")
        sb.append("</div>")
        sb.append("<h1>").append(if (payable) "Kontoauszug mit Zahlungsaufforderung" else "Kontoauszug").append("</h1>")
        sb.append("<div class=\"soft sub\">Dein Deckel vom ").append(fmt(d.run.from)).append(" bis ").append(fmt(d.run.to)).append(" · ").append(esc(d.run.label)).append("</div>")

        sb.append("<table class=\"lines\"><tr class=\"sum\"><td colspan=\"2\">Stand am ").append(fmt(d.run.from.minusDays(1))).append("</td><td class=\"num\">").append(euro(s.opening)).append("</td><td class=\"num\"></td></tr>")
        if (d.lines.isEmpty()) sb.append("<tr><td colspan=\"4\" class=\"soft\">Keine Buchungen im Zeitraum.</td></tr>")
        for (line in d.lines) {
            val day = line.at.atZone(d.zone).toLocalDate()
            sb.append("<tr><td class=\"soft d\">").append("%02d.%02d.".format(day.dayOfMonth, day.monthValue)).append("</td><td>").append(esc(line.text)).append("</td><td class=\"num\">").append(euroSigned(line.effect)).append("</td><td class=\"num soft\">").append(euro(line.after)).append("</td></tr>")
        }
        sb.append("<tr class=\"sum strong\"><td colspan=\"2\">Stand am ").append(fmt(d.run.to)).append("</td><td class=\"num\">").append(euro(s.closing)).append("</td><td></td></tr>")
        if (s.extraAmount > 0) sb.append("<tr class=\"sum\"><td colspan=\"2\">").append(esc(s.extraLabel)).append("</td><td class=\"num\">").append(euro(s.extraAmount)).append("</td><td></td></tr>")
        sb.append("</table>")

        if (payable) {
            sb.append("<div class=\"pay\">")
            if (d.verein.bank.configured) sb.append("<img class=\"qr\" src=\"").append(qrPng(epcPayload(d.verein.bank, s.amount, s.number))).append("\" alt=\"QR-Code zum Bezahlen\"/>")
            // Der Betrag steht am Satzende: Nach dem Eurozeichen verschluckt die eingebaute Schrift das Leerzeichen.
            sb.append("<div><div class=\"big\">Bitte bis ").append(fmt(s.dueDate)).append(" überweisen: ").append(euro(s.amount)).append("</div>")
            if (d.verein.bank.configured) {
                sb.append("<div class=\"soft\">Zahlen mit Code: in der Banking-App scannen — oder überweisen an</div>")
                sb.append("<div class=\"mono\">").append(esc(d.verein.bank.holder)).append("<br/>").append(esc(d.verein.bank.iban.chunked(4).joinToString(" "))).append("<br/>Verwendungszweck <b>").append(esc(s.number)).append("</b></div>")
            } else sb.append("<div class=\"soft\">Bankverbindung: bitte beim Kassier erfragen. Verwendungszweck <b>").append(esc(s.number)).append("</b></div>")
            sb.append("</div></div>")
        } else {
            sb.append("<div class=\"pay\"><div class=\"big\">Nichts zu zahlen — der Deckel ist ").append(if (s.closing > 0) "im Plus" else "ausgeglichen").append(".</div></div>")
        }
        if (d.verein.statementText.isNotBlank()) sb.append("<div class=\"soft foot\">").append(esc(d.verein.statementText)).append("</div>")
        sb.append("<div class=\"soft foot\">Kein Steuerausweis: Der Verein stellt einen Kontoauszug aus, keine Rechnung im Sinn des UStG.</div>")
        sb.append("</div>")
        return sb.toString()
    }

    // Die eingebaute PDF-Schrift kennt weder das typografische Minus noch das geschützte Leerzeichen;
    // beides würde als „#“ gesetzt. Hier also Bindestrich und gewöhnliches Leerzeichen.
    private fun euro(v: Double) = Money.format(v)
    private fun euroSigned(v: Double) = Money.formatSigned(v).replace('\u2212', '-')
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private const val CSS = """
        @page { size: A4; margin: 18mm 18mm 16mm 20mm; }
        body { font-family: Helvetica, Arial, sans-serif; font-size: 10.5pt; color: #191D1A; line-height: 1.4; }
        .page { }
        .break { page-break-after: always; }
        .head { display: table; width: 100%; margin-bottom: 14mm; }
        .head > div { display: table-cell; vertical-align: top; }
        .meta { text-align: right; color: #414942; }
        .club { font-weight: bold; font-size: 12.5pt; }
        .soft { color: #414942; }
        .addr { margin-bottom: 10mm; font-size: 11pt; }
        h1 { font-size: 16pt; margin: 0 0 1mm 0; }
        .sub { margin-bottom: 6mm; }
        table.lines { width: 100%; border-collapse: collapse; margin-bottom: 8mm; }
        table.lines td { padding: 1.4mm 1mm; border-bottom: 0.2pt solid #C1C9C0; vertical-align: top; }
        table.lines tr.sum td { border-top: 0.6pt solid #191D1A; border-bottom: 0; }
        table.lines tr.strong td { font-weight: bold; font-size: 11.5pt; }
        td.num { text-align: right; white-space: nowrap; }
        td.d { white-space: nowrap; width: 14mm; }
        .pay { display: table; width: 100%; border: 0.5pt solid #C1C9C0; padding: 4mm; margin-bottom: 6mm; }
        .pay > * { display: table-cell; vertical-align: middle; }
        .qr { width: 32mm; height: 32mm; padding-right: 5mm; }
        .big { font-weight: bold; font-size: 13pt; margin-bottom: 1mm; white-space: nowrap; }
        .mono { font-family: Courier, monospace; font-size: 10pt; margin-top: 1.5mm; }
        .foot { font-size: 8.5pt; margin-top: 2mm; }
    """
}
