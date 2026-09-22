package com.example.vereins_kassensystem.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.vereins_kassensystem.platform.nowMillis

/**
 * Eine Änderung, die der Server noch nicht kennt — die Warteschlange aus Kapitel 4.
 *
 * Entsteht in derselben Datenbanktransaktion wie die Änderung selbst: Entweder stehen
 * Buchung und Auftrag beide da oder keines von beiden. [seq] hält die Reihenfolge, in der
 * geschoben wird, damit ein Produkt vor seiner Variante beim Server ankommt; [changeId]
 * ist der Idempotenzschlüssel, mit dem ein Wiederholungsversuch nichts doppelt bucht.
 *
 * Nur auf diesem Gerät, wird nie abgeglichen — und entsteht erst gar nicht, solange das
 * Gerät nicht gekoppelt ist.
 */
@Entity(
    tableName = "pending_changes",
    indices = [Index(value = ["changeId"], unique = true)]
)
data class PendingChange(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val changeId: String,
    /** Tabellenname auf dem Server, z. B. `transactions`. */
    val entity: String,
    val entityId: String,
    /** `insert`, `update` oder `delete`. */
    val op: String,
    /** Die Zeile im Drahtformat, als JSON. */
    val payload: String,
    val baseUpdatedAt: String? = null,
    val createdAt: Long = nowMillis()
)

/** Kleiner Schlüssel-Wert-Speicher für den Abgleich: Lesezeiger, ob gekoppelt, letzter Lauf. */
@Entity(tableName = "sync_state")
data class SyncState(
    @PrimaryKey val key: String,
    val value: String
)
