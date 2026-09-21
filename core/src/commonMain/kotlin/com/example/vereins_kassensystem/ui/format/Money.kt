package com.example.vereins_kassensystem.ui.format

import kotlin.math.abs

/**
 * Währungsformatierung, an einer Stelle.
 *
 * Vorher baute jeder Bildschirm sie sich selbst aus `String.format("%.2f") + " €"`, was
 * die Gerätesprache ignoriert und mit der deutschen Datumsformatierung ein paar Zeilen
 * weiter uneins war. Ein Verein, der diese App betreibt, rechnet in Euro und liest
 * deutsch, also ist das Format festgelegt statt dem Gerät überlassen: 1234.5 wird zu
 * "1.234,50 €".
 *
 * Seit der Umstellung auf zwei Plattformen kommt ein zweiter Grund dazu — `NumberFormat`
 * gibt es auf iOS nicht. Gerechnet wird darum in [Decimals], und zwar für beide
 * Plattformen gleich. Ein Betrag sieht auf dem iPad aus wie auf dem Android-Tablet, was
 * beim Abgleich zweier Kassen keine Kleinigkeit ist.
 */
object Money {

    /** "1.234,50 €" — die Standardform für alles, was der Nutzer als Betrag liest. */
    fun format(amount: Double): String =
        Decimals.grouped(Decimals.fixed(amount, 2)) + " €"

    /** "+5,00 €" / "−12,50 €", für Saldenbewegungen, bei denen die Richtung der Punkt ist. */
    fun formatSigned(amount: Double): String {
        val body = format(abs(amount))
        return when {
            amount > 0.0 -> "+$body"
            amount < 0.0 -> "−$body" // echtes Minuszeichen, kein Bindestrich
            else -> body
        }
    }

    /**
     * Reine Dezimalzahl ohne Währungszeichen, für Eingabefelder und CSV, wo das Zeichen
     * gleich wieder entfernt werden müsste.
     */
    fun formatPlain(amount: Double): String = Decimals.fixed(amount, 2)

    /**
     * Liest, was jemand in ein Betragsfeld getippt hat, und akzeptiert beide
     * Dezimaltrennzeichen. Gibt null zurück, wenn die Eingabe keine Zahl ist, damit der
     * Aufrufer das Feld im Fehlerzustand lassen kann, statt stillschweigend null zu
     * buchen.
     *
     * Welches Trennzeichen zuletzt vorkommt, ist das Dezimaltrennzeichen — "1.234,50" und
     * "1,234.50" ergeben beide 1234,50. Ein einzelnes Trennzeichen gilt immer als
     * Dezimalpunkt: Wer "12.50" auf einem Ziffernblock tippt, meint zwölf fünfzig, und
     * diesen Punkt als Tausendertrenner zu lesen, würde ihm 1250 berechnen.
     */
    fun parse(input: String): Double? {
        val cleaned = input.trim()
            .replace("−", "-")
            .replace(" ", "")
            .replace(" ", "") // geschütztes Leerzeichen, schmal und normal —
            .replace(" ", "") // das setzt die Euro-Formatierung vor das Zeichen
            .replace("€", "")
        if (cleaned.isEmpty()) return null

        val lastDot = cleaned.lastIndexOf('.')
        val lastComma = cleaned.lastIndexOf(',')

        val normalised = when {
            // Beide vorhanden: das spätere ist das Dezimaltrennzeichen.
            lastDot >= 0 && lastComma >= 0 ->
                if (lastDot > lastComma) cleaned.replace(",", "")
                else cleaned.replace(".", "").replace(',', '.')
            // Nur Kommas: Dezimaltrenner, aber "1,234,50" muss die Gruppierung los werden.
            lastComma >= 0 ->
                cleaned.substring(0, lastComma).replace(",", "") +
                    "." + cleaned.substring(lastComma + 1)
            // Nur Punkte: dasselbe, gespiegelt.
            lastDot >= 0 ->
                cleaned.substring(0, lastDot).replace(".", "") +
                    "." + cleaned.substring(lastDot + 1)
            else -> cleaned
        }
        return normalised.toDoubleOrNull()
    }
}
