package com.example.vereins_kassensystem.data.stock

import com.example.vereins_kassensystem.data.entity.Product
import com.example.vereins_kassensystem.data.entity.ProductVariant
import com.example.vereins_kassensystem.data.entity.StockMode
import kotlin.math.floor

/**
 * Stock arithmetic, kept away from the UI and the database so it can be reasoned about
 * and tested on its own.
 *
 * ## Why bulk stock is not just a number
 *
 * A keg does not give up all its contents. Some is lost tapping it (Anstich), some when
 * it comes off (Abstich), and some is simply residue that will not come out. That loss
 * is paid **once per container**, which is why the same total volume pours a different
 * number of beers depending on how it was delivered:
 *
 * ```
 * 2 × 20 l kegs, 0,8 l lost each -> 2 × 19,2 = 38,4 l -> 76 × 0,5 l
 * 1 × 40 l keg,  0,8 l lost      -> 1 × 39,2 = 39,2 l -> 78 × 0,5 l
 * ```
 *
 * Two beers' difference for the same forty litres. The app therefore tracks unopened
 * containers and the contents of the open one separately, rather than a single volume.
 *
 * ## Overselling is allowed
 *
 * Nothing here refuses to go negative. A stock figure is a best guess about the cellar,
 * and a queue at the counter is real; blocking a sale because the number says zero would
 * be the app arguing with the room. Negative stock is surfaced as a warning instead.
 */
object Stock {

    /** Servable volume of one container, after its loss. Never negative. */
    fun servablePerContainer(product: Product): Double =
        (product.containerSize - product.containerLoss).coerceAtLeast(0.0)

    /** Everything that can still be poured: the open container plus the unopened ones. */
    fun servableVolume(product: Product): Double =
        product.openContainerRemaining + product.fullContainers * servablePerContainer(product)

    /** What one sale of this variant draws, falling back to the product's own size. */
    fun servingSizeFor(product: Product, variant: ProductVariant?): Double =
        variant?.servingSize ?: product.servingSize

    /**
     * How many more servings of [servingSize] are available.
     *
     * Negative when the product has been oversold, which is a real state here — the
     * figure then reads as how far into the red the cellar is.
     */
    fun servingsRemaining(product: Product, servingSize: Double): Int {
        if (servingSize <= 0.0) return 0
        return floor(servableVolume(product) / servingSize).toInt()
    }

    /** Servings remaining for a product's own serving size. Convenience for the UI. */
    fun servingsRemaining(product: Product): Int =
        servingsRemaining(product, product.servingSize)

    /**
     * Draws [volume] from the product, broaching further containers as needed.
     *
     * If the open container runs dry mid-pour the next one is broached and its loss is
     * realised then — the same order things happen at the tap.
     */
    fun draw(product: Product, volume: Double): Product {
        if (product.stockMode != StockMode.BULK) return product

        val perContainer = servablePerContainer(product)
        var remaining = product.openContainerRemaining - volume
        var full = product.fullContainers

        // Broach while we owe volume and there is something left to broach. Adding the
        // container's servable amount also nets off any debt from an earlier oversell.
        while (remaining < 0.0 && full > 0 && perContainer > 0.0) {
            full -= 1
            remaining += perContainer
        }

        return product.copy(fullContainers = full, openContainerRemaining = remaining)
    }

    /** Puts back what a cancelled or corrected sale had drawn. */
    fun release(product: Product, volume: Double): Product {
        if (product.stockMode != StockMode.BULK) return product
        return product.copy(openContainerRemaining = product.openContainerRemaining + volume)
    }

    /**
     * Books in [containers] full containers.
     *
     * If the product was oversold, the arriving stock settles that debt first — which is
     * what actually happens in the cellar when the delivery turns up.
     */
    fun receiveContainers(product: Product, containers: Int): Product {
        val perContainer = servablePerContainer(product)
        var full = product.fullContainers + containers
        var remaining = product.openContainerRemaining

        while (remaining < 0.0 && full > 0 && perContainer > 0.0) {
            full -= 1
            remaining += perContainer
        }

        return product.copy(fullContainers = full, openContainerRemaining = remaining)
    }

    /** Applies a goods receipt of [quantity] in whatever unit the product counts in. */
    fun receive(product: Product, quantity: Double): Product = when (product.stockMode) {
        StockMode.PIECE -> product.copy(
            stockQuantity = product.stockQuantity + quantity.toInt()
        )
        StockMode.BULK -> receiveContainers(product, quantity.toInt())
    }

    /**
     * Applies the stock effect of selling [quantity] of a product or variant.
     * Piece products lose pieces; bulk products lose the poured volume.
     */
    fun applySale(
        product: Product,
        variant: ProductVariant?,
        quantity: Int
    ): Product = when (product.stockMode) {
        StockMode.PIECE -> product.copy(stockQuantity = product.stockQuantity - quantity)
        StockMode.BULK -> draw(product, servingSizeFor(product, variant) * quantity)
    }

    /** Whether the product should be flagged as running low. */
    fun isLow(product: Product): Boolean {
        if (!product.trackInventory) return false
        return when (product.stockMode) {
            StockMode.PIECE -> product.stockQuantity <= product.minStockLevel
            StockMode.BULK -> servingsRemaining(product) <= product.minServingsLevel
        }
    }

    /** Whether the product is past empty and now selling into the red. */
    fun isNegative(product: Product): Boolean = when (product.stockMode) {
        StockMode.PIECE -> product.stockQuantity < 0
        StockMode.BULK -> servableVolume(product) < 0.0
    }
}
