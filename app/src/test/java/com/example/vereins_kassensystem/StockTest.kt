package com.example.vereins_kassensystem

import com.example.vereins_kassensystem.data.entity.Product
import com.example.vereins_kassensystem.data.entity.ProductVariant
import com.example.vereins_kassensystem.data.entity.StockMode
import com.example.vereins_kassensystem.data.stock.Stock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cellar maths. Getting this wrong means telling a volunteer there is beer when
 * there is not, so the container-loss behaviour is pinned down rather than trusted.
 */
class StockTest {

    private fun keg(
        containerSize: Double,
        loss: Double = 0.8,
        full: Int = 0,
        open: Double = 0.0
    ) = Product(
        id = 1, name = "Helles", price = 0.0, category = "Getränke",
        hasVariants = true, trackInventory = true,
        stockMode = StockMode.BULK, stockUnit = "l",
        containerSize = containerSize, containerLoss = loss,
        servingSize = 0.5, fullContainers = full, openContainerRemaining = open
    )

    @Test
    fun `two small kegs pour fewer beers than one large keg of the same volume`() {
        // The case that motivated the whole model: loss is paid once per container.
        val twoTwenties = keg(containerSize = 20.0, full = 2)
        val oneForty = keg(containerSize = 40.0, full = 1)

        assertEquals(38.4, Stock.servableVolume(twoTwenties), 0.0001)
        assertEquals(39.2, Stock.servableVolume(oneForty), 0.0001)

        assertEquals(76, Stock.servingsRemaining(twoTwenties, 0.5))
        assertEquals(78, Stock.servingsRemaining(oneForty, 0.5))
    }

    @Test
    fun `serving size comes from the variant when it has one`() {
        val product = keg(30.0, full = 1)
        val small = ProductVariant(id = 1, productId = 1, name = "0,3l", price = 3.2, servingSize = 0.33)
        val large = ProductVariant(id = 2, productId = 1, name = "0,5l", price = 4.2, servingSize = 0.5)

        assertEquals(0.33, Stock.servingSizeFor(product, small), 0.0001)
        assertEquals(0.5, Stock.servingSizeFor(product, large), 0.0001)
        // No variant falls back to the product's own size.
        assertEquals(0.5, Stock.servingSizeFor(product, null), 0.0001)
    }

    @Test
    fun `a 0,3l pour draws 0,33 litres, not one piece`() {
        val product = keg(30.0, full = 1)
        val small = ProductVariant(id = 1, productId = 1, name = "0,3l", price = 3.2, servingSize = 0.33)

        val after = Stock.applySale(product, small, quantity = 3)

        // One container broached: 29,2 servable, less 3 x 0,33.
        assertEquals(29.2 - 0.99, Stock.servableVolume(after), 0.0001)
        assertEquals(0, after.fullContainers)
    }

    @Test
    fun `running the open container dry broaches the next one and pays its loss`() {
        // 1 litre left on tap, one spare keg.
        val product = keg(containerSize = 30.0, loss = 0.8, full = 1, open = 1.0)

        val after = Stock.draw(product, 2.0) // more than the open keg holds

        assertEquals(0, after.fullContainers)
        // 1,0 + 29,2 - 2,0
        assertEquals(28.2, after.openContainerRemaining, 0.0001)
    }

    @Test
    fun `selling past empty is allowed and goes negative`() {
        val product = keg(containerSize = 30.0, full = 0, open = 0.5)

        val after = Stock.draw(product, 2.0)

        assertTrue("overselling must be possible", Stock.isNegative(after))
        assertEquals(-1.5, Stock.servableVolume(after), 0.0001)
        assertEquals(-3, Stock.servingsRemaining(after, 0.5))
    }

    @Test
    fun `a delivery settles an oversold debt first`() {
        val oversold = keg(containerSize = 30.0, full = 0, open = -1.5)

        val after = Stock.receiveContainers(oversold, containers = 1)

        assertEquals(0, after.fullContainers)          // broached to clear the debt
        assertEquals(29.2 - 1.5, after.openContainerRemaining, 0.0001)
        assertFalse(Stock.isNegative(after))
    }

    @Test
    fun `piece products just count pieces`() {
        val wurst = Product(
            id = 2, name = "Bratwurst", price = 3.5, category = "Küche",
            trackInventory = true, stockMode = StockMode.PIECE, stockQuantity = 10
        )

        assertEquals(7, Stock.applySale(wurst, null, quantity = 3).stockQuantity)
        assertEquals(20, Stock.receive(wurst, 10.0).stockQuantity)
        // And they may also go negative.
        assertTrue(Stock.isNegative(Stock.applySale(wurst, null, quantity = 12)))
    }

    @Test
    fun `low warning uses servings for bulk and pieces for piece products`() {
        val nearlyEmpty = keg(containerSize = 30.0, full = 0, open = 4.0) // 8 x 0,5l
            .copy(minServingsLevel = 20)
        assertTrue(Stock.isLow(nearlyEmpty))

        val plenty = keg(containerSize = 30.0, full = 2).copy(minServingsLevel = 20)
        assertFalse(Stock.isLow(plenty))

        // Untracked products never warn, whatever the number says.
        assertFalse(Stock.isLow(nearlyEmpty.copy(trackInventory = false)))
    }

    @Test
    fun `a container that loses more than it holds yields nothing rather than negative`() {
        val broken = keg(containerSize = 0.5, loss = 2.0, full = 3)
        assertEquals(0.0, Stock.servablePerContainer(broken), 0.0001)
        assertEquals(0, Stock.servingsRemaining(broken, 0.5))
    }
}
