package com.example.vereins_kassensystem

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.example.vereins_kassensystem.data.entity.Member
import com.example.vereins_kassensystem.data.entity.MemberCategory
import com.example.vereins_kassensystem.data.entity.Product
import com.example.vereins_kassensystem.ui.VereinsDeckelApp
import com.example.vereins_kassensystem.ui.format.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Bar und Deckel, einmal wirklich bedient — auf Kotlin/Native im iOS-Simulator.
 *
 * `RoomOnIosTest` zeigt, dass die Datenbank auf iOS bucht. Dieser Test zeigt, dass der Weg
 * dorthin hält: die echte Wurzel der Oberfläche ([VereinsDeckelApp]) mit Navigation,
 * ViewModels und Dialogen, darunter ein [AppGraph] mit einer Datenbank im Speicher.
 * Getippt wird, was ein Mitglied am Samstag tippt: Produkt, Bezahlen, Bar, Passend,
 * Abschließen — und dasselbe noch einmal auf Marias Deckel.
 *
 * Nicht abgedeckt ist, was nur das Gerät kann: die Berührung durch UIKit hindurch, die
 * Tastatur, Kamera und Dateiauswahl. Die Szene hier hat kein Fenster.
 *
 * Läuft mit `:shared:iosSimulatorArm64Test`, also mit `:shared:allTests`.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class SalesFlowOnIosTest {

    @Test
    fun `a cash sale and a sale on the tab through the real screens`() = runComposeUiTest {
        // Die ViewModels laufen auf Dispatchers.Main. Im Testprozess ist das die
        // Hauptschleife von iOS, und die steht, solange der Test auf ihr wartet — eine
        // Buchung käme nach dem ersten Datenbankzugriff nie zurück. Unconfined statt
        // Default, weil Lifecycle den Haupt-Thread daran erkennt, dass Main keinen Wechsel
        // braucht; mit Default verweigert der NavHost den Aufbau.
        Dispatchers.setMain(Dispatchers.Unconfined)
        val db = openTestDatabase()
        val graph = AppGraph(TestPlatform()) { db }
        try {
            val repository = graph.repository

            val categoryId = repository.insertCategory(MemberCategory(name = "Mitglied", negativeBalanceLimit = 0.0))
            repository.insertProduct(
                Product(name = "Weißbier 0,5l", price = 4.2, category = "Getränke", servingSize = 0.5)
            )
            // Guthaben entsteht nur noch durch eine Buchung — auch im Test.
            val maria = Member(name = "Maria Bauer", categoryId = categoryId)
            repository.insertMember(maria)
            repository.adjustMemberBalance(maria, 23.5, "Startguthaben", "CASH")
            val beer = "Weißbier 0,5l, ${Money.format(4.2)}"

            setContent { VereinsDeckelApp(graph) }

            // ---- Bar
            clickDescription(beer)
            clickText("Bezahlen")
            clickText("Bar")
            clickText("Passend")
            clickText("Abschließen")
            waitUntil("Barverkauf in der Datenbank", 10_000) {
                runBlocking { repository.allTransactions.first().any { it.paymentType == "CASH" && it.memberId == null } }
            }
            val cash = repository.allTransactions.first().single { it.memberId == null }
            assertEquals("CASH", cash.paymentType)
            assertEquals("Weißbier 0,5l", cash.productName)
            assertEquals(4.2, cash.price, 0.0001)
            assertEquals(1, cash.quantity)
            assertNull(cash.memberId)
            waitForText("Nichts ausgewählt")

            // ---- Deckel
            clickText("Mitglied auswählen")
            clickText("Maria Bauer")
            clickDescription(beer)
            clickText("Bezahlen")
            clickText("Deckel · Maria Bauer")
            clickText("Abschließen")
            waitUntil("Deckel belastet", 10_000) {
                runBlocking { abs(repository.allMembers.first().single().balance - 19.3) < 0.0001 }
            }
            val history = repository.allTransactions.first()
            assertEquals(3, history.size, "Startguthaben, Barverkauf, Deckelverkauf")
            val tab = history.single { it.paymentType == "MEMBER_BALANCE" }
            assertEquals("Maria Bauer", tab.memberName)
            assertEquals(repository.allMembers.first().single().id, tab.memberId)
            waitForText("Nichts ausgewählt")
        } finally {
            // Erst den Abgleich anhalten: Er beobachtet die Datenbank und überlebte sie sonst.
            graph.close()
            db.close()
            Dispatchers.resetMain()
        }
    }

    private fun ComposeUiTest.waitForText(text: String) {
        waitUntil("'$text' sichtbar", 10_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.clickText(text: String) {
        waitForText(text)
        onAllNodesWithText(text).onFirst().performClick()
    }

    private fun ComposeUiTest.clickDescription(description: String) {
        waitUntil("'$description' sichtbar", 10_000) {
            onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
        onAllNodesWithContentDescription(description).onFirst().performClick()
    }
}
