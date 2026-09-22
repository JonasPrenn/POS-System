package com.example.vereins_kassensystem.server.sync

import com.example.vereins_kassensystem.server.db.Database
import com.example.vereins_kassensystem.server.db.execute
import com.example.vereins_kassensystem.server.db.query
import com.example.vereins_kassensystem.server.db.queryOne
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.math.BigDecimal
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Das Synchronisationsprotokoll aus Kapitel 4: Ziehen ab Lesezeiger, Schieben mit
 * Idempotenzschlüsseln, Konfliktregeln je Tabellenart.
 *
 * Alle Operationen eines Schiebevorgangs laufen in einer Transaktion — entweder alle oder
 * keine. Was nicht anwendbar ist (unbekannte Entität, abgeleitetes Feld, Änderung an einer
 * anfügenden Tabelle), bricht als [Unprocessable] ab und wird zu 422; das ist ein
 * Programmfehler in der App, kein Betriebsfall. Konflikte dagegen sind Betriebsfälle und
 * kommen als `ignored_stale` mit dem Serverstand zurück.
 */
class SyncStore(private val db: Database) {

    data class Change(val entity: String, val seq: Long, val deleted: Boolean, val row: JsonObject)

    data class Page(val changes: List<Change>, val nextSince: Long, val hasMore: Boolean)

    data class Operation(
        val clientChangeId: UUID,
        val entity: String,
        val op: String,
        val baseUpdatedAt: OffsetDateTime?,
        val row: JsonObject,
    )

    data class OpResult(val clientChangeId: UUID, val status: String, val seq: Long?, val current: JsonObject?)

    data class PushOutcome(val results: List<OpResult>, val nextSince: Long)

    /** Eine gelesene Zeile mitsamt den Sync-Spalten. */
    private class Row(
        val values: Map<String, Any?>,
        val seq: Long,
        val updatedAt: OffsetDateTime,
        val deleted: Boolean,
        val deletedAt: OffsetDateTime?,
    )

    private sealed interface Outcome {
        data class Applied(val seq: Long) : Outcome
        data class Stale(val current: JsonObject?) : Outcome
    }

    // ------------------------------------------------------------------ 4.1 Ziehen

    /**
     * Alle Änderungen über [since], gemischt über alle Tabellen in Sequenzreihenfolge —
     * so kommt eine Variante nie vor ihrem Produkt an.
     */
    fun changes(since: Long, limit: Int): Page = db.read { c ->
        val merged = Entities.all
            .flatMap { def -> readChanges(c, def, since, limit + 1) }
            .sortedBy { it.seq }
        val page = merged.take(limit)
        Page(page, page.lastOrNull()?.seq ?: since, merged.size > limit)
    }

    private fun readChanges(c: Connection, def: EntityDef, since: Long, limit: Int): List<Change> =
        c.query(
            "SELECT * FROM ${def.table} WHERE server_seq > ? ORDER BY server_seq LIMIT ?",
            since, limit
        ) { rs ->
            val row = readRow(def, rs)
            Change(def.table, row.seq, row.deleted, encode(def, row))
        }

    // ------------------------------------------------------------------ 4.2 Schieben

    fun push(deviceId: UUID, operations: List<Operation>): PushOutcome = db.write { c ->
        val results = try {
            operations.mapIndexed { index, op -> apply(c, deviceId, op, index) }
        } catch (e: SQLException) {
            // Fremdschlüssel, CHECK, UNIQUE: Die Zeile passt nicht zum Bestand — ein
            // Programmfehler der App, den sie gemeldet bekommt, kein Serverausfall.
            if (e.sqlState?.startsWith("23") == true) throw Unprocessable("Datenbank lehnt die Zeile ab: ${e.message}")
            throw e
        }
        val nextSince = results.mapNotNull { it.seq }.maxOrNull() ?: currentSeq(c)
        PushOutcome(results, nextSince)
    }

