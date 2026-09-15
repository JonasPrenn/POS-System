package com.example.vereins_kassensystem.ui.format

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Currency formatting, in one place.
 *
 * Previously every screen built its own with `String.format("%.2f") + " €"`, which
 * ignores the device locale and disagreed with the German date formatting used a few
 * lines away. A club running this app is invoicing in euro and reading German, so the
 * format is pinned rather than left to the device: 1234.5 renders as "1.234,50 €".
 */
object Money {

    private val locale: Locale = Locale.GERMANY

    private val formatter: NumberFormat =
        NumberFormat.getCurrencyInstance(locale).apply {
            currency = Currency.getInstance("EUR")
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }

    /** "1.234,50 €" — the default for anything the user reads as an amount. */
    fun format(amount: Double): String = synchronized(formatter) {
        formatter.format(amount)
    }

    /** "+5,00 €" / "−12,50 €", for balance movements where direction is the point. */
    fun formatSigned(amount: Double): String {
        val body = format(kotlin.math.abs(amount))
        return when {
            amount > 0.0 -> "+$body"
            amount < 0.0 -> "−$body" // real minus sign, not a hyphen
            else -> body
        }
    }

    /**
     * Plain decimal with no currency symbol, for text fields and CSV where a symbol
     * would have to be stripped again.
     */
    fun formatPlain(amount: Double): String = String.format(locale, "%.2f", amount)

    /**
     * Reads what a user typed into an amount field, accepting either decimal separator.
     * Returns null when the input is not a number, so callers can keep the field in an
     * error state rather than silently charging zero.
     *
     * Whichever separator appears last is the decimal one, so "1.234,50" and "1,234.50"
     * both give 1234.50. A lone separator is always read as the decimal point: someone
     * typing "12.50" on a keypad means twelve fifty, and treating that dot as a
     * thousands separator would charge them 1250.
     */
    fun parse(input: String): Double? {
        val cleaned = input.trim()
            .replace("−", "-")
            .replace(" ", "")
            .replace("\u00A0", "") // NBSP / narrow NBSP: what the euro
            .replace("\u202F", "") // formatter puts before the symbol
            .replace("\u20AC", "")
        if (cleaned.isEmpty()) return null

        val lastDot = cleaned.lastIndexOf('.')
        val lastComma = cleaned.lastIndexOf(',')

        val normalised = when {
            // Both present: the later one is the decimal separator.
            lastDot >= 0 && lastComma >= 0 ->
                if (lastDot > lastComma) cleaned.replace(",", "")
                else cleaned.replace(".", "").replace(',', '.')
            // Only commas: decimal separator, but "1,234,50" still needs grouping removed.
            lastComma >= 0 ->
                cleaned.substring(0, lastComma).replace(",", "") +
                    "." + cleaned.substring(lastComma + 1)
            // Only dots: same treatment, mirrored.
            lastDot >= 0 ->
                cleaned.substring(0, lastDot).replace(".", "") +
                    "." + cleaned.substring(lastDot + 1)
            else -> cleaned
        }
        return normalised.toDoubleOrNull()
    }
}
