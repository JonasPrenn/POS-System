package com.example.vereins_kassensystem.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Where a goods receipt came from. */
enum class StockEntrySource { MANUAL, SCAN, CORRECTION }

/**
 * A goods receipt — stock arriving, or a counted correction.
 *
 * Kept as its own table rather than just bumping the product's number, so the stock a
 * product shows can always be traced back to what was booked in and by which route.
 * Corrections are recorded the same way, with a negative [quantity], so a stocktake
 * leaves a trail instead of silently overwriting.
 */
@Entity(
    tableName = "stock_entries",
    indices = [Index("productId"), Index("timestamp")]
)
data class StockEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,

    /** Snapshot, so the history stays readable after a product is renamed or deleted. */
    val productName: String,

    /**
     * How much arrived: containers for [StockMode.BULK], pieces for [StockMode.PIECE].
     * Negative for a correction.
     */
    val quantity: Double,

    /** What one unit of [quantity] was, e.g. "Fass 30 l" or "Stk". */
    val unitLabel: String,

    /** What the delivery cost in total, if the receipt was to hand. */
    val totalCost: Double? = null,

    val note: String? = null,
    val source: StockEntrySource = StockEntrySource.MANUAL,
    val timestamp: Long = System.currentTimeMillis()
)