    private fun apply(c: Connection, deviceId: UUID, op: Operation, index: Int): OpResult {
        // Wiederholung nach Netzabbruch: dieselbe Antwort wie beim ersten Mal.
        c.queryOne(
            "SELECT result, seq FROM applied_changes WHERE client_change_id = ?", op.clientChangeId
        ) { rs -> OpResult(op.clientChangeId, rs.getString("result"), rs.getLong("seq").takeUnless { rs.wasNull() }, null) }
            ?.let { return it }

        val what = "Operation $index (${op.entity}, ${op.op})"
        if (op.entity in Entities.derivedEntities) {
            throw Unprocessable("$what: '${op.entity}' ist abgeleitet und nur lesbar")
        }
        if (op.entity in Entities.serverOnly) {
            throw Unprocessable("$what: '${op.entity}' setzt die Verwaltung; die Geräte lesen sie nur")
        }
        val def = Entities.byName[op.entity] ?: throw Unprocessable("$what: unbekannte Entität")
        val values = decodeRow(def, op.row, partial = op.op != "insert", what)
        val id = values["id"] as? UUID ?: throw Unprocessable("$what: 'id' fehlt")

        val outcome = when (op.op) {
            "insert" -> insert(c, def, deviceId, id, values, what)
            "update" -> update(c, def, id, values, op.baseUpdatedAt, what)
            "delete" -> delete(c, def, id, op.baseUpdatedAt, what)
            else -> throw Unprocessable("$what: unbekannte Operation, erlaubt sind insert, update, delete")
        }

        val result = when (outcome) {
            is Outcome.Applied -> OpResult(op.clientChangeId, "applied", outcome.seq, null)
            is Outcome.Stale -> OpResult(op.clientChangeId, "ignored_stale", null, outcome.current)
        }
        c.execute(
            "INSERT INTO applied_changes (client_change_id, device_id, entity, entity_id, result, seq) VALUES (?, ?, ?, ?, ?, ?)",
            op.clientChangeId, deviceId, def.table, id, result.status, result.seq
        )
        return result
    }

    private fun insert(c: Connection, def: EntityDef, deviceId: UUID, id: UUID, values: Map<String, Any?>, what: String): Outcome {
        // Gleiche id, egal welche Tabelle: dieselbe Zeile ist schon da. Der Client
        // bekommt den Serverstand und kann, wenn er wirklich etwas anderes will, ändern.
        readRow(c, def, id)?.let { return Outcome.Stale(encode(def, it)) }

        if (def.kind == EntityKind.EVENT) {
            conflictingOpenContainer(c, values)?.let { open ->
                val incoming = values["opened_at"] as OffsetDateTime
                if (incoming.isBefore(open.timestampOf("opened_at"))) {
                    // Der frühere Anstich gewinnt; der spätere wird verworfen und taucht
                    // beim nächsten Ziehen als gelöscht auf.
                    c.execute("UPDATE tapped_containers SET deleted = true, deleted_at = now() WHERE id = ?", open.values["id"])
                } else {
                    return Outcome.Stale(encode(def, open))
                }
            }
        }

        val columns = def.columns.filter { it.name in values }
        val names = columns.joinToString(", ") { it.name } + ", origin_device"
        val marks = columns.joinToString(", ") { "?" } + ", ?"
        val params = columns.map { values[it.name] } + deviceId
        val seq = c.queryOne(
            "INSERT INTO ${def.table} ($names) VALUES ($marks) RETURNING server_seq", *params.toTypedArray()
        ) { it.getLong(1) } ?: error("$what: INSERT ohne RETURNING")
        return Outcome.Applied(seq)
    }

    private fun update(c: Connection, def: EntityDef, id: UUID, values: Map<String, Any?>, base: OffsetDateTime?, what: String): Outcome {
        if (def.kind == EntityKind.APPEND_ONLY) {
            throw Unprocessable("$what: '${def.table}' wird nie geändert; Korrekturen sind neue Zeilen")
        }
        val current = readRow(c, def, id) ?: return Outcome.Stale(null)
        if (current.deleted || isStale(current, base)) return Outcome.Stale(encode(def, current))

        val changed = def.columns.filter { it.name != "id" && it.name in values }
        if (changed.isEmpty()) return Outcome.Applied(current.seq)

        val sets = changed.joinToString(", ") { "${it.name} = ?" }
        val params = changed.map { values[it.name] } + id
        val seq = c.queryOne(
            "UPDATE ${def.table} SET $sets WHERE id = ? RETURNING server_seq", *params.toTypedArray()
        ) { it.getLong(1) } ?: error("$what: UPDATE ohne RETURNING")
        return Outcome.Applied(seq)
    }

