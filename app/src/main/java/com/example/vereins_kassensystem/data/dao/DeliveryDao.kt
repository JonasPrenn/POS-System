package com.example.vereins_kassensystem.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.vereins_kassensystem.data.entity.Delivery
import kotlinx.coroutines.flow.Flow

@Dao
interface DeliveryDao {

    @Query("SELECT * FROM deliveries ORDER BY timestamp DESC")
    fun getAllDeliveries(): Flow<List<Delivery>>

    @Query("SELECT * FROM deliveries WHERE id = :id")
    suspend fun getDelivery(id: Long): Delivery?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDelivery(delivery: Delivery): Long

    @Update
    suspend fun updateDelivery(delivery: Delivery)

    @Delete
    suspend fun deleteDelivery(delivery: Delivery)

    /** Sum actually booked as stock, to compare against what the receipt says. */
    @Query("SELECT COALESCE(SUM(totalCost), 0) FROM stock_entries WHERE deliveryId = :deliveryId")
    suspend fun bookedTotal(deliveryId: Long): Double
}
