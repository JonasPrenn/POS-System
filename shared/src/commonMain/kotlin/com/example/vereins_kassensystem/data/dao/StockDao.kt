package com.example.vereins_kassensystem.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.vereins_kassensystem.data.entity.ContainerType
import com.example.vereins_kassensystem.data.entity.ContainerTypeRow
import com.example.vereins_kassensystem.data.entity.ProductComponent
import com.example.vereins_kassensystem.data.entity.StockItem
import com.example.vereins_kassensystem.data.entity.StockItemRow
import com.example.vereins_kassensystem.data.entity.TappedContainer
import com.example.vereins_kassensystem.data.entity.TappedContainerRow
import kotlinx.coroutines.flow.Flow

/**
 * Der Keller. Gelesen werden die Lesemodelle aus :core mit ihren hergeleiteten Zahlen
 * (DerivedSql.kt), geschrieben die Zeilen. Zähler, die man fortschreiben könnte, gibt es
 * nicht mehr — wer Bestand ändern will, bucht einen Wareneingang oder einen Abgang.
 */
@Dao
interface StockDao {

    // ------------------------------------------------------------- stock items

    @Query("$STOCK_ITEM_SELECT WHERE s.deleted = 0 ORDER BY s.name ASC")
    fun getAllItems(): Flow<List<StockItem>>

    @Query("$STOCK_ITEM_SELECT WHERE s.id = :id AND s.deleted = 0")
    suspend fun getItem(id: String): StockItem?

    @Query("SELECT * FROM stock_items WHERE id = :id")
    suspend fun getItemRow(id: String): StockItemRow?

    @Insert
    suspend fun insertItem(item: StockItemRow)

    @Update
    suspend fun updateItem(item: StockItemRow)

    @Query("UPDATE stock_items SET deleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDeleteItem(id: String, now: Long)

    // -------------------------------------------------------- container types

    @Query("$CONTAINER_TYPE_SELECT WHERE c.deleted = 0 ORDER BY c.nominalSize DESC")
    fun getAllContainerTypes(): Flow<List<ContainerType>>

    @Query("SELECT * FROM container_types WHERE id = :id")
    suspend fun getContainerTypeRow(id: String): ContainerTypeRow?

    @Query("SELECT * FROM container_types WHERE stockItemId = :stockItemId AND deleted = 0")
    suspend fun getContainerTypeRowsFor(stockItemId: String): List<ContainerTypeRow>

    @Insert
    suspend fun insertContainerType(type: ContainerTypeRow)

    @Update
    suspend fun updateContainerType(type: ContainerTypeRow)

    @Query("UPDATE container_types SET deleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDeleteContainerType(id: String, now: Long)

    // ------------------------------------------------------ tapped containers

    /** Auch Anstiche gelöschter Gebindegrößen: Sie sind das Gedächtnis der Erträge. */
    @Query("$TAPPED_SELECT WHERE t.deleted = 0 ORDER BY t.openedAt DESC")
    fun getAllTapped(): Flow<List<TappedContainer>>

    @Query("SELECT * FROM tapped_containers WHERE id = :id")
    suspend fun getTappedRow(id: String): TappedContainerRow?

    @Insert
    suspend fun insertTapped(container: TappedContainerRow)

    @Update
    suspend fun updateTapped(container: TappedContainerRow)

    // ------------------------------------------------------ product components

    @Query("SELECT * FROM product_components WHERE deleted = 0")
    fun getAllComponents(): Flow<List<ProductComponent>>

    @Query("SELECT * FROM product_components WHERE productId = :productId AND deleted = 0")
    suspend fun getComponentsFor(productId: String): List<ProductComponent>

    @Query("SELECT * FROM product_components WHERE stockItemId = :stockItemId AND deleted = 0")
    suspend fun getComponentsUsing(stockItemId: String): List<ProductComponent>

    @Insert
    suspend fun insertComponent(component: ProductComponent)

    @Query("UPDATE product_components SET deleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDeleteComponent(id: String, now: Long)
}
