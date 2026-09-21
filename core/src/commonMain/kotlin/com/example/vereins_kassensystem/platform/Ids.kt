package com.example.vereins_kassensystem.platform

import kotlin.random.Random

/**
 * Schlüssel, die zwischen Geräten nicht kollidieren.
 *
 * `java.util.UUID` gibt es auf iOS nicht, und `kotlin.uuid.Uuid` ist je nach
 * Kotlin-Stand noch mit Opt-in versehen. Beides vermeidet diese Datei, indem sie die
 * sechzehn Bytes selbst zusammensetzt — das ist überschaubar und hängt an nichts.
 *
 * Erzeugt wird Variante 7: die ersten sechs Bytes tragen die Unix-Zeit in
 * Millisekunden, der Rest ist Zufall. Gegenüber Variante 4 hat das zwei Vorteile, die
 * hier beide zählen — die Schlüssel sind zeitlich sortiert, also sind Datenbankindizes
 * nicht nach jedem Einfügen zerfasert, und eine Liste nach Schlüssel sortiert ist
 * nebenbei chronologisch.
 *
 * Die Spezifikation in docs/ setzt dieselbe Variante voraus; Client und Server erzeugen
 * Schlüssel nach derselben Regel.
 */
object Ids {

    private const val HEX = "0123456789abcdef"

    /** Ein neuer Schlüssel in der üblichen Schreibweise mit Bindestrichen. */
    fun new(): String {
        val bytes = ByteArray(16)
        Random.nextBytes(bytes)

        // Bytes 0..5: Zeitstempel, höchstwertiges Byte zuerst.
        val millis = nowMillis()
        for (i in 0 until 6) {
            bytes[i] = ((millis shr (8 * (5 - i))) and 0xFF).toByte()
        }

        // Byte 6, obere vier Bit: Version 7. Byte 8, obere zwei Bit: Variante 2.
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()

        return buildString(36) {
            for (i in bytes.indices) {
                if (i == 4 || i == 6 || i == 8 || i == 10) append('-')
                val v = bytes[i].toInt() and 0xFF
                append(HEX[v shr 4])
                append(HEX[v and 0x0F])
            }
        }
    }
}
