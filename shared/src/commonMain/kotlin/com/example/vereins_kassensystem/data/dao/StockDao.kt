package com.example.vereins_kassensystem.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.vereins_kassensystem.data.entity.ContainerType
import com.example.vereins_kassensystem.data.entity.ProductComponent
import com.example.vereins_kassensystem.data.entity.StockItem
import com.example.vereins_kassensystem.data.entity.TappedContainer
import kotlinx.coroutines.flow.Flow

@Dao
interface StockDao {

    // ------------------------------------------------------------- stock items

    @Query("SELECT * FROM stock_items ORDER BY name ASC")
    fun getAllItems(): Flow<List<StockItem>>

    @Query("SELECT * FROM stock_items WHERE id = :id")
    suspend fun getItem(id: Long): StockItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: StockItem): Long

    @Update
    suspend fun updateItem(item: StockItem)

    @Delete
    suspend fun deleteItem(item: StockItem)

    @Query("UPDATE stock_items SET simpleQuantity = simpleQuantity + :delta WHERE id = :id")
    suspend fun addSimpleQuantity(id: Long, delta: Double)

    // -------------------------------------------------------- container types

    @Query("SELECT * FROM container_types ORDER BY nominalSize DESC")
    fun getAllContainerTypes(): Flow<List<ContainerType>>

    @Query("SELECT * FROM container_types WHERE id = :id")
    suspend fun getContainerType(id: Long): ContainerType?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContainerType(type: ContainerType): Long

    @Update
    suspend fun updateContainerType(type: ContainerType)

    @Delete
    suspend fun deleteContainerType(type: ContainerType)

    @Query("UPDATE container_types SET fullCount = fullCount + :delta WHERE id = :id")
    suspend fun addFullCount(id: Long, delta: Int)

    // ------------------------------------------------------ tapped containers

    @Query("SELECT * FROM tapped_containers ORDER BY openedAt DESC")
    fun getAllTapped(): Flow<List<TappedContainer>>

    @Query(
        """
        SELECT t.* FROM tapped_containers t
        JOIN container_types c ON c.id = t.containerTypeId
        WHERE c.stockItemId = :stockItemId AND t.closedAt IS NULL
        LIMIT 1
        """
    )
    suspend fun getOpenContainerFor(stockItemId: Long): TappedContainer?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTapped(container: TappedContainer): Long

    @Update
    suspend fun updateTapped(container: TappedContainer)

    @Query("UPDATE tapped_containers SET drawn = drawn + :delta WHERE id = :id")
    suspend fun addDrawn(id: Long, delta: Double)

    // ------------------------------------------------------ product components

    @Query("SELECT * FROM product_components")
    fun getAllComponents(): Flow<List<ProductComponent>>

    @Query("SELECT * FROM product_components WHERE productId = :productId")
    suspend fun getComponentsFor(productId: Long): List<ProductComponent>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComponent(component: ProductComponent): Long

    @Query("DELETE FROM product_components WHERE productId = :productId")
    suspend fun deleteComponentsFor(productId: Long)

    @Transaction
    suspend fun replaceComponents(productId: Long, components: List<ProductComponent>) {
        deleteComponentsFor(productId)
        components.forEach { insertComponent(it.copy(id = 0, productId = productId)) }
    }
}
