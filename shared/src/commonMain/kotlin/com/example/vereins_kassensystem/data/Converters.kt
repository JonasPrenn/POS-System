package com.example.vereins_kassensystem.data

import androidx.room.TypeConverter
import com.example.vereins_kassensystem.data.entity.CashMovementKind
import com.example.vereins_kassensystem.data.entity.ContainerCloseReason
import com.example.vereins_kassensystem.data.entity.StockEntrySource
import com.example.vereins_kassensystem.data.entity.StockTracking

/**
 * Enums are stored by name rather than ordinal, so reordering or inserting a constant
 * later cannot silently reinterpret existing rows.
 */
class Converters {

    @TypeConverter
    fun cashMovementKindToString(value: CashMovementKind): String = value.name

    @TypeConverter
    fun stringToCashMovementKind(value: String?): CashMovementKind =
        CashMovementKind.entries.firstOrNull { it.name == value } ?: CashMovementKind.WITHDRAWAL

    @TypeConverter
    fun stockTrackingToString(value: StockTracking): String = value.name

    @TypeConverter
    fun stringToStockTracking(value: String?): StockTracking =
        StockTracking.entries.firstOrNull { it.name == value } ?: StockTracking.SIMPLE

    @TypeConverter
    fun stockEntrySourceToString(value: StockEntrySource): String = value.name

    @TypeConverter
    fun stringToStockEntrySource(value: String?): StockEntrySource =
        StockEntrySource.entries.firstOrNull { it.name == value } ?: StockEntrySource.MANUAL

    /** Nullable: an open vessel has no close reason yet. */
    @TypeConverter
    fun closeReasonToString(value: ContainerCloseReason?): String? = value?.name

    @TypeConverter
    fun stringToCloseReason(value: String?): ContainerCloseReason? =
        value?.let { name -> ContainerCloseReason.entries.firstOrNull { it.name == name } }
}
