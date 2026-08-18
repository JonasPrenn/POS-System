package com.example.vereins_kassensystem.ui.format

import java.util.Locale

/**
 * Stock quantities, in one place — the same move [Money] made for amounts.
 *
 * A cellar figure is not currency: "2 Fässer" should not read "2,00", and 0,5 l should
 * not read "1". So whole numbers lose the decimals and fractions keep exactly one, which
 * is as fine as anything gets measured behind a bar.
 *
 * The screen and the view model each had their own private copy of this. They agreed by
 * luck rather than by construction, which is the kind of thing that stays true until one
 * of them is edited.
 */
object Quantity {

    private val locale: Locale = Locale.GERMANY

    /** 2.0 -> "2", 0.5 -> "0,5", 12.25 -> "12,3". */
    fun format(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString()
        else String.format(locale, "%.1f", value)

    /** [format] with a leading + on positives, for movements where direction is the point. */
    fun formatSigned(value: Double): String =
        if (value >= 0.0) "+" + format(value) else format(value)
}
