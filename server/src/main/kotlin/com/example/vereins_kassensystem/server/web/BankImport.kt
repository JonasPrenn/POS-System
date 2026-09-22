package com.example.vereins_kassensystem.server.web

import com.example.vereins_kassensystem.server.devices.Tokens
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Kontoauszüge lesen (Konzept 4.2): CAMT.053 als XML, wie jede österreichische Bank ihn
 * anbietet, oder eine CSV, deren Spalten am Kopf erkannt werden. Was hier herauskommt, ist
 * eine Zeile je Umsatz — zugeordnet wird in [Statements.importBank].
 */
object BankImport {

    class Row(val date: LocalDate, val amount: Double, val counterparty: String, val reference: String) {
        /** Datum, Betrag, Text und Gegenseite: Wer denselben Auszug zweimal einliest, bekommt nichts doppelt. */
        val fingerprint: String get() = Tokens.sha256Hex("$date|${"%.2f".format(java.util.Locale.ROOT, amount)}|${reference.trim()}|${counterparty.trim()}")
    }

    fun parse(bytes: ByteArray, fileName: String): List<Row> {
        val head = String(bytes, 0, minOf(bytes.size, 400), Charsets.UTF_8).trimStart()
        return if (head.startsWith("<?xml") || head.startsWith("<Document") || fileName.endsWith(".xml", ignoreCase = true)) camt(bytes) else csv(bytes)
    }

    // -------------------------------------------------------------- CAMT.053

    private fun camt(bytes: ByteArray): List<Row> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Kein DTD, keine externen Entitäten: Ein Auszug ist Daten, kein Programm.
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val doc = try { factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)) } catch (e: Exception) { throw AccountProblem("Die Datei ist kein lesbares XML.") }
        val entries = doc.getElementsByTagNameNS("*", "Ntry")
        if (entries.length == 0) throw AccountProblem("Im XML stehen keine Umsätze (kein <Ntry>) — ist es ein CAMT.053-Auszug?")
        return (0 until entries.length).map { i ->
            val entry = entries.item(i) as Element
            val amount = entry.child("Amt")?.textContent?.trim()?.toDoubleOrNull() ?: throw AccountProblem("Ein Umsatz hat keinen Betrag.")
            val credit = entry.child("CdtDbtInd")?.textContent?.trim() == "CRDT"
            val date = (entry.child("BookgDt") ?: entry.child("ValDt"))?.let { it.child("Dt")?.textContent ?: it.child("DtTm")?.textContent?.take(10) }?.trim()
                ?.let { LocalDate.parse(it) } ?: throw AccountProblem("Ein Umsatz hat kein Buchungsdatum.")
            val details = entry.all("TxDtls").firstOrNull()
            val reference = listOfNotNull(
                details?.all("Ustrd")?.joinToString(" ") { it.textContent.trim() }?.takeIf { it.isNotBlank() },
                details?.all("EndToEndId")?.firstOrNull()?.textContent?.trim()?.takeIf { it.isNotBlank() && it != "NOTPROVIDED" },
                entry.child("AddtlNtryInf")?.textContent?.trim()?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            val party = details?.let { d -> (d.all(if (credit) "Dbtr" else "Cdtr").firstOrNull())?.child("Nm")?.textContent?.trim() }.orEmpty()
            Row(date, if (credit) amount else -amount, party, reference)
        }
    }

    private fun Element.child(name: String): Element? = all(name).firstOrNull()
    private fun Element.all(name: String): List<Element> {
        val list = getElementsByTagNameNS("*", name)
        return (0 until list.length).map { list.item(it) as Element }
    }

    // ------------------------------------------------------------------- CSV

    private val dateFormats = listOf("dd.MM.yyyy", "yyyy-MM-dd", "dd.MM.yy", "d.M.yyyy")

    private fun csv(bytes: ByteArray): List<Row> {
        val text = decode(bytes).removePrefix("﻿")
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.size < 2) throw AccountProblem("Die CSV hat keine Umsatzzeilen.")
        val separator = listOf(';', ',', '\t').maxByOrNull { ch -> lines[0].count { it == ch } } ?: ';'
        val header = split(lines[0], separator).map { it.lowercase().trim() }
        fun column(vararg needles: String): Int = header.indexOfFirst { h -> needles.any { h.contains(it) } }
        val date = column("buchungsdatum", "buchungstag", "valuta", "datum", "date")
        val amount = column("betrag", "amount", "umsatz")
        val reference = column("verwendungszweck", "zahlungsreferenz", "buchungstext", "umsatztext", "text", "reference", "beschreibung")
        val party = column("auftraggeber", "empfänger", "partner", "name", "zahlungspflichtiger", "gegenkonto", "iban")
        if (date < 0 || amount < 0) throw AccountProblem("In der Kopfzeile fehlen Datum oder Betrag. Erkannt: ${header.joinToString(", ")}")
        return lines.drop(1).mapNotNull { line ->
            val cells = split(line, separator)
            if (cells.size <= maxOf(date, amount)) return@mapNotNull null
            val day = parseDate(cells[date].trim()) ?: return@mapNotNull null
            val value = parseAmount(cells[amount]) ?: return@mapNotNull null
            Row(day, value, cells.getOrNull(party)?.trim().orEmpty(), cells.getOrNull(reference)?.trim().orEmpty())
        }.also { if (it.isEmpty()) throw AccountProblem("Keine Zeile ließ sich als Umsatz lesen — Datum oder Betrag haben ein unbekanntes Format.") }
    }

    private fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        // Ein Auszug aus Windows kommt oft als ISO-8859-1; das Ersatzzeichen verrät es.
        return if (utf8.contains('�')) String(bytes, Charsets.ISO_8859_1) else utf8
    }

    /** Trennt an [separator], außer innerhalb von Anführungszeichen. */
    private fun split(line: String, separator: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        for (ch in line) {
            when {
                ch == '"' -> quoted = !quoted
                ch == separator && !quoted -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(ch)
            }
        }
        out.add(sb.toString())
        return out
    }

    private fun parseDate(text: String): LocalDate? = dateFormats.firstNotNullOfOrNull { f -> runCatching { LocalDate.parse(text, DateTimeFormatter.ofPattern(f)) }.getOrNull() }

    private fun parseAmount(text: String): Double? {
        val cleaned = text.trim().replace("€", "").replace("EUR", "").replace(" ", "").replace(" ", "")
        if (cleaned.isEmpty()) return null
        val normalized = when {
            cleaned.contains(',') && cleaned.lastIndexOf(',') > cleaned.lastIndexOf('.') -> cleaned.replace(".", "").replace(',', '.')
            cleaned.contains(',') -> cleaned.replace(",", "")
            else -> cleaned
        }
        return normalized.toDoubleOrNull()
    }
}
