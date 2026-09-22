package com.example.vereins_kassensystem.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.vereins_kassensystem.data.entity.Product
import com.example.vereins_kassensystem.data.entity.ProductVariant
import kotlinx.coroutines.flow.Flow

/**
 * Ein Produkt mit seinen Varianten. Früher eine Room-Relation; die kann gelöschte Kinder
 * nicht ausblenden, deshalb setzt das Repository beides aus zwei Strömen zusammen.
 */
data class ProductWithVariants(
    val product: Product,
    val variants: List<ProductVariant>
)

@Dao
interface ProductDao {
    @Query("SELECT * FROM products WHERE deleted = 0 ORDER BY name ASC")
    fun getAllProducts(): Flow<List<Product>>

    @Query("SELECT * FROM product_variants WHERE deleted = 0 ORDER BY price ASC, name ASC")
    fun getAllVariants(): Flow<List<ProductVariant>>

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun getProduct(id: String): Product?

    @Query("SELECT * FROM product_variants WHERE productId = :productId AND deleted = 0")
    suspend fun getVariantsFor(productId: String): List<ProductVariant>

    @Insert
    suspend fun insertProduct(product: Product)

    @Update
    suspend fun updateProduct(product: Product)

    @Query("UPDATE products SET deleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDeleteProduct(id: String, now: Long)

    @Insert
    suspend fun insertVariant(variant: ProductVariant)

    @Query("UPDATE product_variants SET deleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDeleteVariant(id: String, now: Long)
}
