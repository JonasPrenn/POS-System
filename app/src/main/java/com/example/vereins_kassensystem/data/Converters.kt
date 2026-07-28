package com.example.vereins_kassensystem.data

import androidx.room.TypeConverter
import com.example.vereins_kassensystem.data.entity.StockEntrySource
import com.example.vereins_kassensystem.data.entity.StockMode

/**
 * Enums are stored by name rather than ordinal, so reordering or inserting a constant
 * later cannot silently reinterpret existing rows.
 */
class Converters {

    @TypeConverter
    fun stockModeToString(value: StockMode): String = value.name

    @TypeConverter
    fun stringToStockMode(value: String?): StockMode =
        StockMode.entries.firstOrNull { it.name == value } ?: StockMode.PIECE

    @TypeConverter
    fun stockEntrySourceToString(value: StockEntrySource): String = value.name

    @TypeConverter
    fun stringToStockEntrySource(value: String?): StockEntrySource =
        StockEntrySource.entries.firstOrNull { it.name == value } ?: StockEntrySource.MANUAL
}
