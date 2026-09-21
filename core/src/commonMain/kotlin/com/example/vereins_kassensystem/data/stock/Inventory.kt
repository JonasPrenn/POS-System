package com.example.vereins_kassensystem.data.stock

import com.example.vereins_kassensystem.data.entity.ContainerCloseReason
import com.example.vereins_kassensystem.data.entity.ContainerType
import com.example.vereins_kassensystem.data.entity.RecipeLine
import com.example.vereins_kassensystem.data.entity.StockItem
import com.example.vereins_kassensystem.data.entity.StockTracking
import com.example.vereins_kassensystem.data.entity.TappedContainer
import com.example.vereins_kassensystem.data.entity.isOpen
import kotlin.math.floor

/** A stock item together with everything needed to say how much of it is left. */
data class StockItemState(
    val item: StockItem,
    val containerTypes: List<ContainerType> = emptyList(),
    /** All tapped vessels for this item, open and closed. Closed ones are the memory. */
    val tapped: List<TappedContainer> = emptyList()
)

/** What a vessel size is currently believed to yield, and how sure that is. */
data class YieldEstimate(
    val containerType: ContainerType,
    val perContainer: Double,
    /** How many emptied vessels the estimate is based on. Zero means it is still a guess. */
    val observations: Int
) {
    val isLearned: Boolean get() = observations > 0
}

/**
 * Stock arithmetic across items, vessel sizes and recipes.
 *
 * Three things make this more than subtraction:
 *
 * **Yields are learned, not configured.** A keg does not give up its nominal volume, and
 * how much it does give up varies with the size, the tap and the cellar. Rather than
 * asking for a loss figure and trusting it, the app measures what emptied kegs actually
 * poured and averages that per size.
 *
 * **Spoiled vessels are excluded.** A keg that went warm was thrown away half full. That
 * is a loss to report, not a measurement of what the size yields — counting it would drag
 * every future forecast down permanently.
 *
 * **A product is limited by its scarcest ingredient.** A Radler needs beer *and* soda, so
 * how many are left is the minimum across the recipe, not a figure on the product.
 */
object Inventory {

    /**
     * What one vessel of this size is expected to yield.
     *
     * The configured estimate acts as a prior with weight one, so a single oddly-poured
     * keg cannot swing the forecast, while after a handful of deliveries the measurements
     * dominate:
     *
     * ```
     * yield = (estimate + sum of emptied yields) / (1 + count)
     * ```
     */
    fun yieldFor(type: ContainerType, tapped: List<TappedContainer>): YieldEstimate {
        val measured = tapped.filter {
            it.containerTypeId == type.id && it.closeReason == ContainerCloseReason.EMPTIED
        }
        val total = type.initialYieldEstimate + measured.sumOf { it.drawn }
        val divisor = 1 + measured.size
        return YieldEstimate(
            containerType = type,
            perContainer = (total / divisor).coerceAtLeast(0.0),
            observations = measured.size
        )
    }

    /** Yield estimates for every size this item comes in. */
    fun yields(state: StockItemState): List<YieldEstimate> =
        state.containerTypes.map { yieldFor(it, state.tapped) }

    /**
     * How much of the item is still available, in its own unit.
     *
     * Sums what is left in every open vessel plus the expected yield of every unopened
     * one. Goes negative when more was sold than the cellar held, which is allowed.
     */
    fun available(state: StockItemState): Double {
        if (state.item.tracking == StockTracking.SIMPLE) return state.item.simpleQuantity

        val estimates = yields(state).associateBy { it.containerType.id }

        val fromOpen = state.tapped
            .filter { it.isOpen }
            .sumOf { open ->
                val perContainer = estimates[open.containerTypeId]?.perContainer ?: 0.0
                perContainer - open.drawn
            }

        val fromFull = state.containerTypes.sumOf { type ->
            (estimates[type.id]?.perContainer ?: 0.0) * type.fullCount
        }

        return fromOpen + fromFull
    }

    /** Unopened vessels on hand, by size — what the Anstich dialog offers. */
    fun fullContainers(state: StockItemState): List<Pair<ContainerType, Int>> =
        state.containerTypes.filter { it.fullCount > 0 }.map { it to it.fullCount }

    /** The vessel currently on tap, if any. */
    fun openContainer(state: StockItemState): TappedContainer? =
        state.tapped.firstOrNull { it.isOpen }

    /**
     * How many of a product can still be made.
     *
     * The recipe is scaled by [servingSize], then each component is divided into what is
     * available; the smallest result wins, because that ingredient runs out first. A
     * product with no recipe is unlimited — nothing is being tracked for it.
     */
    fun servingsPossible(
        components: List<RecipeLine>,
        states: Map<String, StockItemState>,
        servingSize: Double
    ): Int? {
        if (components.isEmpty() || servingSize <= 0.0) return null

        val perServing = components.mapNotNull { component ->
            val state = states[component.stockItemId] ?: return@mapNotNull null
            val needed = component.quantityPerUnit * servingSize
            if (needed <= 0.0) null else floor(available(state) / needed).toInt()
        }
        return perServing.minOrNull()
    }

    /** Which recipe line is the binding constraint — the one to reorder first. */
    fun limitingItem(
        components: List<RecipeLine>,
        states: Map<String, StockItemState>,
        servingSize: Double
    ): StockItem? {
        if (components.isEmpty() || servingSize <= 0.0) return null
        return components
            .mapNotNull { component ->
                val state = states[component.stockItemId] ?: return@mapNotNull null
                val needed = component.quantityPerUnit * servingSize
                if (needed <= 0.0) null else state.item to (available(state) / needed)
            }
            .minByOrNull { it.second }
            ?.first
    }

    /**
     * Applies a sale: draws each recipe component from its item.
     *
     * Returns the volume to draw per stock item; the repository turns that into row
     * updates. Nothing here refuses to go negative — the queue at the counter wins over
     * the number in the cellar.
     */
    fun drawForSale(
        components: List<RecipeLine>,
        servingSize: Double,
        quantity: Int
    ): Map<String, Double> = components.associate { component ->
        component.stockItemId to component.quantityPerUnit * servingSize * quantity
    }

    /** Whether the item should be flagged as running low. */
    fun isLow(state: StockItemState): Boolean = available(state) <= state.item.minLevel

    /** Whether more has been sold than the cellar held. */
    fun isNegative(state: StockItemState): Boolean = available(state) < 0.0

    /**
     * Total volume thrown away as spoiled, for reporting. Kept separate from yield so a
     * bad batch shows up as a cost rather than quietly lowering every forecast.
     */
    fun spoiledVolume(state: StockItemState): Double =
        state.tapped
            .filter { it.closeReason == ContainerCloseReason.SPOILED }
            .sumOf { it.discardedVolume }
}
