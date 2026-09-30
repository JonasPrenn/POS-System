package com.example.vereins_kassensystem

import com.example.vereins_kassensystem.data.SettingsRepository
import com.example.vereins_kassensystem.data.entity.Member
import com.example.vereins_kassensystem.data.entity.Product
import com.example.vereins_kassensystem.data.repository.AppRepository
import com.example.vereins_kassensystem.platform.PaymentProcessor
import com.example.vereins_kassensystem.platform.PaymentResult
import com.example.vereins_kassensystem.viewmodel.MemberViewModel
import com.example.vereins_kassensystem.viewmodel.SalesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ohne offene Kasse wird nicht kassiert (Entscheidung vom 30. September 2026) — geprüft an den
 * ViewModels selbst, nicht nur daran, dass die Oberfläche keinen Knopf zeigt. Vor allem die
 * Karte: Sie darf gar nicht erst belastet werden, denn eine belastete Karte ohne Buchung ist
 * der teuerste Fehler, den die Kasse machen kann.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TillGateOnIosTest {

    private class Terminal : PaymentProcessor {
        val charges = mutableListOf<Double>()
        override suspend fun isAvailable() = true
        override suspend fun login(affiliateKey: String) = Result.success(Unit)
        override suspend fun charge(amount: Double, reference: String, tip: Double, tipOnTerminal: Boolean): PaymentResult {
            charges += amount
            return PaymentResult.Success("TX")
        }
    }

    private val beer = Product(name = "Helles 0,5", price = 4.2, category = "Getränke")

    private fun withTill(block: suspend CoroutineScope.(AppRepository, SalesViewModel, MutableList<String>) -> Unit) = runBlocking {
        // Die ViewModels laufen auf Main; im Test ist das die Hauptschleife, die steht, solange er wartet.
        Dispatchers.setMain(Dispatchers.Unconfined)
        val db = openTestDatabase()
        try {
            val repository = AppRepository(db)
            repository.insertProduct(beer)
            val sales = SalesViewModel(repository, SettingsRepository(MemorySettings()))
            val errors = mutableListOf<String>()
            val listening = launch(Dispatchers.Unconfined) { sales.checkoutError.collect { errors += it } }
            block(repository, sales, errors)
            listening.cancel()
        } finally {
            db.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `while the till is closed no card is charged and nothing is booked`() = withTill { repository, sales, errors ->
        val terminal = Terminal()
        sales.addToCart(beer)
        sales.checkoutByCard(terminal).join()
        sales.checkout("CASH").join()
        sales.checkout("MEMBER_BALANCE").join()

        assertTrue(terminal.charges.isEmpty(), "die Karte wird bei geschlossener Kasse nicht belastet")
        assertTrue(repository.allTransactions.first().isEmpty(), "nichts gebucht")
        assertEquals(3, errors.size)
        assertTrue(errors.all { it.startsWith("Die Kasse ist zu") }, errors.toString())
        assertEquals(1, sales.cart.value.size, "der Warenkorb bleibt, bis die Kasse offen ist")
    }

    @Test
    fun `without a drawer cash is refused and the card goes through`() = withTill { repository, sales, errors ->
        repository.openCashSession(0.0, "Maria Bauer", "Theke", cashless = true)
        val terminal = Terminal()
        sales.addToCart(beer)
        sales.checkout("CASH").join()
        assertTrue(repository.allTransactions.first().isEmpty(), "kein Bargeld ohne Barkasse")
        assertTrue(errors.single().contains("ohne Barkasse"), errors.toString())

        sales.checkoutByCard(terminal).join()
        assertEquals(listOf(4.2), terminal.charges)
        assertEquals(listOf("CARD"), repository.allTransactions.first().map { it.paymentType })
    }

    @Test
    fun `a cash top up needs an open drawer`() = withTill { repository, _, _ ->
        val maria = Member(name = "Maria Bauer")
        repository.insertMember(maria)
        val members = MemberViewModel(repository)

        members.adjustBalance(maria, 20.0, "Aufladung", "CASH").join()
        assertTrue(repository.allTransactions.first().isEmpty(), "ohne offene Kasse kein Bargeld")

        repository.openCashSession(0.0, "Maria Bauer", "Theke", cashless = true)
        members.adjustBalance(maria, 20.0, "Aufladung", "CASH").join()
        assertTrue(repository.allTransactions.first().isEmpty(), "ohne Barkasse kein Bargeld")

        // Die Kasse ohne Barkasse schließen, eine mit Barkasse öffnen: Jetzt geht es.
        repository.closeCashSession(repository.openCashSession.first()!!, null, "Maria Bauer", null)
        repository.openCashSession(50.0, "Maria Bauer", "Theke")
        members.adjustBalance(maria, 20.0, "Aufladung", "CASH").join()
        assertEquals(listOf("CASH"), repository.allTransactions.first().map { it.paymentType })
        assertEquals(20.0, repository.getMember(maria.id)!!.balance, 0.0001)
    }
}
