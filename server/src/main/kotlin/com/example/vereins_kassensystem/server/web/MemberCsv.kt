package com.example.vereins_kassensystem.server.web

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.UUID

/**
 * Mitglieder als CSV, hinein und hinaus (Konzept 4.1). Die Datei des Tablets
 * („Name;Mitgliedergruppe“) geht ebenso wie eine aus Excel: Trennzeichen erkannt,
 * Anführungszeichen verstanden, UTF-8 mit oder ohne BOM, sonst Windows-1252. Die Kopfzeile
 * sagt, was eine Spalte ist — erkannt werden Name, Couleurname, Kategorie, Mitgliedsnummer,
 * E-Mail, Anschrift, Einwilligung und Notizen, alles andere bleibt liegen. Wen es schon gibt,
 * legt der Import nicht noch einmal an; er ergänzt nur, was leer ist. Und er tut nichts,
 * bevor der Kassier die Vorschau gesehen hat.
 */
object MemberCsv {

    class Row(val line: Int, val name: String, val nickname: String, val category: String, val number: String, val email: String, val address: String, val consent: Boolean?, val notes: String)

    class Table(val text: String, val rows: List<Row>, val delimiter: Char, val charset: String)

    enum class Fate { NEW, EXISTS, SKIPPED }

    class Planned(val row: Row, val fate: Fate, val reason: String = "", val existingId: UUID? = null, val newCategory: Boolean = false, val fills: List<String> = emptyList())

    class Plan(val table: Table, val lines: List<Planned>) {
        val created get() = lines.count { it.fate == Fate.NEW }
        val existing get() = lines.count { it.fate == Fate.EXISTS }
        val skipped get() = lines.count { it.fate == Fate.SKIPPED }
        val filled get() = lines.count { it.fills.isNotEmpty() }
        val newCategories get() = lines.filter { it.newCategory }.map { it.row.category }.distinctBy { it.lowercase() }
        val hasWork get() = created > 0 || filled > 0
    }

    private val HEADERS = mapOf(
        "name" to "name", "mitglied" to "name", "mitgliedsname" to "name",
        "couleurname" to "nickname", "couleurname (vulgo)" to "nickname", "vulgo" to "nickname", "spitzname" to "nickname", "nickname" to "nickname",
        "mitgliedergruppe" to "category", "kategorie" to "category", "gruppe" to "category", "category" to "category",
        "mitgliedsnummer" to "number", "nummer" to "number", "nr" to "number", "nr." to "number",
        "e-mail" to "email", "email" to "email", "mail" to "email", "e-mail-adresse" to "email",
        "anschrift" to "address", "adresse" to "address", "address" to "address",
        "einwilligung" to "consent", "einwilligung e-mail" to "consent", "e-mail-einwilligung" to "consent",
        "notizen" to "notes", "notiz" to "notes", "bemerkung" to "notes",
    )
    private val YES = setOf("ja", "j", "1", "x", "true", "wahr", "yes")

    fun parse(bytes: ByteArray): Table {
        val (text, charset) = decode(bytes)
        if (text.isBlank()) throw AccountProblem("Die Datei ist leer.")
        val firstLine = text.lineSequence().first { it.isNotBlank() }
        val delimiter = listOf(';', ',', '\t').filter { d -> firstLine.contains(d) }.maxByOrNull { d -> firstLine.count { it == d } } ?: ';'
        val records = records(text, delimiter)
        val roles = records.first().map { HEADERS[it.trim().trimStart('﻿').lowercase()] }
        if ("name" !in roles) throw AccountProblem("Die erste Zeile braucht eine Spalte „Name“ — so, wie das Tablet exportiert: Name;Mitgliedergruppe.")
        fun index(role: String): Int? = roles.indexOf(role).takeIf { it >= 0 }
        val iName = index("name")!!; val iNick = index("nickname"); val iCat = index("category"); val iNo = index("number")
        val iMail = index("email"); val iAddr = index("address"); val iCons = index("consent"); val iNotes = index("notes")
        val rows = records.drop(1).mapIndexed { i, fields ->
            fun at(index: Int?): String = index?.let { fields.getOrNull(it) }?.trim().orEmpty()
            Row(i + 2, at(iName), at(iNick), at(iCat), at(iNo), at(iMail), at(iAddr), iCons?.let { at(it).lowercase() in YES }, at(iNotes))
        }
        return Table(text, rows, delimiter, charset)
    }

