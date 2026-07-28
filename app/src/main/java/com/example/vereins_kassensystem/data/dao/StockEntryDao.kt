package com.example.vereins_kassensystem.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.vereins_kassensystem.data.entity.StockEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface StockEntryDao {

    @Query("SELECT * FROM stock_entries ORDER BY timestamp DESC")
    fun getAllEntries(): Flow<List<StockEntry>>

    @Query("SELECT * FROM stock_entries WHERE productId = :productId ORDER BY timestamp DESC")
    fun getEntriesForProduct(productId: Long): Flow<List<StockEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: StockEntry): Long

    @Delete
    suspend fun deleteEntry(entry: StockEntry)
}
