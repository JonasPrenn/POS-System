package com.example.vereins_kassensystem.data

/**
 * Was in der Lade liegt, Stück für Stück gezählt: je Schein und Münze die Anzahl.
 *
 * Wer beim Öffnen und Schließen der Kasse nur eine Summe eintippt, rechnet vorher im Kopf —
 * und verzählt sich dort. Gezählt wird deshalb, wie man eine Lade zählt: Stapel für Stapel,
 * von 100 € bis 1 Cent, und die Summe rechnet die Kasse. In Cent und als ganze Zahl, denn
 * dreimal zehn Cent in Gleitkomma sind nicht dreißig.
 *
 * Gespeichert wird nur die Summe (`openingCount`, `closingCount`); die Stückelung hilft beim
 * Zählen und geht nicht über den Draht.
 */
data class CashCount(val pieces: Map<Int, Int> = emptyMap()) {

    /** Die Summe in Cent. */
    val totalCents: Long get() = pieces.entries.sumOf { (value, count) -> value.toLong() * count }

    /** Die Summe in Euro, so wie sie in der Schicht steht. */
    val total: Double get() = totalCents / 100.0

    /** Wie viele Stück dieses Werts gezählt sind. */
    fun count(value: Int): Int = pieces[value] ?: 0

    /** Dieselbe Zählung mit [count] Stück von [value]; null Stück heißt: nichts gezählt. */
    fun with(value: Int, count: Int): CashCount {
        require(value in DENOMINATIONS) { "Kein Schein und keine Münze der Lade: $value Cent" }
        require(count >= 0) { "Eine Anzahl ist nie negativ: $count" }
        return CashCount(if (count == 0) pieces - value else pieces + (value to count))
    }

    companion object {
        /** Die Werte der Lade in Cent, vom größten zum kleinsten: Scheine bis 100 €, Münzen bis 1 Cent. */
        val DENOMINATIONS: List<Int> = listOf(10_000, 5_000, 2_000, 1_000, 500, 200, 100, 50, 20, 10, 5, 2, 1)

        /** Der kleinste Schein; alles darunter ist eine Münze. */
        const val SMALLEST_NOTE: Int = 500
    }
}
