package com.example.vereins_kassensystem.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Where a goods receipt came from. */
enum class StockEntrySource { MANUAL, SCAN, CORRECTION }

/**
 * A goods receipt — stock arriving, or a counted correction.
 *
 * Booked against a [StockItem] rather than a product: a delivery of beer arrives once
 * even though it feeds both the Helles and the Radler.
 *
 * Kept as its own table so a stock figure can always be traced back to what was booked in,
 * and corrections leave a trail instead of silently overwriting.
 */
@Entity(
    tableName = "stock_entries",
    indices = [Index("stockItemId"), Index("timestamp")]
)
data class StockEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stockItemId: Long,

    /** Snapshot, so history stays readable after an item is renamed or deleted. */
    val itemName: String,

    /**
     * How much arrived: containers for container-tracked items, units otherwise.
     * Negative for a correction.
     */
    val quantity: Double,

    /** What one unit of [quantity] was, e.g. "50 l Fass" or "Stk". */
    val unitLabel: String,

    /** What the delivery cost in total, if the receipt was to hand. */
    val totalCost: Double? = null,

    val note: String? = null,
    val source: StockEntrySource = StockEntrySource.MANUAL,
    val timestamp: Long = System.currentTimeMillis()
)
