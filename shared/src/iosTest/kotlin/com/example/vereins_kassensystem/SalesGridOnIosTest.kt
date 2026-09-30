package com.example.vereins_kassensystem

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.example.vereins_kassensystem.data.entity.Product
import com.example.vereins_kassensystem.data.repository.AppRepository
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
 * Das Raster im Verkauf durch die echte Oberfläche: Ausblenden per langem Druck — nur auf diesem
 * Gerät, verkaufen lässt sich das Produkt unter „Ausgeblendet“ trotzdem — und die Suche, die als
 * Symbol oben rechts sitzt und sich erst auf Antippen öffnet (Wünsche vom 30. September 2026).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class SalesGridOnIosTest {

    private val beer = "Weißbier 0,5l, ${Money.format(4.2)}"
    private val sausage = "Bratwurst, ${Money.format(3.5)}"

    private fun withTill(block: ComposeUiTest.(AppGraph, AppRepository) -> Unit) = runComposeUiTest {
        // Wie im SalesFlowOnIosTest: Die ViewModels laufen auf Main, und die steht, solange der Test wartet.
        Dispatchers.setMain(Dispatchers.Unconfined)
        val db = openTestDatabase()
        val graph = AppGraph(TestPlatform()) { db }
        try {
            runBlocking {
                graph.repository.insertProduct(Product(name = "Weißbier 0,5l", price = 4.2, category = "Getränke", servingSize = 0.5))
                graph.repository.insertProduct(Product(name = "Bratwurst", price = 3.5, category = "Küche"))
                graph.repository.openCashSession(50.0, "Test", "Theke")
            }
            setContent { VereinsDeckelApp(graph) }
            block(graph, graph.repository)
        } finally {
            graph.close()
            db.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a long press hides a product on this device and it still sells from Ausgeblendet`() = withTill { graph, repository ->
        waitForDescription(beer)
        onAllNodesWithContentDescription(beer).onFirst().performTouchInput { longClick() }
        clickText("Ausblenden")
        waitUntil("Bier nicht mehr unter „Alle“", 10_000) { onAllNodesWithContentDescription(beer).fetchSemanticsNodes().isEmpty() }
        waitForDescription(sausage)
        // Die Kategorie hatte nur das Bier — ihr Chip fällt weg, statt ein leeres Raster zu zeigen.
        assertTrue(onAllNodesWithText("Getränke").fetchSemanticsNodes().isEmpty(), "kein Chip für eine Kategorie ohne sichtbare Produkte")
        // Nur auf diesem Gerät: eine Einstellung des Geräts, keine Spalte am Produkt.
        assertEquals(1, runBlocking { graph.settingsRepository.hiddenProducts.first() }.size)

        // Unter „Ausgeblendet“ steht es — und verkauft sich trotzdem.
        clickText("Ausgeblendet · 1")
        clickDescription(beer)
        clickText("Bezahlen"); clickText("Bar"); clickText("Passend"); clickText("Abschließen")
        waitUntil("ausgeblendetes Bier verkauft", 10_000) {
            runBlocking { repository.allTransactions.first().any { it.productName == "Weißbier 0,5l" && it.paymentType == "CASH" } }
        }

        // Wieder einblenden: zurück unter „Alle“, und „Ausgeblendet“ verschwindet mit dem letzten.
        onAllNodesWithContentDescription(beer).onFirst().performTouchInput { longClick() }
        clickText("Einblenden")
        waitUntil("wieder unter „Alle“", 10_000) {
            onAllNodesWithText("Ausgeblendet", substring = true).fetchSemanticsNodes().isEmpty() &&
                onAllNodesWithContentDescription(beer).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(runBlocking { graph.settingsRepository.hiddenProducts.first() }.isEmpty())
    }

    @Test
    fun `search is an icon and opens its field only on demand`() = withTill { _, _ ->
        waitForDescription("Produkt suchen")
        assertTrue(onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty(), "kein Suchfeld, bevor jemand sucht")

        clickDescription("Produkt suchen")
        waitUntil("Suchfeld offen", 10_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasSetTextAction()).onFirst().performTextInput("Brat")
        waitUntil("nur noch die Bratwurst", 10_000) { onAllNodesWithContentDescription(beer).fetchSemanticsNodes().isEmpty() }
        waitForDescription(sausage)

        // Schließen leert die Suche — sonst bliebe das Raster unsichtbar gefiltert.
        clickDescription("Suche schließen")
        waitUntil("Suchfeld zu, alles wieder da", 10_000) {
            onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() &&
                onAllNodesWithContentDescription(beer).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun ComposeUiTest.waitForDescription(description: String) {
        waitUntil("'$description' sichtbar", 10_000) { onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.clickDescription(description: String) {
        waitForDescription(description)
        onAllNodesWithContentDescription(description).onFirst().performClick()
    }

    private fun ComposeUiTest.clickText(text: String) {
        waitUntil("'$text' sichtbar", 10_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText(text).onFirst().performClick()
    }
}
