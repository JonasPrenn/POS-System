package com.example.vereins_kassensystem.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * How a [StockItem] is held.
 *
 * [SIMPLE] is a plain count — sausages in the freezer, pretzels in the basket.
 *
 * [CONTAINER] is held in vessels that are broached and run dry: kegs of beer, kegs of
 * soda. Several sizes can be in the cellar at once, and how much a vessel really gives
 * up is learned rather than assumed. See [ContainerType].
 */
enum class StockTracking { SIMPLE, CONTAINER }

/**
 * A Lagerartikel — something the cellar holds, as opposed to something the counter sells.
 *
 * The two were the same thing until now, which could not describe a Radler: it is one
 * product drawn from two different kegs. Products reach stock through a recipe
 * ([ProductComponent]) instead of owning a stock figure themselves.
 */
@Entity(tableName = "stock_items")
data class StockItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,

    /** "l", "kg", "Stk" — what quantities of this item are expressed in. */
    val unit: String = "Stk",

    val tracking: StockTracking = StockTracking.SIMPLE,

    /** On hand, for [StockTracking.SIMPLE] items. May go negative. */
    val simpleQuantity: Double = 0.0,

    /** Warn below this, expressed in [unit]. */
    val minLevel: Double = 0.0
)

/**
 * A size of vessel a [StockItem] arrives in — a 50 l keg, a 30 l keg, a 20 l keg.
 *
 * Several sizes coexist for the same item, and each has its own real yield: a 20 l keg
 * loses proportionally more to tapping and residue than a 50 l one, so they cannot share
 * a single loss figure.
 */
@Entity(
    tableName = "container_types",
    foreignKeys = [
        ForeignKey(
            entity = StockItem::class,
            parentColumns = ["id"],
            childColumns = ["stockItemId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("stockItemId")]
)
data class ContainerType(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stockItemId: Long,

    /** What people call it: "50 l Fass". */
    val label: String,

    /** What it says on the vessel. */
    val nominalSize: Double,

    /**
     * What one is expected to actually give up, before anything has been observed.
     *
     * Used as a prior with weight 1 rather than as the answer — see `Inventory`. Set it
     * roughly; real emptied kegs will outweigh it within a few deliveries.
     */
    val initialYieldEstimate: Double,

    /** Unopened vessels of this size on hand. */
    val fullCount: Int = 0
)

/** Why a vessel stopped being on tap. */
enum class ContainerCloseReason {
    /** Poured out normally. This is a real yield measurement. */
    EMPTIED,

    /**
     * Went off — stood too long, got warm, foamed out. The rest was thrown away.
     *
     * Deliberately **not** a yield measurement: counting a spoiled keg would teach the
     * app that this size only gives up what happened to be poured before it turned, and
     * every future forecast would be short.
     */
    SPOILED
}

/**
 * A vessel that has been broached: what has been drawn from it, and how it ended.
 *
 * Closed rows are the app's memory of what vessels of a size really yield.
 */
@Entity(
    tableName = "tapped_containers",
    foreignKeys = [
        ForeignKey(
            entity = ContainerType::class,
            parentColumns = ["id"],
            childColumns = ["containerTypeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("containerTypeId"), Index("openedAt")]
)
data class TappedContainer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val containerTypeId: Long,

    /** Volume drawn so far, in the stock item's unit. */
    val drawn: Double = 0.0,

    val openedAt: Long = System.currentTimeMillis(),
    val closedAt: Long? = null,
    val closeReason: ContainerCloseReason? = null,

    /** Estimated volume thrown away, recorded when closed as [ContainerCloseReason.SPOILED]. */
    val discardedVolume: Double = 0.0,

    val note: String? = null
) {
    val isOpen: Boolean get() = closedAt == null
}

/**
 * One line of a product's recipe: how much of a [StockItem] one unit of the product uses.
 *
 * Quantities are **per unit of serving**, and the variant's serving size scales them, so
 * a Radler is defined once as half beer and half soda and every glass size follows:
 *
 * ```
 * Radler = 0,5 Bier + 0,5 Soda
 *   0,3 l glass -> 0,15 Bier + 0,15 Soda
 *   0,5 l glass -> 0,25 Bier + 0,25 Soda
 * ```
 *
 * A plain product is simply a one-line recipe: Bratwurst uses 1 Bratwurst.
 */
@Entity(
    tableName = "product_components",
    foreignKeys = [
        ForeignKey(
            entity = Product::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = StockItem::class,
            parentColumns = ["id"],
            childColumns = ["stockItemId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("productId"), Index("stockItemId")]
)
data class ProductComponent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val stockItemId: Long,

    /** Share of one serving unit taken from this item — 0.5 for half a Radler. */
    val quantityPerUnit: Double
)
