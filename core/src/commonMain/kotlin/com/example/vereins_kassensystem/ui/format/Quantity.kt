package com.example.vereins_kassensystem.ui.format

/**
 * Lagermengen, an einer Stelle — derselbe Schritt, den [Money] für Beträge gemacht hat.
 *
 * Eine Kellerzahl ist keine Währung: "2 Fässer" soll nicht "2,00" heißen und 0,5 l nicht
 * "1". Ganze Zahlen verlieren also die Nachkommastellen, Bruchteile behalten genau eine,
 * was so fein ist, wie hinter einer Theke überhaupt gemessen wird.
 *
 * Bildschirm und ViewModel hatten davon je eine eigene Kopie. Sie waren sich einig aus
 * Zufall, nicht von Bauart — das bleibt wahr, bis eine der beiden bearbeitet wird.
 */
object Quantity {

    /** 2.0 -> "2", 0.5 -> "0,5", 12.25 -> "12,3". */
    fun format(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString()
        else Decimals.fixed(value, 1)

    /** [format] mit führendem Plus, wo die Richtung der Bewegung der Punkt ist. */
    fun formatSigned(value: Double): String =
        if (value >= 0.0) "+" + format(value) else format(value)
}
