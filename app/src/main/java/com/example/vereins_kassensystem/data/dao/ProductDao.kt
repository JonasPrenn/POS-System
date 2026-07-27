package com.example.vereins_kassensystem.data.dao

import androidx.room.*
import com.example.vereins_kassensystem.data.entity.Product
import com.example.vereins_kassensystem.data.entity.ProductVariant
import kotlinx.coroutines.flow.Flow

data class ProductWithVariants(
    @Embedded val product: Product,
    @Relation(
        parentColumn = "id",
        entityColumn = "productId"
    )
    val variants: List<ProductVariant>
)

@Dao
interface ProductDao {
    @Transaction
    @Query("SELECT * FROM products ORDER BY name ASC")
    fun getAllProductsWithVariants(): Flow<List<ProductWithVariants>>

    @Query("SELECT * FROM products ORDER BY name ASC")
    fun getAllProducts(): Flow<List<Product>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProduct(product: Product): Long

    @Update
    suspend fun updateProduct(product: Product)

    @Delete
    suspend fun deleteProduct(product: Product)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVariant(variant: ProductVariant)

    @Update
    suspend fun updateVariant(variant: ProductVariant)

    @Delete
    suspend fun deleteVariant(variant: ProductVariant)

    @Query("DELETE FROM product_variants WHERE productId = :productId")
    suspend fun deleteVariantsForProduct(productId: Long)
}
