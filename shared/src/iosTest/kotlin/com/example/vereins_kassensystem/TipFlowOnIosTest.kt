package com.example.vereins_kassensystem

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.example.vereins_kassensystem.data.Ledger
import com.example.vereins_kassensystem.data.entity.Product
import com.example.vereins_kassensystem.data.entity.Transaction
import com.example.vereins_kassensystem.data.repository.AppRepository
import com.example.vereins_kassensystem.platform.BackupScheduler
import com.example.vereins_kassensystem.platform.PaymentProcessor
import com.example.vereins_kassensystem.platform.PaymentResult
import com.example.vereins_kassensystem.platform.Platform
import com.example.vereins_kassensystem.platform.PlatformKind
import com.example.vereins_kassensystem.platform.SettingsStore
import com.example.vereins_kassensystem.ui.VereinsDeckelApp
import com.example.vereins_kassensystem.ui.format.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Trinkgeld durch die echten Bildschirme, mit einem Kartenterminal, das mitschreibt, was die
 * Kasse von ihm will, und antwortet, was der Test sagt. Der Code ist derselbe wie auf Android;
 * nur das SumUp-SDK dahinter ist hier nachgebaut — was dessen Antworten heißen, prüft
 * `SumUpOutcomeTest` auf der JVM.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class TipFlowOnIosTest {

    private data class Charge(val amount: Double, val tip: Double, val tipOnTerminal: Boolean)

    private class Terminal(var asksForTip: Boolean) : PaymentProcessor {
        val charges = mutableListOf<Charge>()
        var next: PaymentResult = PaymentResult.Success("TX")
        override suspend fun isAvailable() = true
        override suspend fun login(affiliateKey: String) = Result.success(Unit)
        override suspend fun asksForTipOnTerminal() = asksForTip
        override suspend fun charge(amount: Double, reference: String, tip: Double, tipOnTerminal: Boolean): PaymentResult {
            charges += Charge(amount, tip, tipOnTerminal)
            return next
        }
    }

    private class TerminalPlatform(terminal: Terminal) : Platform {
        override val description = "Tablet mit Terminal"
        override val kind = PlatformKind.ANDROID
        override val settings: SettingsStore = MemorySettings()
        override val payments: PaymentProcessor = terminal
        override val backupScheduler: BackupScheduler = NoBackups
    }

    private val beer = "Weißbier 0,5l, ${Money.format(4.2)}"

    private fun withTill(terminal: Terminal, block: ComposeUiTest.(AppRepository) -> Unit) = runComposeUiTest {
        // Wie im SalesFlowOnIosTest: Die ViewModels laufen auf Main, und die steht, solange der Test wartet.
        Dispatchers.setMain(Dispatchers.Unconfined)
        val db = openTestDatabase()
        val graph = AppGraph(TerminalPlatform(terminal)) { db }
        try {
            runBlocking { graph.repository.insertProduct(Product(name = "Weißbier 0,5l", price = 4.2, category = "Getränke", servingSize = 0.5)) }
            setContent { VereinsDeckelApp(graph) }
            block(graph.repository)
        } finally {
            graph.close()
            db.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `the terminal asks for the tip and the till books what SumUp reports`() {
        val terminal = Terminal(asksForTip = true).also { it.next = PaymentResult.Success("TX-9", tip = 1.5) }
        withTill(terminal) { repository ->
            clickDescription(beer)
            clickText("Bezahlen"); clickText("Karte")
            waitForText("Das Trinkgeld wählt der Gast am Kartenterminal", substring = true)
            assertTrue(onAllNodesWithText("%", substring = true).fetchSemanticsNodes().isEmpty(), "die App fragt nicht selbst")
            clickText("Abschließen")
            waitUntil("Kartenbuchung", 10_000) { card(repository).isNotEmpty() }
            assertEquals(Charge(4.2, 0.0, tipOnTerminal = true), terminal.charges.single(), "das Terminal fragt, die App schickt kein eigenes Trinkgeld")
            assertEquals(setOf("Weißbier 0,5l 4.2", "Trinkgeld 1.5"), card(repository).map { "${it.productName} ${it.price}" }.toSet())
        }
    }

    @Test
    fun `without terminal tipping the app asks and sends the tip apart and forgets it when the payment does not go through`() {
        val terminal = Terminal(asksForTip = false)
        withTill(terminal) { repository ->
            // Abbruch am Terminal: Meldung, nichts gebucht, das Trinkgeld ist weg — die Barzahlung danach kennt es nicht.
            terminal.next = PaymentResult.Cancelled
            clickDescription(beer)
            clickText("Bezahlen"); clickText("Karte"); clickContaining("10 %"); clickText("Abschließen")
            waitForText("Kartenzahlung abgebrochen", substring = true)
            waitGone("Kartenzahlung abgebrochen")
            assertEquals(Charge(4.2, 0.5, tipOnTerminal = false), terminal.charges.last(), "Trinkgeld getrennt vom Betrag, damit es auf dem SumUp-Beleg als Trinkgeld steht")
            clickText("Bezahlen"); clickText("Bar"); clickText("Passend"); clickText("Abschließen")
            waitUntil("erste Barbuchung", 10_000) { cash(repository).size == 1 }

            // Abgelehnt: Die Meldung steht da, und auch dieses Trinkgeld ist vergessen.
            terminal.next = PaymentResult.Failed("Kein Internet: SumUp braucht eine Verbindung für die Kartenzahlung. Nichts gebucht.")
            clickDescription(beer)
            clickText("Bezahlen"); clickText("Karte"); clickContaining("5 %"); clickText("Abschließen")
            waitForText("Kein Internet", substring = true)
            waitGone("Kein Internet")
            clickText("Bezahlen"); clickText("Bar"); clickText("Passend"); clickText("Abschließen")
            waitUntil("zweite Barbuchung", 10_000) { cash(repository).size == 2 }
            assertTrue(cash(repository).none { it.productId == Ledger.TIP_REF }, "kein Trinkgeld aus den missglückten Kartenversuchen: ${cash(repository).map { it.productName }}")

            // Der nächste Versuch beginnt ohne Trinkgeld und geht durch.
            terminal.next = PaymentResult.Success("TX-2", tip = 0.5)
            clickDescription(beer)
            clickText("Bezahlen"); clickText("Karte")
            waitForText("Kein")
            onAllNodesWithText("Kein").onFirst().assertIsSelected()
            clickContaining("10 %"); clickText("Abschließen")
            waitUntil("Kartenbuchung", 10_000) { card(repository).isNotEmpty() }
            assertEquals(setOf("Weißbier 0,5l 4.2", "Trinkgeld 0.5"), card(repository).map { "${it.productName} ${it.price}" }.toSet())
        }
    }

    @Test
    fun `cash takes the change as tip or a typed amount and passend works on an uneven total`() = withTill(Terminal(asksForTip = false)) { repository ->
        // Drei Bier, 12,60 €: Die Summe der Zeilen trägt Rechenstaub, „Passend“ muss trotzdem reichen.
        repeat(3) { clickDescription(beer) }
        clickText("Bezahlen"); clickText("Bar"); clickText("Passend"); clickText("Abschließen")
        waitUntil("passend bezahlt", 10_000) { cash(repository).size == 1 }

        // „Passt so“: 5 € für 4,20 € — das Rückgeld wird Trinkgeld.
        clickDescription(beer)
        clickText("Bezahlen"); clickText("Bar"); clickText("5"); clickText("Rückgeld als Trinkgeld"); clickText("Abschließen")
        waitUntil("Trinkgeld aus dem Rückgeld", 10_000) { cash(repository).size == 3 }

        // „Mach neun“: 10 € für 8,40 €, 0,60 € Trinkgeld getippt, 1,00 € zurück.
        repeat(2) { clickDescription(beer) }
        clickText("Bezahlen"); clickText("Bar"); clickText("10")
        clickText("Trinkgeld"); clickText("0"); clickText(","); clickText("6")
        onAllNodesWithText("Trinkgeld").onFirst().assertIsSelected()
        clickText("Abschließen")
        waitUntil("getipptes Trinkgeld", 10_000) { cash(repository).size == 5 }

        val tips = cash(repository).filter { it.productId == Ledger.TIP_REF }.map { it.price }.sorted()
        assertEquals(listOf(0.6, 0.8), tips, "Trinkgeld in bar, als eigene Zeile")
        assertEquals(setOf(3, 1, 2), cash(repository).filter { it.productId != Ledger.TIP_REF }.map { it.quantity }.toSet())
    }

    private fun card(repository: AppRepository): List<Transaction> = runBlocking { repository.allTransactions.first().filter { it.paymentType == "CARD" } }
    private fun cash(repository: AppRepository): List<Transaction> = runBlocking { repository.allTransactions.first().filter { it.paymentType == "CASH" } }

    private fun ComposeUiTest.waitForText(text: String, substring: Boolean = false) {
        waitUntil("'$text' sichtbar", 10_000) { onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.waitGone(text: String) {
        waitUntil("'$text' verschwunden", 15_000) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty() }
    }

    private fun ComposeUiTest.clickText(text: String) {
        waitForText(text)
        onAllNodesWithText(text).onFirst().performClick()
    }

    private fun ComposeUiTest.clickContaining(text: String) {
        waitForText(text, substring = true)
        onAllNodesWithText(text, substring = true).onFirst().performClick()
    }

    private fun ComposeUiTest.clickDescription(description: String) {
        waitUntil("'$description' sichtbar", 10_000) { onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithContentDescription(description).onFirst().performClick()
    }
}
