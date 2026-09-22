package com.example.vereins_kassensystem

import com.example.vereins_kassensystem.data.entity.ContainerCloseReason
import com.example.vereins_kassensystem.data.entity.ContainerType
import com.example.vereins_kassensystem.data.entity.RecipeLine
import com.example.vereins_kassensystem.data.entity.StockItem
import com.example.vereins_kassensystem.data.entity.StockTracking
import com.example.vereins_kassensystem.data.entity.TappedContainer
import com.example.vereins_kassensystem.data.stock.Inventory
import com.example.vereins_kassensystem.data.stock.StockItemState
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * The cellar model: learned yields, several vessel sizes at once, and recipes drawing
 * from more than one item. Getting these wrong means promising beer that is not there.
 */
class InventoryTest {

    /** Rezeptzeile ohne Room: die Entität selbst wohnt in :shared. */
    private class Line(override val stockItemId: String, override val quantityPerUnit: Double) : RecipeLine

    private val beer = StockItem(id = "i1", name = "Bier", unit = "l", tracking = StockTracking.CONTAINER)
    private val soda = StockItem(id = "i2", name = "Soda", unit = "l", tracking = StockTracking.CONTAINER)

    private fun keg(id: Int, itemId: Int, size: Double, estimate: Double, full: Int = 0) =
        ContainerType(
            id = "t$id", stockItemId = "i$itemId", label = "$size l",
            nominalSize = size, initialYieldEstimate = estimate, fullCount = full
        )

    private fun emptied(typeId: Int, drawn: Double) = TappedContainer(
        containerTypeId = "t$typeId", drawn = drawn,
        closedAt = 1L, closeReason = ContainerCloseReason.EMPTIED
    )

    // ------------------------------------------------------------- learned yield

    @Test
    fun `with no history the configured estimate is used as is`() {
        val type = keg(1, 1, 50.0, estimate = 49.0)
        val estimate = Inventory.yieldFor(type, emptyList())

        assertEquals(49.0, estimate.perContainer, 0.0001)
        assertEquals(0, estimate.observations)
        assertTrue(!estimate.isLearned)
    }

    @Test
    fun `emptied kegs pull the estimate towards what was really poured`() {
        val type = keg(1, 1, 50.0, estimate = 49.0)
        // Three kegs that really gave 47 l each.
        val history = listOf(emptied(1, 47.0), emptied(1, 47.0), emptied(1, 47.0))

        val estimate = Inventory.yieldFor(type, history)

        // (49 + 47 + 47 + 47) / 4
        assertEquals(47.5, estimate.perContainer, 0.0001)
        assertEquals(3, estimate.observations)
        assertTrue(estimate.isLearned)
    }

    @Test
    fun `a single odd keg cannot swing the forecast because the estimate has weight`() {
        val type = keg(1, 1, 50.0, estimate = 49.0)
        val oneBadPour = listOf(emptied(1, 30.0))

        // Averaged with the prior rather than taken at face value: 39,5 not 30.
        assertEquals(39.5, Inventory.yieldFor(type, oneBadPour).perContainer, 0.0001)
    }

    @Test
    fun `a spoiled keg is a loss and not a measurement`() {
        val type = keg(1, 1, 50.0, estimate = 49.0)
        val spoiled = TappedContainer(
            containerTypeId = "t1", drawn = 12.0, closedAt = 1L,
            closeReason = ContainerCloseReason.SPOILED, discardedVolume = 37.0
        )

        val estimate = Inventory.yieldFor(type, listOf(spoiled))

        // Untouched by the ruined keg — otherwise the app would learn "50 l kegs give 12 l".
        assertEquals(49.0, estimate.perContainer, 0.0001)
        assertEquals(0, estimate.observations)

        val state = StockItemState(beer, listOf(type), listOf(spoiled))
        assertEquals(37.0, Inventory.spoiledVolume(state), 0.0001)
    }

