package com.example.vereins_kassensystem.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * How a product's stock is counted.
 *
 * [PIECE] is the simple case: a Bratwurst leaves the fridge one at a time.
 *
 * [BULK] is for anything drawn from a container — beer from a keg, wine from a box.
 * One sale draws a *volume*, not a piece, and each container carries a loss, so the
 * maths is genuinely different. See `Stock.kt`.
 */
enum class StockMode { PIECE, BULK }

@Entity(tableName = "products")
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val price: Double,
    val category: String,
    val imageUrl: String? = null,
    val hasVariants: Boolean = false,

    /** Pieces on hand. Only meaningful when [stockMode] is [StockMode.PIECE]. */
    val stockQuantity: Int = 0,
    val minStockLevel: Int = 5,
    val trackInventory: Boolean = false,

    val stockMode: StockMode = StockMode.PIECE,

    /** What the stock is measured in: "Stk", "l", "kg". Display only. */
    val stockUnit: String = "Stk",

    /** Volume of one full container, e.g. 30.0 for a 30-litre keg. BULK only. */
    val containerSize: Double = 0.0,

    /**
     * Volume lost per container that can never be sold — Anstich, Abstich and the
     * residue left in the keg. This is why two 20-litre kegs pour fewer beers than one
     * 40-litre keg: the loss is paid once per container.
     */
    val containerLoss: Double = 0.0,

    /**
     * How much one sale draws, for products without variants. A 0.5 l Helles draws 0.5.
     * Products with variants carry this per variant instead.
     */
    val servingSize: Double = 1.0,

    /** Unopened containers on hand. BULK only. */
    val fullContainers: Int = 0,

    /** What is left in the container currently on tap, already net of its loss. BULK only. */
    val openContainerRemaining: Double = 0.0,

    /** Warn below this many servings remaining. BULK only. */
    val minServingsLevel: Int = 20
)
