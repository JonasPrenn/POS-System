package com.example.vereins_kassensystem.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.vereins_kassensystem.data.entity.StockDraw
import com.example.vereins_kassensystem.data.entity.StockEntry
import kotlinx.coroutines.flow.Flow

/** Wareneingänge und Lagerabgänge — beide nur anfügend, zusammen ergeben sie den Bestand. */
@Dao
interface StockEntryDao {

    @Query("SELECT * FROM stock_entries WHERE deleted = 0 ORDER BY timestamp DESC")
    fun getAllEntries(): Flow<List<StockEntry>>

    @Insert
    suspend fun insertEntry(entry: StockEntry)

    @Insert
    suspend fun insertDraw(draw: StockDraw)
}
