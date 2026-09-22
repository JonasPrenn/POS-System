package com.example.vereins_kassensystem.data.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.vereins_kassensystem.platform.Ids
import com.example.vereins_kassensystem.platform.nowMillis

/** Where a goods receipt came from. */
enum class StockEntrySource { MANUAL, SCAN, CORRECTION }

/**
 * A goods receipt — stock arriving, or a counted correction.
 *
 * Booked against a [StockItem] rather than a product: a delivery of beer arrives once
 * even though it feeds both the Helles and the Radler.
 *
 * Kept as its own table so a stock figure can always be traced back to what was booked in,
 * and corrections leave a trail instead of silently overwriting. Seit Schema 11 ist die
 * Tabelle nicht mehr nur die Spur, sondern die Quelle: Der Bestand *ist* die Summe dieser
 * Zeilen minus die Lagerabgänge ([StockDraw]).
 */
@Entity(
    tableName = "stock_entries",
    indices = [Index("stockItemId"), Index("timestamp"), Index("deliveryId"), Index("containerTypeId")]
)
data class StockEntry(
    @PrimaryKey val id: String = Ids.new(),
    val stockItemId: String,

    /** Snapshot, so history stays readable after an item is renamed or deleted. */
    val itemName: String,

    /**
     * How much arrived: containers for container-tracked items, units otherwise.
     * Negative for a correction.
     */
    val quantity: Double,

    /** What one unit of [quantity] was, e.g. "50 l Fass" or "Stk". */
    val unitLabel: String,

    /** What this line cost on the receipt, if it was to hand. */
    val totalCost: Double? = null,

    val note: String? = null,
    val source: StockEntrySource = StockEntrySource.MANUAL,
    val timestamp: Long = nowMillis(),

    /**
     * The receipt this line came from, if any.
     *
     * Null for a standalone stocktake correction, which belongs to no delivery.
     */
    val deliveryId: String? = null,

    /**
     * Welche Gebindegröße ankam; null bei Stückware. Früher stand die Größe nur als Text in
     * [unitLabel] — für eine Spur genug, für eine Herleitung der vollen Gebinde nicht.
     */
    val containerTypeId: String? = null,

    @Embedded val sync: SyncMeta = SyncMeta()
)
