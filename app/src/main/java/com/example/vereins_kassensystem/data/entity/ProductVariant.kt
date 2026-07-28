package com.example.vereins_kassensystem.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "product_variants",
    foreignKeys = [
        ForeignKey(
            entity = Product::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("productId")]
)
data class ProductVariant(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val name: String, // e.g., "0.3l", "0.5l"
    val price: Double,

    /**
     * How much of the product's stock one of these draws.
     *
     * For draught beer this is the glass size in litres: a "0,3l" variant draws 0.33,
     * a "0,5l" draws 0.5. Null falls back to the product's own serving size, which is
     * what piece-counted products want.
     */
    val servingSize: Double? = null
)