    private fun delete(c: Connection, def: EntityDef, id: UUID, base: OffsetDateTime?, what: String): Outcome {
        if (def.kind == EntityKind.APPEND_ONLY) {
            throw Unprocessable("$what: '${def.table}' wird nie gelöscht; Stornos sind neue Zeilen")
        }
        val current = readRow(c, def, id) ?: return Outcome.Stale(null)
        if (current.deleted) return Outcome.Applied(current.seq)
        if (isStale(current, base)) return Outcome.Stale(encode(def, current))

        val seq = c.queryOne(
            "UPDATE ${def.table} SET deleted = true, deleted_at = now() WHERE id = ? RETURNING server_seq", id
        ) { it.getLong(1) } ?: error("$what: UPDATE ohne RETURNING")
        return Outcome.Applied(seq)
    }

    /** Letzter Schreibvorgang gewinnt: ist die Serverzeile neuer als die Basis des Clients, ist der Client veraltet. */
    private fun isStale(current: Row, base: OffsetDateTime?): Boolean =
        base != null && current.updatedAt.truncatedTo(ChronoUnit.MILLIS).isAfter(base.truncatedTo(ChronoUnit.MILLIS))

    /**
     * Das offene Gebinde desselben Lagerartikels, falls es eines gibt. Geprüft wird über
     * den Artikel, nicht die Gebindegröße: Ein 30-l- und ein 50-l-Fass desselben Biers
     * können nicht beide am Hahn hängen — so rechnet auch die App.
     */
    private fun conflictingOpenContainer(c: Connection, values: Map<String, Any?>): Row? {
        val def = Entities.byName.getValue("tapped_containers")
        return c.queryOne(
            """
            SELECT t.* FROM tapped_containers t
              JOIN container_types ct ON ct.id = t.container_type_id
             WHERE ct.stock_item_id = (SELECT stock_item_id FROM container_types WHERE id = ?)
               AND t.closed_at IS NULL AND NOT t.deleted
             ORDER BY t.opened_at
             LIMIT 1
            """.trimIndent(),
            values["container_type_id"]
        ) { rs -> readRow(def, rs) }
    }

    private fun Row.timestampOf(column: String): OffsetDateTime = values[column] as OffsetDateTime

    // ------------------------------------------------------------------ Salden

    /** Der abgeleitete Saldo aus der Sicht `member_balances`; null, wenn es das Mitglied nicht gibt. */
    fun balance(memberId: UUID): BigDecimal? = db.read { c ->
        c.queryOne(
            """
            SELECT b.balance FROM member_balances b
              JOIN members m ON m.id = b.member_id
             WHERE b.member_id = ? AND NOT m.deleted
            """.trimIndent(),
            memberId
        ) { it.getBigDecimal(1) }
    }

    // ------------------------------------------------------------------ Zeilen

    private fun decodeRow(def: EntityDef, json: JsonObject, partial: Boolean, what: String): Map<String, Any?> {
        val values = LinkedHashMap<String, Any?>()
        for ((key, element) in json) {
            if (key in Entities.serverOwned) continue
            if (key in Entities.derivedColumns) {
                throw Unprocessable("$what: '$key' ist abgeleitet und darf nicht geschrieben werden")
            }
            val column = def.byName[key] ?: throw Unprocessable("$what: unbekanntes Feld '$key'")
            values[key] = Values.decode(column, element, "$what: '$key'")
        }
        if (!partial) {
            for (column in def.columns) {
                if (column.name in values) continue
                when {
                    column.requiredOnInsert -> throw Unprocessable("$what: '${column.name}' fehlt")
                    column.default != null -> values[column.name] = column.default
                }
            }
        }
        return values
    }

    private fun readRow(c: Connection, def: EntityDef, id: UUID): Row? =
        c.queryOne("SELECT * FROM ${def.table} WHERE id = ?", id) { rs -> readRow(def, rs) }

    private fun readRow(def: EntityDef, rs: ResultSet): Row = Row(
        values = def.columns.associate { it.name to Values.read(it, rs) },
        seq = rs.getLong("server_seq"),
        updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java),
        deleted = rs.getBoolean("deleted"),
        deletedAt = rs.getObject("deleted_at", OffsetDateTime::class.java),
    )

    private fun encode(def: EntityDef, row: Row): JsonObject = buildJsonObject {
        for (column in def.columns) put(column.name, Values.encode(column, row.values[column.name]))
        put("updated_at", JsonPrimitive(Values.format(row.updatedAt)))
        row.deletedAt?.let { put("deleted_at", JsonPrimitive(Values.format(it))) }
    }

    private fun currentSeq(c: Connection): Long =
        c.queryOne("SELECT last_value, is_called FROM sync_seq") { rs ->
            if (rs.getBoolean("is_called")) rs.getLong("last_value") else 0L
        } ?: 0L
}
