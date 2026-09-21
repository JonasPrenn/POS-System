package com.example.vereins_kassensystem.ui.format

import kotlin.math.abs
import kotlin.math.round

/**
 * Feste Nachkommastellen und Tausenderpunkte, ohne `java.text`.
 *
 * `NumberFormat` und `String.format` gibt es nur auf der JVM. Die App braucht davon
 * genau zwei Dinge — kaufmännisch gerundete Nachkommastellen und deutsche
 * Trennzeichen — und die stehen hier.
 *
 * Bewusst nicht lokalisiert: VereinsDeckel rechnet in Euro und schreibt deutsch, auf
 * jedem Gerät gleich. Eine Kasse, deren Beträge je nach Systemsprache des Tablets
 * anders aussehen, ist beim Abrechnen ein Ärgernis.
 */
internal object Decimals {

    /**
     * Rundet auf [digits] Stellen und setzt sie alle, auch nachlaufende Nullen.
     *
     * Gerundet wird über [round] auf dem skalierten Wert, also kaufmännisch von der Null
     * weg: 12,25 wird bei einer Stelle zu 12,3 und nicht zu 12,2.
     *
     * Was das nicht heilt, ist die Gleitkommadarstellung selbst — 4.005 liegt als Double
     * knapp unter 4,005 und rundet deshalb auf 4,00 ab. Preise sind hier Doubles, weil
     * sie es in der Datenbank schon sind; wer das sauber haben will, muss Beträge auf
     * Cent als Ganzzahl umstellen, und das ist eine Änderung am Datenmodell, nicht an
     * der Formatierung.
     */
    fun fixed(value: Double, digits: Int): String {
        if (value.isNaN() || value.isInfinite()) return "0"

        var scale = 1.0
        repeat(digits) { scale *= 10.0 }

        val negative = value < 0.0
        val scaled = round(abs(value) * scale).toLong()

        val whole = scaled / scale.toLong()
        val frac = scaled % scale.toLong()

        val sb = StringBuilder()
        if (negative && scaled != 0L) sb.append('-')
        sb.append(whole.toString())
        if (digits > 0) {
            sb.append(',')
            val f = frac.toString()
            repeat(digits - f.length) { sb.append('0') }
            sb.append(f)
        }
        return sb.toString()
    }

    /** Setzt Tausenderpunkte in den Vorkommateil: "1234,50" wird zu "1.234,50". */
    fun grouped(formatted: String): String {
        val negative = formatted.startsWith('-')
        val body = if (negative) formatted.substring(1) else formatted

        val comma = body.indexOf(',')
        val whole = if (comma >= 0) body.substring(0, comma) else body
        val rest = if (comma >= 0) body.substring(comma) else ""

        val sb = StringBuilder()
        for ((i, ch) in whole.withIndex()) {
            // Punkt vor jeder Dreiergruppe, von rechts gezählt.
            if (i > 0 && (whole.length - i) % 3 == 0) sb.append('.')
            sb.append(ch)
        }
        return (if (negative) "-" else "") + sb.toString() + rest
    }
}
