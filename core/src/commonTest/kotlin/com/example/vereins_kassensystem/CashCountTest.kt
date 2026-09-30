package com.example.vereins_kassensystem

import com.example.vereins_kassensystem.data.CashCount
import com.example.vereins_kassensystem.ui.format.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Die gezählte Lade: Die Summe muss auf den Cent stimmen, sonst stimmt am Abend die Differenz nicht. */
class CashCountTest {

    @Test
    fun `the total is exact in cents`() {
        // Dreimal zehn Cent sind in Gleitkomma 0.30000000000000004 — hier dreißig Cent.
        val count = CashCount().with(10, 3)
        assertEquals(30L, count.totalCents)
        assertEquals(0.3, count.total)

        val drawer = CashCount()
            .with(5_000, 2)   // 100,00
            .with(100, 3)     //   3,00
            .with(10, 5)      //   0,50
            .with(1, 7)       //   0,07
        assertEquals(10_357L, drawer.totalCents)
        assertEquals("103,57 €", Money.format(drawer.total))
    }

    @Test
    fun `a new count replaces the old one and zero removes it`() {
        val count = CashCount().with(2_000, 4).with(2_000, 1)
        assertEquals(1, count.count(2_000))
        assertEquals(2_000L, count.totalCents)
        val cleared = count.with(2_000, 0)
        assertTrue(cleared.pieces.isEmpty())
        assertEquals(0L, cleared.totalCents)
    }

    @Test
    fun `only notes and coins of the drawer are counted`() {
        assertFailsWith<IllegalArgumentException> { CashCount().with(300, 1) }
        assertFailsWith<IllegalArgumentException> { CashCount().with(100, -1) }
        assertEquals(listOf(10_000, 5_000, 2_000, 1_000, 500), CashCount.DENOMINATIONS.filter { it >= CashCount.SMALLEST_NOTE })
        assertEquals(1, CashCount.DENOMINATIONS.last())
    }

    @Test
    fun `denominations have their everyday names`() {
        assertEquals(
            listOf("100 €", "50 €", "20 €", "10 €", "5 €", "2 €", "1 €", "50 ct", "20 ct", "10 ct", "5 ct", "2 ct", "1 ct"),
            CashCount.DENOMINATIONS.map(Money::denomination)
        )
    }
}