    @Test
    fun `each size learns separately`() {
        val fifty = keg(1, 1, 50.0, estimate = 49.0)
        val twenty = keg(2, 1, 20.0, estimate = 19.2)
        val history = listOf(emptied(1, 47.0), emptied(2, 18.0), emptied(2, 18.0))

        assertEquals(48.0, Inventory.yieldFor(fifty, history).perContainer, 0.0001)
        assertEquals((19.2 + 36.0) / 3, Inventory.yieldFor(twenty, history).perContainer, 0.0001)
    }

    // -------------------------------------------------- several sizes at once

    @Test
    fun `stock adds up across different keg sizes held at the same time`() {
        val fifty = keg(1, 1, 50.0, estimate = 49.0, full = 2)
        val thirty = keg(2, 1, 30.0, estimate = 29.2, full = 1)
        val twenty = keg(3, 1, 20.0, estimate = 19.2, full = 4)

        val state = StockItemState(beer, listOf(fifty, thirty, twenty))

        // 2*49 + 1*29,2 + 4*19,2
        assertEquals(98.0 + 29.2 + 76.8, Inventory.available(state), 0.0001)
        assertEquals(3, Inventory.fullContainers(state).size)
    }

    @Test
    fun `an open keg counts only what is left in it`() {
        val fifty = keg(1, 1, 50.0, estimate = 49.0, full = 1)
        val onTap = TappedContainer(id = "tap9", containerTypeId = "t1", drawn = 20.0)

        val state = StockItemState(beer, listOf(fifty), listOf(onTap))

        // 49 - 20 still on tap, plus one full keg.
        assertEquals(29.0 + 49.0, Inventory.available(state), 0.0001)
        assertEquals("tap9", Inventory.openContainer(state)?.id)
    }

    // --------------------------------------------------------------- recipes

    @Test
    fun `a Radler is limited by whichever of beer or soda runs out first`() {
        // Plenty of beer, almost no soda.
        val beerState = StockItemState(beer, listOf(keg(1, 1, 50.0, 49.0, full = 2)))
        val sodaState = StockItemState(soda, listOf(keg(2, 2, 20.0, 19.2, full = 0)),
            listOf(TappedContainer(containerTypeId = "t2", drawn = 18.2)))  // 1,0 l left

        val states = mapOf("i1" to beerState, "i2" to sodaState)
        val radler = listOf(
            Line("i1", 0.5),
            Line("i2", 0.5)
        )

        // A 0,5 l Radler needs 0,25 l of each. Soda has 1,0 l -> 4 servings.
        assertEquals(4, Inventory.servingsPossible(radler, states, servingSize = 0.5))
        assertEquals("Soda", Inventory.limitingItem(radler, states, 0.5)?.name)
    }

    @Test
    fun `the recipe scales with the glass size`() {
        val beerState = StockItemState(beer, listOf(keg(1, 1, 50.0, 49.0, full = 0)),
            listOf(TappedContainer(containerTypeId = "t1", drawn = 40.0))) // 9,0 l left
        val states = mapOf("i1" to beerState)
        val helles = listOf(Line("i1", 1.0))

        assertEquals(18, Inventory.servingsPossible(helles, states, servingSize = 0.5))
        assertEquals(27, Inventory.servingsPossible(helles, states, servingSize = 0.33))
    }

    @Test
    fun `a sale draws every component of the recipe`() {
        val radler = listOf(
            Line("i1", 0.5),
            Line("i2", 0.5)
        )

        val draw = Inventory.drawForSale(radler, servingSize = 0.5, quantity = 3)

        assertEquals(0.75, draw["i1"]!!, 0.0001)
        assertEquals(0.75, draw["i2"]!!, 0.0001)
    }

    @Test
    fun `a product without a recipe is not stock limited`() {
        assertNull(Inventory.servingsPossible(emptyList(), emptyMap(), 0.5))
    }

    @Test
    fun `simple items are just a count and may go negative`() {
        val wurst = StockItem(id = "i3", name = "Bratwurst", unit = "Stk", simpleQuantity = -2.0)
        val state = StockItemState(wurst)

        assertEquals(-2.0, Inventory.available(state), 0.0001)
        assertTrue(Inventory.isNegative(state))
    }
}
