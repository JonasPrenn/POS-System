package com.example.vereins_kassensystem

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bar und Deckel, einmal wirklich bedient — auf Kotlin/Native im iOS-Simulator.
 *
 * `RoomOnIosTest` zeigt, dass die Datenbank auf iOS bucht. Dieser Test zeigt, dass der Weg
 * dorthin hält: die echte Wurzel der Oberfläche ([VereinsDeckelApp]) mit Navigation,
 * ViewModels und Dialogen, darunter ein [AppGraph] mit einer Datenbank im Speicher.
 * Getippt wird, was ein Mitglied am Samstag tippt: zuerst die Kasse öffnen, im Warenkorb, und
 * das Wechselgeld nach Stückelung zählen — vorher gibt es kein „Bezahlen“. Dann Produkt,
 * Bezahlen, Bar, Passend, Abschließen, dasselbe noch einmal auf Marias Deckel, und am Ende die
 * Kasse auf der Übersicht schließen, wieder gezählt.
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

            // ---- Ohne offene Kasse wird nicht kassiert: Der Warenkorb füllt sich, aber statt „Bezahlen“ steht „Kasse öffnen“.
            clickDescription(beer)
            waitForText("Die Kasse ist zu — erst öffnen, dann kassieren.")
            assertTrue(onAllNodesWithText("Bezahlen").fetchSemanticsNodes().isEmpty(), "kein Bezahlen bei geschlossener Kasse")

            // ---- Kasse öffnen, mit Barkasse: 2 × 50 €, 3 × 1 €, 5 × 10 ct = 103,50 €
            clickText("Kasse öffnen")
            inDialog("Wer öffnet: Mitglied wählen")
            inDialog("Maria Bauer")
            inDialog("50 €"); inDialog("2")
            inDialog("1 €"); inDialog("3")
            inDialog("10 ct"); inDialog("5")
            waitForText("103,50 €")
            inDialog("Öffnen")
            waitUntil("Kasse offen", 10_000) { runBlocking { repository.openCashSession.first() != null } }
            val opened = assertNotNull(repository.openCashSession.first())
            assertEquals(103.5, opened.openingCount, 0.0001)
            assertEquals("Maria Bauer", opened.openedBy)
            assertEquals(false, opened.cashless)
            waitForText("Kasse offen · Maria Bauer · mit Barkasse")

            // ---- Bar
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
            // Ein Tipp auf den Deckel bucht — ohne zweiten Schritt.
            clickText("Deckel · Maria Bauer")
            waitUntil("Deckel belastet", 10_000) {
                runBlocking { abs(repository.allMembers.first().single().balance - 19.3) < 0.0001 }
            }
            val history = repository.allTransactions.first()
            assertEquals(3, history.size, "Startguthaben, Barverkauf, Deckelverkauf")
            val tab = history.single { it.paymentType == "MEMBER_BALANCE" }
            assertEquals("Maria Bauer", tab.memberName)
            assertEquals(repository.allMembers.first().single().id, tab.memberId)
            waitForText("Nichts ausgewählt")

            // ---- Kasse schließen, auf der Übersicht: Soll 103,50 € Wechselgeld + 4,20 € bar = 107,70 €,
            // gezählt 1 × 100 €, 7 × 1 €, 1 × 50 ct, 1 × 20 ct. Der Deckel liegt nicht in der Lade.
            clickText("Übersicht")
            clickText("Kasse schließen")
            inDialog("100 €"); inDialog("1")
            inDialog("1 €"); inDialog("7")
            inDialog("50 ct"); inDialog("1")
            inDialog("20 ct"); inDialog("1")
            waitUntil("gezählt wie Soll", 10_000) { onAllNodes(hasText("107,70 €") and hasAnyAncestor(isDialog())).fetchSemanticsNodes().size == 2 }
            inDialog("Schließen")
            waitUntil("Kasse zu", 10_000) { runBlocking { repository.openCashSession.first() == null } }
            val closed = db.syncDao().allCashSessions().single()
            assertEquals(107.7, assertNotNull(closed.closingCount), 0.0001)
            assertEquals("Maria Bauer", closed.closedBy)
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

    /** Tippt im obersten Dialog — die Ziffer „2“ steht sonst womöglich auch dahinter auf der Seite. */
    private fun ComposeUiTest.inDialog(text: String) {
        val inDialog = hasText(text) and hasAnyAncestor(isDialog())
        waitUntil("'$text' im Dialog", 10_000) { onAllNodes(inDialog).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(inDialog).onFirst().performClick()
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
