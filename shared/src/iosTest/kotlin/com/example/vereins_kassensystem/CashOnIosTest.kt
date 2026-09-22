package com.example.vereins_kassensystem

import com.example.vereins_kassensystem.data.SettingsRepository
import com.example.vereins_kassensystem.data.Ledger
import com.example.vereins_kassensystem.data.entity.CashMovementKind
import com.example.vereins_kassensystem.data.entity.Member
import com.example.vereins_kassensystem.data.entity.displayName
import com.example.vereins_kassensystem.data.entity.Product
import com.example.vereins_kassensystem.data.repository.AppRepository
import com.example.vereins_kassensystem.data.sync.RowCodec
import com.example.vereins_kassensystem.data.sync.SyncApplier
import com.example.vereins_kassensystem.data.sync.SyncTables
import com.example.vereins_kassensystem.platform.Ids
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Die Lade dieses Geräts (Konzept 4.5): gezählt, gerechnet, geschlossen — mit dem echten Treiber. */
class CashOnIosTest {

    private fun money(expected: Double, actual: Double, message: String) = assertEquals(expected, actual, 0.001, message)

    @Test
    fun `the expected cash follows what this device booked and nothing pulled from the server`() = runTest {
        val db = openTestDatabase()
        try {
            val repository = AppRepository(db)
            val beer = Product(name = "Helles 0,5", price = 4.2, category = "Getränke")
            repository.insertProduct(beer)
            val maria = Member(name = "Maria Bauer")
            repository.insertMember(maria)
            // Ein Couleurname, nachträglich vergeben, bleibt beim Ändern erhalten — das ging vorher verloren.
            repository.updateMember(maria.copy(nickname = "Minerva"))
            assertEquals("Minerva", assertNotNull(repository.getMember(maria.id)).nickname)
            assertEquals("Maria Bauer v. Minerva", assertNotNull(repository.getMember(maria.id)).displayName)

            assertNull(repository.openCashSession.first(), "vor dem Öffnen keine Schicht")
            val session = repository.openCashSession(150.0, "Matthias", "Theke links")
            assertEquals(session.id, repository.openCashSession(999.0, "Jemand", "x").id, "eine zweite Schicht in derselben Lade gibt es nicht")

            repository.bookCheckout(listOf(AppRepository.SaleLine(beer, null, 2, 0.0)), 0.0, 0.0, null, "CASH", Ids.new())          // +8,40 bar
            repository.bookCheckout(listOf(AppRepository.SaleLine(beer, null, 1, 0.0)), 0.0, 0.0, null, "CARD", Ids.new())          // Karte: nicht in der Lade
            repository.bookCheckout(listOf(AppRepository.SaleLine(beer, null, 1, 0.0)), 0.0, 0.0, maria, Ledger.MEMBER_BALANCE, Ids.new()) // Deckel: nicht in der Lade
            repository.adjustMemberBalance(maria, 20.0, "Aufladung", "CASH")                                                        // +20,00 bar
            repository.recordCashMovement(session, CashMovementKind.WITHDRAWAL, 50.0, "zur Bank", "Matthias")
            repository.recordCashMovement(session, CashMovementKind.DEPOSIT, 10.0, "Wechselgeld", "Matthias")

            // Eine Buchung, die vom Server kommt, gehört einem anderen Gerät und zählt hier nicht.
            val pulled = RowCodec.encode(
                com.example.vereins_kassensystem.data.entity.Transaction(
                    transactionGroupId = Ids.new(), memberId = null, memberName = null, productId = beer.id, productName = beer.name,
                    productCategory = "Getränke", price = 4.2, quantity = 5, paymentType = "CASH"
                )
            )
            SyncApplier(db, SettingsRepository(MemorySettings())).apply(SyncTables.TRANSACTIONS, pulled, deleted = false)

            money(138.4, repository.expectedCash(session), "150 + 8,40 + 20 − 50 + 10; Karte, Deckel und die gezogene Zeile nicht")

            repository.closeCashSession(session, 136.4, "Matthias", "Wechselgeld verzählt")
            assertNull(repository.openCashSession.first())
            val closed = assertNotNull(db.cashDao().session(session.id))
            money(136.4, assertNotNull(closed.closingCount), "gezählt")
            assertEquals("Wechselgeld verzählt", closed.note)

            // Drahtformat: hin und zurück dieselbe Zeile.
            assertEquals(closed.copy(sync = closed.sync), RowCodec.decodeCashSession(RowCodec.encode(closed), false).copy(sync = closed.sync))
            val movement = db.cashDao().movements(session.id).first()
            assertEquals(movement.copy(sync = movement.sync), RowCodec.decodeCashMovement(RowCodec.encode(movement), false).copy(sync = movement.sync))
        } finally {
            db.close()
        }
    }
}
