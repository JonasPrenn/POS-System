package com.example.vereins_kassensystem.server.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Ein Schreibversuch, der nicht anwendbar ist — wird zu 422 (Spezifikation 5.3). */
class Unprocessable(message: String) : RuntimeException(message)

/**
 * Werte zwischen JSON, Kotlin und JDBC, nach den Zahlenformaten aus Kapitel 5.4:
 * Geld als Zeichenkette mit zwei Nachkommastellen, Mengen als Zahl, Zeit als ISO 8601 in
 * UTC mit `Z`, UUIDs klein mit Bindestrichen.
 *
 * Beim Lesen ist der Server nachsichtig (Geld auch als Zahl, Zeit auch als
 * Epoch-Millisekunden, UUID auch in Großbuchstaben), beim Schreiben strikt — die App soll
 * genau ein Format lernen müssen.
 */
object Values {

    private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    private val TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneOffset.UTC)

    fun format(instant: Instant): String = TIMESTAMP.format(instant)

    fun format(time: OffsetDateTime): String = format(time.toInstant())

    fun parseUuid(text: String, what: String): UUID {
        if (!UUID_PATTERN.matches(text)) throw Unprocessable("$what ist keine UUID: '$text'")
        return UUID.fromString(text)
    }

    /** Auf Millisekunden gestutzt — mehr speichert das Schema nicht, und verglichen wird exakt. */
    fun parseTimestamp(text: String, what: String): OffsetDateTime = try {
        OffsetDateTime.parse(text).truncatedTo(ChronoUnit.MILLIS).withOffsetSameInstant(ZoneOffset.UTC)
    } catch (e: DateTimeParseException) {
        throw Unprocessable("$what ist kein Zeitstempel nach ISO 8601: '$text'")
    }

    fun parseMoney(text: String, what: String): BigDecimal {
        val value = try {
            BigDecimal(text.trim())
        } catch (e: NumberFormatException) {
            throw Unprocessable("$what ist kein Betrag: '$text'")
        }
        if (value.scale() > 2) throw Unprocessable("$what hat mehr als zwei Nachkommastellen: '$text'")
        if (value.precision() - value.scale() > 10) throw Unprocessable("$what ist zu groß: '$text'")
        return value.setScale(2)
    }

    /** JSON → getypter Wert (UUID, String, BigDecimal, Int, Double, Boolean, OffsetDateTime). */
    fun decode(column: Column, element: JsonElement, what: String): Any? {
        if (element is JsonNull) {
            if (!column.nullable) throw Unprocessable("$what darf nicht null sein")
            return null
        }
        val primitive = element as? JsonPrimitive
            ?: throw Unprocessable("$what muss ein einfacher Wert sein, kein Objekt oder Array")
        return when (column.type) {
            ColumnType.UUID -> parseUuid(primitive.content, what)
            ColumnType.TEXT -> {
                if (!primitive.isString) throw Unprocessable("$what muss eine Zeichenkette sein")
                val text = primitive.content
                column.allowed?.let { allowed ->
                    if (text !in allowed) throw Unprocessable("$what muss eines von ${allowed.sorted()} sein, nicht '$text'")
                }
                text
            }
            ColumnType.MONEY -> parseMoney(primitive.content, what)
            ColumnType.INT -> {
                if (primitive.isString) throw Unprocessable("$what muss eine Zahl sein")
                primitive.content.toIntOrNull() ?: throw Unprocessable("$what muss eine ganze Zahl sein: '${primitive.content}'")
            }
            ColumnType.DOUBLE -> {
                if (primitive.isString) throw Unprocessable("$what muss eine Zahl sein")
                primitive.content.toDoubleOrNull()?.takeIf { it.isFinite() }
                    ?: throw Unprocessable("$what muss eine Zahl sein: '${primitive.content}'")
            }
            ColumnType.BOOL -> primitive.booleanOrNull ?: throw Unprocessable("$what muss true oder false sein")
            ColumnType.TIMESTAMP -> {
                if (primitive.isString) parseTimestamp(primitive.content, what)
                else {
                    val millis = primitive.content.toLongOrNull()
                        ?: throw Unprocessable("$what muss ein Zeitstempel sein: '${primitive.content}'")
                    Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC)
                }
            }
        }
    }

    /** Getypter Wert → JSON, im Format, das die App lernt. */
    fun encode(column: Column, value: Any?): JsonElement = when (value) {
        null -> JsonNull
        else -> when (column.type) {
            ColumnType.UUID -> JsonPrimitive(value.toString().lowercase())
            ColumnType.TEXT -> JsonPrimitive(value as String)
            ColumnType.MONEY -> JsonPrimitive((value as BigDecimal).setScale(2).toPlainString())
            ColumnType.INT -> JsonPrimitive(value as Int)
            ColumnType.DOUBLE -> JsonPrimitive(value as Double)
            ColumnType.BOOL -> JsonPrimitive(value as Boolean)
            ColumnType.TIMESTAMP -> JsonPrimitive(format(value as OffsetDateTime))
        }
    }

    /** Liest die Spalte getypt aus dem ResultSet, null-bewusst. */
    fun read(column: Column, rs: ResultSet): Any? = when (column.type) {
        ColumnType.UUID -> rs.getObject(column.name, UUID::class.java)
        ColumnType.TEXT -> rs.getString(column.name)
        ColumnType.MONEY -> rs.getBigDecimal(column.name)
        ColumnType.INT -> rs.getInt(column.name).takeUnless { rs.wasNull() }
        ColumnType.DOUBLE -> rs.getDouble(column.name).takeUnless { rs.wasNull() }
        ColumnType.BOOL -> rs.getBoolean(column.name).takeUnless { rs.wasNull() }
        ColumnType.TIMESTAMP -> rs.getObject(column.name, OffsetDateTime::class.java)
    }
}
