package com.example.vereins_kassensystem

import com.example.vereins_kassensystem.ui.format.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Separator handling decides what a member actually gets charged, so it is pinned down
 * here rather than trusted.
 */
class MoneyTest {

    private fun assertParses(expected: Double, input: String) {
        val actual = Money.parse(input)
            ?: throw AssertionError("expected $input to parse, got null")
        assertEquals(input, expected, actual, 0.0001)
    }

    @Test
    fun `comma is a decimal separator`() {
        assertParses(12.50, "12,50")
        assertParses(0.99, "0,99")
    }

    @Test
    fun `a lone dot is a decimal separator, not a grouping mark`() {
        // The 100x bug: reading this as a thousands separator would charge 1250.
        assertParses(12.50, "12.50")
        assertParses(5.00, "5.00")
    }

    @Test
    fun `mixed separators use the later one as the decimal point`() {
        assertParses(1234.50, "1.234,50")
        assertParses(1234.50, "1,234.50")
    }

    @Test
    fun `repeated separators are treated as grouping`() {
        assertParses(1234.50, "1,234,50")
        assertParses(1234.50, "1.234.50")
    }

    @Test
    fun `plain integers and negatives`() {
        assertParses(1234.0, "1234")
        assertParses(-5.0, "-5,00")
        assertParses(-5.0, "−5,00") // real minus sign
    }

    @Test
    fun `formatted output round-trips back through parse`() {
        listOf(0.0, 0.99, 12.5, 1234.5, 99999.99).forEach { value ->
            assertParses(value, Money.format(value))
        }
    }

    @Test
    fun `junk does not silently become zero`() {
        assertNull(Money.parse(""))
        assertNull(Money.parse("   "))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse("12,,x"))
    }

    @Test
    fun `signed formatting shows direction`() {
        assertEquals("+", Money.formatSigned(5.0).take(1))
        assertEquals("−", Money.formatSigned(-5.0).take(1))
        assertEquals(Money.format(0.0), Money.formatSigned(0.0))
    }
}
