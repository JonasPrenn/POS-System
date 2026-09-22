package com.example.vereins_kassensystem.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.vereins_kassensystem.data.entity.Delivery
import kotlinx.coroutines.flow.Flow

@Dao
interface DeliveryDao {

    @Query("SELECT * FROM deliveries WHERE deleted = 0 ORDER BY timestamp DESC")
    fun getAllDeliveries(): Flow<List<Delivery>>

    @Query("SELECT * FROM deliveries WHERE id = :id")
    suspend fun getDelivery(id: String): Delivery?

    /** Belege, deren Foto der Server noch nicht hat. */
    @Query("SELECT * FROM deliveries WHERE deleted = 0 AND photoUri IS NOT NULL AND photoKey IS NULL")
    suspend fun getDeliveriesAwaitingUpload(): List<Delivery>

    @Insert
    suspend fun insertDelivery(delivery: Delivery)

    @Update
    suspend fun updateDelivery(delivery: Delivery)

    @Query("UPDATE deliveries SET deleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)
}
