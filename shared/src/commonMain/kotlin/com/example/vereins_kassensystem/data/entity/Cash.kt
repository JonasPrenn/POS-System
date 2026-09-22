package com.example.vereins_kassensystem.data.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.vereins_kassensystem.platform.Ids
import com.example.vereins_kassensystem.platform.nowMillis

/**
 * Eine Schicht an der Kasse dieses Geräts (Konzept 4.5): Wechselgeld gezählt beim Öffnen,
 * Bestand gezählt beim Schließen. Was dazwischen in der Lade sein müsste, rechnet
 * `CashDao.cashInSince` aus den Barbuchungen dieses Geräts — die Zeile hier speichert nur,
 * was jemand gezählt hat.
 *
 * Synchronisiert: Die Verwaltung führt daraus das Kassenbuch. Geschrieben wird die Zeile nur
 * von dem Gerät, das die Schicht hat; ein Konflikt kommt so nicht vor.
 */
@Entity(tableName = "cash_sessions", indices = [Index("openedAt")])
data class CashSession(
    @PrimaryKey val id: String = Ids.new(),
    val deviceLabel: String,
    val openedAt: Long = nowMillis(),
    val openedBy: String,
    val openingCount: Double,
    val closedAt: Long? = null,
    val closedBy: String? = null,
    val closingCount: Double? = null,
    val note: String? = null,
    /** Bardienst ohne Barkasse: niemand zählt, und die Theke nimmt kein Bargeld — nur Deckel und Karte. */
    val cashless: Boolean = false,
    @Embedded val sync: SyncMeta = SyncMeta()
) {
    val isOpen: Boolean get() = closedAt == null
}

enum class CashMovementKind { WITHDRAWAL, DEPOSIT }

/** Geld aus der Lade oder hinein, außerhalb eines Verkaufs: zur Bank, für einen Bareinkauf, Wechselgeld nachgelegt. */
@Entity(tableName = "cash_movements", indices = [Index("sessionId"), Index("timestamp")])
data class CashMovement(
    @PrimaryKey val id: String = Ids.new(),
    val sessionId: String,
    val kind: CashMovementKind,
    val amount: Double,
    val reason: String,
    val byName: String,
    val timestamp: Long = nowMillis(),
    @Embedded val sync: SyncMeta = SyncMeta()
)
