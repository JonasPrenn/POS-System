package com.example.vereins_kassensystem

import com.example.vereins_kassensystem.data.AppDatabase
import com.example.vereins_kassensystem.data.Ledger
import com.example.vereins_kassensystem.data.SettingsRepository
import com.example.vereins_kassensystem.data.entity.Member
import com.example.vereins_kassensystem.data.entity.Product
import com.example.vereins_kassensystem.data.entity.ProductComponent
import com.example.vereins_kassensystem.data.entity.StockItem
import com.example.vereins_kassensystem.data.repository.AppRepository
import com.example.vereins_kassensystem.data.sync.PairingResult
import com.example.vereins_kassensystem.data.sync.SyncEngine
import com.example.vereins_kassensystem.data.sync.SyncProblem
import com.example.vereins_kassensystem.platform.SettingsStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Der Abgleich der App, mit zwei Geräten und einem nachgebauten Server — die
 * Abnahmekriterien aus Kapitel 8 der Spezifikation, soweit sie ohne Bildschirm gehen.
 */
class SyncEngineTest {

    /** Ein Tablet: eigene Datenbank, eigene Einstellungen, eigener Abgleich. */
    private class Device(server: FakeSyncServer, label: String, store: SettingsStore = MemorySettings()) {
        val db: AppDatabase = openTestDatabase()
        val repository = AppRepository(db)
        // Der Handler schluckt, was ein abgebrochener Beobachter beim Schließen der Datenbank noch wirft.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
        val engine = SyncEngine(
            database = db, repository = repository, settings = SettingsRepository(store),
            platform = TestPlatform(label), scope = scope, apiFactory = { _, _ -> server.api() }
        )

        suspend fun pair() = engine.pair("https://deckel.example.at", FakeSyncServer.PAIRING_CODE, "")
        suspend fun pending() = db.syncDao().pendingCount()
        suspend fun balanceOf(name: String) = repository.allMembers.first().first { it.name == name }.balance

        /** Erst den Abgleich anhalten und auslaufen lassen: Er beobachtet die Datenbank. */
        suspend fun close() {
            scope.coroutineContext.job.cancelAndJoin()
            db.close()
        }
    }

    private suspend fun Device.sell(product: Product, member: Member?, quantity: Int = 1) = repository.bookCheckout(
        listOf(AppRepository.SaleLine(product, null, quantity, 0.0)), 0.0, 0.0, member,
        if (member == null) "CASH" else Ledger.MEMBER_BALANCE, com.example.vereins_kassensystem.platform.Ids.new()
    )

    private fun devices(block: suspend (FakeSyncServer, Device, Device) -> Unit) = runTest {
        val server = FakeSyncServer()
        val theke = Device(server, "Theke")
        val ipad = Device(server, "iPad Garten")
        try {
            block(server, theke, ipad)
        } finally {
            theke.close()
            ipad.close()
        }
    }

    @Test
    fun `the first device uploads its stock and the second starts from it`() = devices { server, theke, ipad ->
        // Spezifikation 2.5: genau ein Gerät ist die Quelle; jedes weitere startet leer und zieht alles.
        val beer = Product(name = "Bratwurst", price = 4.2, category = "Küche")
        val keg = StockItem(name = "Bratwurst", simpleQuantity = 40.0)
        val maria = Member(name = "Maria Bauer")
        theke.repository.insertStockItem(keg)
        theke.repository.saveProduct(beer, emptyList(), listOf(ProductComponent(productId = beer.id, stockItemId = keg.id, quantityPerUnit = 1.0)), isNew = true)
        theke.repository.insertMember(maria)
        theke.repository.adjustMemberBalance(maria, 20.0, "Startguthaben", "CASH")
        theke.sell(beer, maria, quantity = 2)
        assertEquals(0, theke.pending(), "vor der Kopplung entsteht keine Warteschlange")

        assertIs<PairingResult.Source>(theke.pair())
        assertTrue(theke.pending() > 0, "die Erstbefüllung liegt in der Warteschlange")
        assertTrue(theke.engine.syncOnce())
        assertEquals(0, theke.pending())
        assertEquals(1, server.count("members"))
        assertEquals(2, server.count("transactions"))

        assertEquals(PairingResult.Joined, ipad.pair())
        assertTrue(ipad.engine.syncOnce())
        assertEquals(11.6, ipad.balanceOf("Maria Bauer"), 0.0001, "20 − 2 × 4,20, aus den gezogenen Buchungen hergeleitet")
        assertEquals(38.0, ipad.repository.allStockItems.first().single().simpleQuantity, 0.0001, "40 − 2, aus Eingang und Abgängen")
        assertEquals("Bratwurst", ipad.repository.allProducts.first().single().name)
        assertEquals(0, ipad.pending(), "Gezogenes wird nicht wieder geschoben")
    }

