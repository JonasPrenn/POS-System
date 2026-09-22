package com.example.vereins_kassensystem.platform

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Die Uhr der App, an genau einer Stelle.
 *
 * Die JVM-Entsprechung aus `java.lang.System` gibt es auf iOS nicht. Statt elf
 * Aufrufstellen einzeln umzustellen, hängt hier alles an einer Funktion: Sollte sich die
 * Herkunft der Zeit ändern — andere Standardbibliothek, injizierte Uhr für Tests — ist
 * das eine Zeile.
 */
@Suppress("NOTHING_TO_INLINE")
inline fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

/**
 * Datums- und Zeitangaben auf Deutsch.
 *
 * Die App hatte dafür `SimpleDateFormat` mit `Locale.GERMANY`, was auf iOS nicht
 * existiert. Die Formate werden hier von Hand gesetzt statt über eine
 * Lokalisierungsbibliothek: Es sind genau zwei, sie sind fest deutsch, und die
 * Monatsnamen einer Sprache sind eine Liste mit zwölf Einträgen — dafür lohnt keine
 * Abhängigkeit.
 */
object VdDate {

    private val monthsShort = listOf(
        "Jan", "Feb", "Mär", "Apr", "Mai", "Jun",
        "Jul", "Aug", "Sep", "Okt", "Nov", "Dez"
    )

    private val monthsLong = listOf(
        "Januar", "Februar", "März", "April", "Mai", "Juni",
        "Juli", "August", "September", "Oktober", "November", "Dezember"
    )

    private val weekdays = listOf(
        "Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"
    )

    private fun localOf(epochMillis: Long): LocalDateTime =
        Instant.fromEpochMilliseconds(epochMillis)
            .toLocalDateTime(TimeZone.currentSystemDefault())

    private fun pad(value: Int): String = if (value < 10) "0$value" else value.toString()

    /** "15. Sep · 18:22" — für Listenzeilen in Historie und Wareneingang. */
    fun dayAndTime(epochMillis: Long): String {
        val t = localOf(epochMillis)
        return "${pad(t.day)}. ${monthsShort[t.month.number - 1]} · " +
            "${pad(t.hour)}:${pad(t.minute)}"
    }

    /** "18:22" — für die Statusanzeige des Abgleichs, wo der Tag ohnehin heute ist. */
    fun timeOfDay(epochMillis: Long): String {
        val t = localOf(epochMillis)
        return "${pad(t.hour)}:${pad(t.minute)}"
    }

    /** "Montag, 15. September" — die Begrüßungszeile auf dem Dashboard. */
    fun weekdayAndDate(epochMillis: Long): String {
        val t = localOf(epochMillis)
        // isoDayNumber ist 1 = Montag, die Liste beginnt ebenfalls mit Montag.
        val weekday = weekdays[t.dayOfWeek.ordinal]
        return "$weekday, ${t.day}. ${monthsLong[t.month.number - 1]}"
    }

    /**
     * Ob zwei Zeitpunkte auf denselben Kalendertag fallen, in der Zeitzone des Geräts.
     *
     * Nicht über "weniger als 24 Stunden auseinander" zu lösen: Um 00:30 liegt der
     * Verkauf von 23:50 keine halbe Stunde zurück und gehört trotzdem zu gestern — was
     * für eine Theke, die über Mitternacht offen hat, der Normalfall ist.
     */
    fun isSameDay(a: Long, b: Long): Boolean {
        val da = localOf(a)
        val db = localOf(b)
        return da.year == db.year && da.dayOfYear == db.dayOfYear
    }

    /** "2026-09-15_1822" — für Dateinamen von Sicherungen, sortierbar und harmlos. */
    fun fileStamp(epochMillis: Long): String {
        val t = localOf(epochMillis)
        return "${t.year}-${pad(t.month.number)}-${pad(t.day)}_" +
            "${pad(t.hour)}${pad(t.minute)}"
    }
}
