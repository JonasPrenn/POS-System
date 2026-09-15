package com.example.vereins_kassensystem

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.vereins_kassensystem.data.AppDatabase
import com.example.vereins_kassensystem.data.entity.Member
import com.example.vereins_kassensystem.data.entity.Product
import com.example.vereins_kassensystem.data.entity.Transaction
import com.example.vereins_kassensystem.data.repository.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Room auf Kotlin/Native, einmal wirklich ausgeführt.
 *
 * Die Übersetzung beweist nur, dass der Prozessor seinen Code erzeugt; ob der
 * gebündelte SQLite-Treiber auf iOS Tabellen anlegt, schreibt und Ströme liefert, zeigt
 * erst ein Lauf. Dieser Test bucht, was die Kasse am Samstag tut — einen Barverkauf und
 * einen Verkauf auf den Deckel — und liest beides zurück. Läuft im iOS-Simulator über
 * `:shared:iosSimulatorArm64Test`, also mit `:shared:allTests`.
 */
class RoomOnIosTest {

    private fun openDatabase(): AppDatabase =
        Room.inMemoryDatabaseBuilder<AppDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

    @Test
    fun `a cash sale and a sale on the tab land in the history and on the balance`() = runTest {
        val db = openDatabase()
        try {
            val repository = AppRepository(
                db.productDao(), db.memberDao(), db.transactionDao(), db.categoryDao(),
                db.stockEntryDao(), db.stockDao(), db.deliveryDao()
            )

            val productId = repository.insertProduct(
                Product(name = "Weißbier 0,5l", price = 4.2, category = "Getränke", servingSize = 0.5)
            )
            repository.insertMember(Member(name = "Maria Bauer", balance = 23.5))
            val maria = repository.allMembers.first().single()

            // Bar: eine Buchung ohne Mitglied.
            repository.insertTransaction(
                Transaction(
                    transactionGroupId = "bar-1", memberId = null, memberName = null,
                    productId = productId, productName = "Weißbier 0,5l", productCategory = "Getränke",
                    price = 4.2, quantity = 1, paymentType = "CASH"
                )
            )
            // Deckel: zwei Weißbier auf Marias Deckel, Saldo sinkt.
            repository.insertTransaction(
                Transaction(
                    transactionGroupId = "deckel-1", memberId = maria.id, memberName = maria.name,
                    productId = productId, productName = "Weißbier 0,5l", productCategory = "Getränke",
                    price = 4.2, quantity = 2, paymentType = "MEMBER_BALANCE"
                )
            )
            repository.updateMemberBalance(maria.id, -8.4)

            val history = repository.allTransactions.first()
            assertEquals(2, history.size)
            assertEquals(setOf("CASH", "MEMBER_BALANCE"), history.map { it.paymentType }.toSet())
            assertEquals(15.1, repository.allMembers.first().single().balance, 0.0001)
            assertEquals("Weißbier 0,5l", repository.allProducts.first().single().name)
        } finally {
            db.close()
        }
    }
}
