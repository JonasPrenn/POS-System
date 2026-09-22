package com.example.vereins_kassensystem.data.entity

/**
 * Was jede abgeglichene Zeile zusätzlich trägt (Spezifikation 2.4 und 3.1).
 *
 * Gelöscht wird nicht mehr hart: Ein fehlender Datensatz lässt sich nicht übertragen, ein
 * als gelöscht markierter schon. Die Abfragen der DAOs blenden solche Zeilen aus.
 *
 * [serverUpdatedAt] ist der `updated_at` der Serverzeile, wie er zuletzt gezogen wurde —
 * als Zeichenkette, unverändert, weil er genau so als `base_updated_at` zurückgeht und der
 * Server daran erkennt, ob eine Änderung auf einem veralteten Stand aufsetzt. Null, solange
 * der Server die Zeile nicht kennt.
 */
data class SyncMeta(
    val deleted: Boolean = false,
    val deletedAt: Long? = null,
    val serverUpdatedAt: String? = null,
)
