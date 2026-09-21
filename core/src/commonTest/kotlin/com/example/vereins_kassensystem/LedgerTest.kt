package com.example.vereins_kassensystem

import com.example.vereins_kassensystem.data.Ledger
import kotlin.test.Test
import kotlin.test.assertEquals

/** Die Saldoregel entscheidet, was ein Mitglied schuldet — deshalb festgenagelt. */
class LedgerTest {

    private fun assertEffect(expected: Double, actual: Double, what: String) =
        assertEquals(expected, actual, 0.0001, what)

    @Test
    fun `a top-up credits the balance regardless of payment type`() {
        assertEffect(20.0, Ledger.balanceEffect(Ledger.TOPUP_REF, "CASH", 20.0, 1), "bar")
        assertEffect(20.0, Ledger.balanceEffect(Ledger.TOPUP_REF, "CARD", 20.0, 1), "karte")
        assertEffect(-5.0, Ledger.balanceEffect(Ledger.TOPUP_REF, "CORRECTION", -5.0, 1), "korrektur")
    }

    @Test
    fun `a sale on the Deckel debits price times quantity minus discount`() {
        val effect = Ledger.balanceEffect("018f-product", Ledger.MEMBER_BALANCE, 4.2, 2, discountAmount = 0.4)
        assertEffect(-8.0, effect, "zwei Bier mit 40 Cent Rabatt")
    }

    @Test
    fun `cash and card sales leave the Deckel alone`() {
        assertEffect(0.0, Ledger.balanceEffect("018f-product", "CASH", 4.2, 1), "bar")
        assertEffect(0.0, Ledger.balanceEffect("018f-product", "CARD", 4.2, 1), "karte")
        assertEffect(0.0, Ledger.balanceEffect(Ledger.TIP_REF, "CASH", 1.0, 1), "trinkgeld bar")
    }

    @Test
    fun `a refund reverses the sign`() {
        assertEffect(4.2, Ledger.balanceEffect("018f-product", Ledger.MEMBER_BALANCE, 4.2, 1, isRefund = true), "storno")
        assertEffect(-20.0, Ledger.balanceEffect(Ledger.TOPUP_REF, "CASH", 20.0, 1, isRefund = true), "aufladung storniert")
    }
}
