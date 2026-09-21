package com.example.vereins_kassensystem

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.vereins_kassensystem.data.AppDatabase
import com.example.vereins_kassensystem.data.Ledger
import com.example.vereins_kassensystem.data.entity.ContainerCloseReason
import com.example.vereins_kassensystem.data.entity.ContainerType
import com.example.vereins_kassensystem.data.entity.Member
import com.example.vereins_kassensystem.data.entity.MemberCategory
import com.example.vereins_kassensystem.data.entity.Product
import com.example.vereins_kassensystem.data.entity.ProductComponent
import com.example.vereins_kassensystem.data.entity.ProductVariant
import com.example.vereins_kassensystem.data.entity.StockItem
import com.example.vereins_kassensystem.data.entity.StockTracking
import com.example.vereins_kassensystem.data.entity.isOpen
import com.example.vereins_kassensystem.data.repository.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Room auf Kotlin/Native, einmal wirklich ausgeführt.
 *
 * Die Übersetzung beweist nur, dass der Prozessor seinen Code erzeugt; ob der
 * gebündelte SQLite-Treiber auf iOS Tabellen anlegt, schreibt und Ströme liefert, zeigt
 * erst ein Lauf. Seit Schema 11 prüft dieser Test vor allem die hergeleiteten Werte: Es
 * gibt keinen Zähler mehr, den man fortschreiben könnte — Saldo und Bestand müssen aus den
 * Zeilen stimmen. Läuft im iOS-Simulator mit `:shared:allTests`.
 */
class RoomOnIosTest {

    private fun openDatabase(): AppDatabase =
        Room.inMemoryDatabaseBuilder<AppDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

    @Test
    fun `the balance is what the ledger rule says about the booked lines`() = runTest {
        val db = openDatabase()
        try {
            val repository = AppRepository(db)
            val beer = Product(name = "Weißbier 0,5l", price = 4.2, category = "Getränke", servingSize = 0.5)
            repository.insertProduct(beer)
            val maria = Member(name = "Maria Bauer")
            repository.insertMember(maria)
            repository.adjustMemberBalance(maria, 23.5, "Startguthaben", "CASH")

            // Bar berührt den Deckel nicht, der Deckelverkauf mit Rabatt schon, die Korrektur auch.
            repository.bookCheckout(listOf(AppRepository.SaleLine(beer, null, 1, 0.0)), 0.0, 0.0, null, "CASH", "018f2b6c-7d1e-7a00-8000-0000000000a1")
            repository.bookCheckout(listOf(AppRepository.SaleLine(beer, null, 2, 0.4)), 0.0, 0.0, maria, Ledger.MEMBER_BALANCE, "018f2b6c-7d1e-7a00-8000-0000000000a2")
            repository.adjustMemberBalance(maria, -0.5, "Glas zerbrochen", "CORRECTION")

            val history = repository.allTransactions.first()
            assertEquals(4, history.size)
            val expected = history.filter { it.memberId == maria.id }.sumOf {
                Ledger.balanceEffect(it.productId, it.paymentType, it.price, it.quantity, it.discountAmount, it.isRefund)
            }
            val member = repository.allMembers.first().single()
            assertEquals(expected, member.balance, 0.0001, "SQL in MemberDao und Ledger rechnen dasselbe")
            assertEquals(15.0, member.balance, 0.0001, "23,50 − (8,40 − 0,40) − 0,50")
            assertTrue(member.lastUsedTimestamp > 0)
        } finally {
            db.close()
        }
    }

