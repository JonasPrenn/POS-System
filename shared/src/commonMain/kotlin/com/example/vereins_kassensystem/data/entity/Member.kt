package com.example.vereins_kassensystem.data.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.vereins_kassensystem.platform.Ids

/**
 * Ein Mitglied, wie die Oberfläche es sieht: Stammdaten plus zwei hergeleitete Angaben.
 *
 * [balance] wird seit Schema 11 nicht mehr gespeichert, sondern aus den Buchungen
 * berechnet (Spezifikation 2.2) — ein fortgeschriebener Zähler verliert Buchungen, sobald
 * zwei Theken gleichzeitig kassieren. Die Regel steht in `Ledger` und, als SQL, in
 * `MemberDao`; ein Test hält beide beieinander.
 *
 * [lastUsedTimestamp] ist die Zeit der letzten Buchung des Mitglieds. Früher wurde dafür
 * bei jedem Verkauf der Mitgliedssatz geändert, was mit zwei Geräten laufend
 * Stammdatenkonflikte ergäbe.
 *
 * Die Tabellenzeile selbst ist [MemberRow].
 */
data class Member(
    val id: String = Ids.new(),
    val name: String,
    val balance: Double = 0.0,
    val categoryId: String? = null,
    val lastUsedTimestamp: Long = 0L
)

@Entity(
    tableName = "members",
    indices = [Index("categoryId")]
)
data class MemberRow(
    @PrimaryKey val id: String = Ids.new(),
    val name: String,
    val categoryId: String? = null,
    @Embedded val sync: SyncMeta = SyncMeta()
)