    /** Was der Import täte — gegen die Mitglieder und Kategorien, die es schon gibt. Wird vor dem Anlegen noch einmal gerechnet. */
    fun plan(table: Table, members: List<MemberLine>, categories: List<MemberCategoryOption>, profiles: Map<UUID, Profile>): Plan {
        val byName = members.associateBy { it.name.trim().lowercase() }
        val knownCategories = categories.map { it.name.lowercase() }.toMutableSet()
        val seen = HashSet<String>()
        val lines = table.rows.map { row ->
            val key = row.name.replace(Regex("\\s+"), " ").trim().lowercase()
            when {
                key.isEmpty() -> Planned(row, Fate.SKIPPED, "kein Name")
                key.length > 80 -> Planned(row, Fate.SKIPPED, "Name länger als 80 Zeichen")
                !seen.add(key) -> Planned(row, Fate.SKIPPED, "steht weiter oben schon einmal")
                row.email.isNotEmpty() && (!row.email.contains('@') || row.email.contains(' ')) -> Planned(row, Fate.SKIPPED, "keine E-Mail-Adresse: ${row.email}")
                else -> {
                    val existing = byName[key]
                    if (existing == null) Planned(row, Fate.NEW, newCategory = row.category.isNotBlank() && knownCategories.add(row.category.lowercase()))
                    else {
                        val p = profiles[existing.id] ?: Profile.empty(existing.id)
                        val fills = listOfNotNull(
                            "Couleurname".takeIf { existing.nickname.isBlank() && row.nickname.isNotBlank() },
                            "Mitgliedsnummer".takeIf { p.number.isBlank() && row.number.isNotBlank() },
                            "E-Mail".takeIf { p.email.isBlank() && row.email.isNotBlank() },
                            "Anschrift".takeIf { p.address.isBlank() && row.address.isNotBlank() },
                            "Notizen".takeIf { p.notes.isBlank() && row.notes.isNotBlank() },
                        )
                        Planned(row, Fate.EXISTS, existingId = existing.id, fills = fills)
                    }
                }
            }
        }
        return Plan(table, lines)
    }

    /** Die Liste als Datei — Name und Gruppe voran, damit auch ein Tablet ohne Server sie einlesen kann. Mit BOM, damit Excel die Umlaute richtig liest. */
    fun export(members: List<MemberLine>, profiles: Map<UUID, Profile>): String = buildString {
        append('﻿')
        append("Name;Mitgliedergruppe;Couleurname;Mitgliedsnummer;E-Mail;Anschrift;Einwilligung E-Mail\r\n")
        for (m in members) {
            val p = profiles[m.id] ?: Profile.empty(m.id)
            append(listOf(m.name, m.category.orEmpty(), m.nickname, p.number, p.email, p.address, if (p.consentEmail) "ja" else "nein").joinToString(";") { quote(it) })
            append("\r\n")
        }
    }

    private fun quote(value: String): String =
        if (value.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    /** UTF-8, wenn es eines ist (mit oder ohne BOM), sonst Windows-1252 — was Excel unter Windows schreibt. */
    private fun decode(bytes: ByteArray): Pair<String, String> {
        val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) bytes.copyOfRange(3, bytes.size) else bytes
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString() to "UTF-8"
        } catch (e: CharacterCodingException) {
            String(body, Charset.forName("windows-1252")) to "Windows-1252"
        }
    }

    /** RFC 4180 in klein: Anführungszeichen, doppelte darin als Escape, Zeilenumbrüche innerhalb erlaubt. Leere Zeilen zählen nicht. */
    private fun records(text: String, delimiter: Char): List<List<String>> {
        val out = ArrayList<List<String>>()
        var fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                quoted && ch == '"' -> quoted = false
                quoted -> field.append(ch)
                ch == '"' -> quoted = true
                ch == delimiter -> { fields.add(field.toString()); field.setLength(0) }
                ch == '\r' -> Unit
                ch == '\n' -> { fields.add(field.toString()); field.setLength(0); out.add(fields); fields = ArrayList() }
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || fields.isNotEmpty()) { fields.add(field.toString()); out.add(fields) }
        return out.filter { r -> r.any { it.isNotBlank() } }
    }
}