    @Test
    fun `more rows than one call carries go up and come down in several`() = devices { server, theke, ipad ->
        // Geschoben wird zu 200, gezogen zu 500 — ein Verein mit ein paar Jahren Buchungen liegt weit darüber.
        repeat(520) { theke.repository.insertMember(Member(name = "Mitglied ${it + 1}")) }
        assertIs<PairingResult.Source>(theke.pair())
        assertTrue(theke.engine.syncOnce())
        assertEquals(0, theke.pending())
        assertEquals(520, server.count("members"))
        assertEquals(3, server.pushCalls, "200 + 200 + 120")

        val pullsBefore = server.pullCalls
        assertEquals(PairingResult.Joined, ipad.pair())
        assertTrue(ipad.engine.syncOnce())
        assertEquals(520, ipad.repository.allMembers.first().size)
        // Ein Aufruf der Kopplung (ist der Server leer?), dann zwei Seiten: 500 + 20.
        assertEquals(3, server.pullCalls - pullsBefore)
    }

    @Test
    fun `sales made offline arrive once even when the answer gets lost`() = devices { server, theke, _ ->
        // A2, A4, A7, A8.
        val beer = Product(name = "Weißbier 0,5l", price = 4.2, category = "Getränke")
        theke.repository.insertProduct(beer)
        theke.pair()
        assertTrue(theke.engine.syncOnce())

        server.online = false
        repeat(3) { theke.sell(beer, null) }
        assertEquals(3, theke.pending())
        assertEquals(false, theke.engine.syncOnce())
        assertEquals(SyncProblem.Offline, theke.engine.status.first { it.problem != null }.problem)
        assertEquals(3, theke.repository.allTransactions.first().size, "verkauft wird trotzdem")

        // Wieder Netz, aber die erste Antwort geht verloren, nachdem der Server verbucht hat.
        server.online = true
        server.loseNextResponse = true
        assertEquals(false, theke.engine.syncOnce())
        assertEquals(3, theke.pending(), "ohne Antwort bleibt alles in der Warteschlange")
        assertTrue(theke.engine.syncOnce())
        assertEquals(0, theke.pending())
        assertEquals(3, server.count("transactions"), "der Wiederholungsversuch bucht nichts doppelt")
        assertNull(theke.engine.status.first { !it.running && it.pending == 0 && it.lastSyncAt != null }.problem)
    }

    @Test
    fun `two tills charging the same tab both end up with the sum`() = devices { _, theke, ipad ->
        // A3 — der Test, der mit einem fortgeschriebenen Saldo fehlschlägt.
        val beer = Product(name = "Bier", price = 4.0, category = "Getränke")
        val wine = Product(name = "Wein", price = 5.0, category = "Getränke")
        val maria = Member(name = "M. Bauer")
        theke.repository.insertProduct(beer)
        theke.repository.insertProduct(wine)
        theke.repository.insertMember(maria)
        theke.repository.adjustMemberBalance(maria, 20.0, "Startguthaben", "CASH")
        theke.pair(); theke.engine.syncOnce()
        ipad.pair(); ipad.engine.syncOnce()
        assertEquals(20.0, ipad.balanceOf("M. Bauer"), 0.0001)

        // Beide lesen 20,00 und buchen, ohne voneinander zu wissen.
        theke.sell(beer, maria)
        ipad.sell(wine, ipad.repository.allMembers.first().single())
        theke.engine.syncOnce(); ipad.engine.syncOnce(); theke.engine.syncOnce()

        assertEquals(11.0, theke.balanceOf("M. Bauer"), 0.0001)
        assertEquals(11.0, ipad.balanceOf("M. Bauer"), 0.0001)
    }

    @Test
    fun `a price changed on both devices settles on the later write everywhere`() = devices { server, theke, ipad ->
        // A5.
        val beer = Product(name = "Weißbier 0,5l", price = 4.2, category = "Getränke")
        theke.repository.insertProduct(beer)
        theke.pair(); theke.engine.syncOnce()
        ipad.pair(); ipad.engine.syncOnce()

        theke.repository.updateProduct(theke.repository.allProducts.first().single().copy(price = 4.5))
        ipad.repository.updateProduct(ipad.repository.allProducts.first().single().copy(price = 4.4))
        theke.engine.syncOnce()
        // Das iPad setzt auf einem Stand auf, den der Server nicht mehr hat: veraltet, es übernimmt den Serverwert.
        ipad.engine.syncOnce()

        assertEquals(4.5, ipad.repository.allProducts.first().single().price, 0.0001)
        assertEquals("4.50", server.row("products", beer.id)!!["price"]!!.jsonPrimitive.content)
        assertEquals(0, ipad.pending())

        // Danach kennt das iPad den neuen Stand und darf wieder ändern.
        ipad.repository.updateProduct(ipad.repository.allProducts.first().single().copy(price = 4.6))
        ipad.engine.syncOnce(); theke.engine.syncOnce()
        assertEquals(4.6, theke.repository.allProducts.first().single().price, 0.0001)
    }

    @Test
    fun `deleting travels as a mark and not as an absence`() = devices { _, theke, ipad ->
        val maria = Member(name = "Maria Bauer")
        theke.repository.insertMember(maria)
        theke.pair(); theke.engine.syncOnce()
        ipad.pair(); ipad.engine.syncOnce()
        assertEquals(1, ipad.repository.allMembers.first().size)

        theke.repository.deleteMember(maria)
        theke.engine.syncOnce(); ipad.engine.syncOnce()
        assertTrue(ipad.repository.allMembers.first().isEmpty())
        assertTrue(ipad.db.syncDao().allMembers().single().sync.deleted)
    }

