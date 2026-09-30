package com.example.vereins_kassensystem.platform

import com.sumup.merchant.reader.api.SumUpAPI.Response.ResultCode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Jede Antwort von SumUp wird etwas, das der Kassier lesen kann — und das Trinkgeld ist, was SumUp belastet hat. */
class SumUpOutcomeTest {

    @Test
    fun `a successful payment books the tip SumUp reports, or the one the app sent`() {
        assertEquals(PaymentResult.Success("TX1", 1.5), SumUpOutcome.checkout(ResultCode.SUCCESSFUL, "Transaction successful.", "TX1", reportedTip = 1.5, requestedTip = 0.0))
        assertEquals(PaymentResult.Success("TX2", 0.5), SumUpOutcome.checkout(ResultCode.SUCCESSFUL, null, "TX2", reportedTip = null, requestedTip = 0.5))
        assertEquals(PaymentResult.Success("TX3", 0.0), SumUpOutcome.checkout(ResultCode.SUCCESSFUL, null, "TX3", reportedTip = null, requestedTip = 0.0))
        // Rechenstaub aus dem SDK wird Cent.
        assertEquals(PaymentResult.Success("TX4", 2.6), SumUpOutcome.checkout(ResultCode.SUCCESSFUL, null, "TX4", reportedTip = 2.6000000000000001, requestedTip = 0.0))
    }

    @Test
    fun `every other answer is shown, says nothing was booked, and keeps SumUp's own words`() {
        val codes = listOf(
            ResultCode.ERROR_TRANSACTION_FAILED to "nicht durchgegangen",
            ResultCode.ERROR_GEOLOCATION_REQUIRED to "Standort",
            ResultCode.ERROR_INVALID_PARAM to "Angaben",
            ResultCode.ERROR_INVALID_TOKEN to "neu anmelden",
            ResultCode.ERROR_NO_CONNECTIVITY to "Kein Internet",
            ResultCode.ERROR_PERMISSION_DENIED to "Berechtigung",
            ResultCode.ERROR_NOT_LOGGED_IN to "Nicht bei SumUp angemeldet",
            ResultCode.ERROR_DUPLICATE_FOREIGN_TX_ID to "noch einmal bezahlen",
            ResultCode.ERROR_INVALID_AFFILIATE_KEY to "Schlüssel ist ungültig",
            ResultCode.ERROR_INVALID_AMOUNT_DECIMALS to "Nachkommastellen",
            ResultCode.ERROR_API_LEVEL_TOO_LOW to "zu alt",
            ResultCode.ERROR_CARD_READER_SETTINGS_OFF to "Bluetooth oder Standort ist aus",
            ResultCode.ERROR_UNKNOWN_TRANSACTION_STATUS to "im SumUp-Konto nachsehen",
            99 to "Code 99",
        )
        for ((code, phrase) in codes) {
            val result = assertIs<PaymentResult.Failed>(SumUpOutcome.checkout(code, "Some SumUp text", null, reportedTip = 1.0, requestedTip = 1.0), "Code $code")
            assertContains(result.message, phrase, message = "Code $code")
            assertContains(result.message, "Nichts gebucht", message = "Code $code")
            assertTrue(result.message.endsWith("(SumUp: Some SumUp text)"), "Code $code: ${result.message}")
        }
        assertEquals(SumUpOutcome.NOT_LOGGED_IN, SumUpOutcome.checkoutMessage(ResultCode.ERROR_NOT_LOGGED_IN, null))
    }

    @Test
    fun `the login says why it did not work`() {
        assertTrue(SumUpOutcome.login(loggedIn = true, code = null, message = null).isSuccess)
        assertEquals("SumUp-Anmeldung abgebrochen.", SumUpOutcome.login(loggedIn = false, code = null, message = null).exceptionOrNull()?.message)
        assertContains(SumUpOutcome.login(false, ResultCode.ERROR_INVALID_AFFILIATE_KEY, "Invalid key").exceptionOrNull()?.message.orEmpty(), "Schlüssel ist ungültig")
        assertContains(SumUpOutcome.login(false, ResultCode.ERROR_NO_CONNECTIVITY, null).exceptionOrNull()?.message.orEmpty(), "Kein Internet")
        assertContains(SumUpOutcome.login(false, 42, "boom").exceptionOrNull()?.message.orEmpty(), "(SumUp: boom)")
    }
}
