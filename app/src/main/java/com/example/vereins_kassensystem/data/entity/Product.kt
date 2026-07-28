package com.example.vereins_kassensystem.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Something the counter sells.
 *
 * A product no longer carries a stock figure. What it consumes lives in its recipe
 * ([ProductComponent]) and what the cellar holds lives in [StockItem] — a Radler is one
 * product drawn from two different kegs, which a single number on the product could never
 * express.
 */
@Entity(tableName = "products")
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val price: Double,
    val category: String,
    val imageUrl: String? = null,
    val hasVariants: Boolean = false,

    /**
     * How many recipe units one sale of this product consumes, for products without
     * variants. A 0,5 l Helles is 0.5; a Bratwurst is 1.
     *
     * Variants override it, which is what lets one recipe serve every glass size.
     */
    val servingSize: Double = 1.0
)