    @Test
    fun `stock is receipts minus taps minus what sales drew`() = runTest {
        val db = openDatabase()
        try {
            val repository = AppRepository(db)
            val bier = StockItem(name = "Bier", unit = "l", tracking = StockTracking.CONTAINER)
            val wurst = StockItem(name = "Bratwurst", unit = "Stk", simpleQuantity = 40.0)
            repository.insertStockItem(bier)
            repository.insertStockItem(wurst)
            val keg = ContainerType(stockItemId = bier.id, label = "50 l Fass", nominalSize = 50.0, initialYieldEstimate = 49.0)
            repository.insertContainerType(keg)

            val helles = Product(name = "Helles", price = 4.0, category = "Getränke", hasVariants = true)
            val halbe = ProductVariant(productId = helles.id, name = "0,5l", price = 4.0, servingSize = 0.5)
            repository.saveProduct(helles, listOf(halbe), listOf(ProductComponent(productId = helles.id, stockItemId = bier.id, quantityPerUnit = 1.0)), isNew = true)
            val bratwurst = Product(name = "Bratwurst", price = 3.5, category = "Küche")
            repository.saveProduct(bratwurst, emptyList(), listOf(ProductComponent(productId = bratwurst.id, stockItemId = wurst.id, quantityPerUnit = 1.0)), isNew = true)

            assertEquals(40.0, repository.allStockItems.first().first { it.id == wurst.id }.simpleQuantity, 0.0001, "Anfangsbestand als Korrekturbuchung")

            repository.receiveStock(bier, 3.0, keg, totalCost = 270.0)
            repository.tapContainer(keg)
            repository.bookCheckout(
                listOf(AppRepository.SaleLine(helles, halbe, 6, 0.0), AppRepository.SaleLine(bratwurst, null, 5, 0.0)),
                0.0, 0.0, null, "CASH", "018f2b6c-7d1e-7a00-8000-0000000000b1"
            )

            assertEquals(2, repository.allContainerTypes.first().single().fullCount, "drei geliefert, eins am Hahn")
            val onTap = repository.allTappedContainers.first().single()
            assertTrue(onTap.isOpen)
            assertEquals(3.0, onTap.drawn, 0.0001, "sechs Halbe")
            assertEquals(35.0, repository.allStockItems.first().first { it.id == wurst.id }.simpleQuantity, 0.0001)

            // Schließen beendet das Zeitfenster: Was danach verkauft wird, zählt nicht mehr zu diesem Fass.
            repository.closeContainer(onTap, ContainerCloseReason.EMPTIED)
            val closed = repository.allTappedContainers.first().single()
            assertFalse(closed.isOpen)
            assertEquals(3.0, closed.drawn, 0.0001)

            // Den Bestand im Artikeldialog von 35 auf 30 zu setzen bucht die Differenz, statt einen Zähler zu überschreiben.
            repository.updateStockItem(repository.allStockItems.first().first { it.id == wurst.id }.copy(simpleQuantity = 30.0))
            assertEquals(30.0, repository.allStockItems.first().first { it.id == wurst.id }.simpleQuantity, 0.0001)
            assertEquals(-5.0, repository.allStockEntries.first().first { it.note == "Bestand im Artikel korrigiert" }.quantity, 0.0001)
        } finally {
            db.close()
        }
    }

    @Test
    fun `deleting is soft and a group with members stays`() = runTest {
        val db = openDatabase()
        try {
            val repository = AppRepository(db)
            val aktive = MemberCategory(name = "Aktive", negativeBalanceLimit = -20.0)
            repository.insertCategory(aktive)
            val maria = Member(name = "Maria Bauer", categoryId = aktive.id)
            repository.insertMember(maria)

            assertFalse(repository.deleteCategory(aktive), "die Gruppe hat noch ein Mitglied")
            repository.deleteMember(maria)
            assertTrue(repository.allMembers.first().isEmpty())
            assertEquals(1, db.syncDao().allMembers().size, "die Zeile ist noch da, nur markiert")
            assertTrue(repository.deleteCategory(aktive))
            assertTrue(repository.allCategories.first().isEmpty())

            val pommes = Product(name = "Pommes", price = 0.0, category = "Küche", hasVariants = true)
            repository.saveProduct(pommes, listOf(ProductVariant(productId = pommes.id, name = "klein", price = 3.0)), emptyList(), isNew = true)
            repository.deleteProduct(pommes)
            assertTrue(repository.allProductsWithVariants.first().isEmpty())
            assertTrue(db.syncDao().allVariants().single().sync.deleted, "die Variante geht mit dem Produkt")
        } finally {
            db.close()
        }
    }
}