    @Test
    fun `a change the server refuses is set aside and the rest goes through`() = devices { server, theke, _ ->
        val beer = Product(name = "Weißbier 0,5l", price = 4.2, category = "Getränke")
        theke.pair(); theke.engine.syncOnce()
        server.rejectedEntity = "members"

        theke.repository.insertMember(Member(name = "Unzustellbar"))
        theke.repository.insertProduct(beer)
        theke.sell(beer, null)
        assertTrue(theke.engine.syncOnce(), "der Lauf selbst gelingt")

        assertEquals(0, theke.pending(), "die Warteschlange ist nicht verstopft")
        assertEquals(1, server.count("products"))
        assertEquals(1, server.count("transactions"))
        assertEquals(0, server.count("members"))

        // Kein Problem des Laufs, aber ein Hinweis, der stehen bleibt, bis ihn jemand gesehen hat.
        val status = theke.engine.status.first { it.lastRejected != null }
        assertNull(status.problem)
        assertTrue(status.lastRejected!!.contains("members"))
        assertTrue(theke.engine.syncOnce())
        assertTrue(theke.engine.status.value.lastRejected != null, "der nächste Lauf räumt ihn nicht weg")
        theke.engine.dismissRejected()
        assertNull(theke.engine.status.first { it.lastRejected == null }.lastRejected)
    }

    @Test
    fun `a revoked device keeps selling and says that it needs pairing`() = devices { server, theke, _ ->
        val beer = Product(name = "Weißbier 0,5l", price = 4.2, category = "Getränke")
        theke.repository.insertProduct(beer)
        theke.pair(); theke.engine.syncOnce()

        server.revoked = true
        theke.sell(beer, null)
        assertEquals(false, theke.engine.syncOnce())
        assertEquals(SyncProblem.NeedsPairing, theke.engine.status.first { it.problem != null }.problem)
        assertEquals(1, theke.pending(), "die Buchung wartet, bis das Gerät wieder angemeldet ist")

        // Neu anmelden statt Kopplung lösen: Bestand und Warteschlange bleiben, die Buchung geht hoch.
        assertEquals("Der Kopplungscode ist unbekannt, abgelaufen oder schon benutzt.", theke.engine.reauthorize("AAAA-AAAA"))
        assertNull(theke.engine.reauthorize(FakeSyncServer.PAIRING_CODE))
        assertTrue(theke.engine.syncOnce())
        assertEquals(0, theke.pending())
        assertEquals(1, server.count("transactions"))
        assertNull(theke.engine.status.first { !it.running && it.pending == 0 }.problem)
    }

    @Test
    fun `a device that cannot keep its token does not pretend to be paired`() = runTest {
        // Der Schlüsselbund eines unsignierten Simulator-Builds nimmt nichts an und sagt es nicht
        // (-34018). Ohne Zurücklesen sähe das Gerät gekoppelt aus und bekäme bei jedem Abgleich 401.
        val forgetful = object : SettingsStore by MemorySettings() {
            override suspend fun getSecret(key: String): String? = null
            override suspend fun putSecret(key: String, value: String) = Unit
        }
        val device = Device(FakeSyncServer(), "iPad Garten", forgetful)
        try {
            val result = device.pair()
            assertIs<PairingResult.Failed>(result)
            assertTrue(result.message.contains("Zugangsschlüssel"), result.message)
            assertEquals(false, device.engine.status.value.paired)
            assertNull(device.db.syncDao().state("device_id"), "nichts angefasst")
        } finally {
            device.close()
        }
    }

    @Test
    fun `when both sides have data nothing happens without a decision`() = devices { server, theke, ipad ->
        theke.repository.insertMember(Member(name = "Maria Bauer"))
        theke.pair(); theke.engine.syncOnce()

        ipad.repository.insertMember(Member(name = "Nur auf dem iPad"))
        assertEquals(PairingResult.NeedsDecision, ipad.pair())
        assertEquals(false, ipad.engine.status.value.paired)
        assertEquals(listOf("Nur auf dem iPad"), ipad.repository.allMembers.first().map { it.name }, "nichts angefasst")
        assertEquals(1, server.count("members"), "und nichts hochgeladen")

        ipad.engine.adoptServerState()
        assertTrue(ipad.engine.syncOnce())
        assertEquals(listOf("Maria Bauer"), ipad.repository.allMembers.first().map { it.name })

        // Ein falscher Code koppelt nicht.
        val wrong = theke.engine.pair("https://deckel.example.at", "AAAA-AAAA", "")
        assertIs<PairingResult.Failed>(wrong)
        assertIs<PairingResult.Failed>(theke.engine.pair("http://deckel.example.at", FakeSyncServer.PAIRING_CODE, ""), "ohne https nur beim Entwickeln")
    }
}
