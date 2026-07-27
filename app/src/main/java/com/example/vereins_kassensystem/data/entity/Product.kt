package com.example.vereins_kassensystem.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "products")
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val price: Double,
    val category: String,
    val imageUrl: String? = null,
    val hasVariants: Boolean = false,
    val stockQuantity: Int = 0,
    val minStockLevel: Int = 5,
    val trackInventory: Boolean = false
)
