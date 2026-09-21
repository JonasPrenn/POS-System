package com.example.vereins_kassensystem.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.vereins_kassensystem.data.entity.ContainerTypeRow
import com.example.vereins_kassensystem.data.entity.Delivery
import com.example.vereins_kassensystem.data.entity.MemberCategory
import com.example.vereins_kassensystem.data.entity.MemberRow
import com.example.vereins_kassensystem.data.entity.PendingChange
import com.example.vereins_kassensystem.data.entity.Product
import com.example.vereins_kassensystem.data.entity.ProductComponent
import com.example.vereins_kassensystem.data.entity.ProductVariant
import com.example.vereins_kassensystem.data.entity.StockDraw
import com.example.vereins_kassensystem.data.entity.StockEntry
import com.example.vereins_kassensystem.data.entity.StockItemRow
import com.example.vereins_kassensystem.data.entity.SyncState
import com.example.vereins_kassensystem.data.entity.TappedContainerRow
import com.example.vereins_kassensystem.data.entity.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Was nur der Abgleich darf: die Warteschlange, sein Zustand, und der Rohzugriff auf alle
 * zwölf Tabellen — schreiben, was der Server sagt, ohne dass daraus wieder ein Auftrag
 * wird, und alles lesen, einschließlich der gelöschten Zeilen, für die Erstbefüllung.
 */
@Dao
interface SyncDao {

    // ---------------------------------------------------------- Warteschlange

    @Insert
    suspend fun enqueue(change: PendingChange)

    @Query("SELECT * FROM pending_changes ORDER BY seq ASC LIMIT :limit")
    suspend fun nextBatch(limit: Int): List<PendingChange>

    @Query("DELETE FROM pending_changes WHERE seq <= :seq")
    suspend fun removeUpTo(seq: Long)

    @Query("SELECT COUNT(*) FROM pending_changes")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_changes")
    suspend fun pendingCount(): Int

    @Query("DELETE FROM pending_changes")
    suspend fun clearQueue()

    // ---------------------------------------------------------------- Zustand

    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    suspend fun state(key: String): String?

    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    fun observeState(key: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putState(state: SyncState)

    @Query("DELETE FROM sync_state WHERE `key` = :key")
    suspend fun removeState(key: String)

    @Query("DELETE FROM sync_state")
    suspend fun clearState()

    // ------------------------------------------- Schreiben, was der Server sagt

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertCategory(row: MemberCategory)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertMember(row: MemberRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertProduct(row: Product)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertVariant(row: ProductVariant)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertStockItem(row: StockItemRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertContainerType(row: ContainerTypeRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertComponent(row: ProductComponent)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertDelivery(row: Delivery)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertStockEntry(row: StockEntry)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTapped(row: TappedContainerRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTransaction(row: Transaction)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertStockDraw(row: StockDraw)

    // ------------------------------------------------- Alles, für die Erstbefüllung

    @Query("SELECT * FROM member_categories") suspend fun allCategories(): List<MemberCategory>
    @Query("SELECT * FROM members") suspend fun allMembers(): List<MemberRow>
    @Query("SELECT * FROM products") suspend fun allProducts(): List<Product>
    @Query("SELECT * FROM product_variants") suspend fun allVariants(): List<ProductVariant>
    @Query("SELECT * FROM stock_items") suspend fun allStockItems(): List<StockItemRow>
    @Query("SELECT * FROM container_types") suspend fun allContainerTypes(): List<ContainerTypeRow>
    @Query("SELECT * FROM product_components") suspend fun allComponents(): List<ProductComponent>
    @Query("SELECT * FROM deliveries ORDER BY timestamp") suspend fun allDeliveries(): List<Delivery>
    @Query("SELECT * FROM stock_entries ORDER BY timestamp") suspend fun allStockEntries(): List<StockEntry>
    @Query("SELECT * FROM tapped_containers ORDER BY openedAt") suspend fun allTapped(): List<TappedContainerRow>
    @Query("SELECT * FROM transactions ORDER BY timestamp") suspend fun allTransactions(): List<Transaction>
    @Query("SELECT * FROM stock_draws ORDER BY timestamp") suspend fun allStockDraws(): List<StockDraw>

    // ---------------------------- Leeren, wenn ein Gerät den Serverstand übernimmt

    @Query("DELETE FROM member_categories") suspend fun wipeCategories()
    @Query("DELETE FROM members") suspend fun wipeMembers()
    @Query("DELETE FROM products") suspend fun wipeProducts()
    @Query("DELETE FROM product_variants") suspend fun wipeVariants()
    @Query("DELETE FROM stock_items") suspend fun wipeStockItems()
    @Query("DELETE FROM container_types") suspend fun wipeContainerTypes()
    @Query("DELETE FROM product_components") suspend fun wipeComponents()
    @Query("DELETE FROM deliveries") suspend fun wipeDeliveries()
    @Query("DELETE FROM stock_entries") suspend fun wipeStockEntries()
    @Query("DELETE FROM tapped_containers") suspend fun wipeTapped()
    @Query("DELETE FROM transactions") suspend fun wipeTransactions()
    @Query("DELETE FROM stock_draws") suspend fun wipeStockDraws()
}
