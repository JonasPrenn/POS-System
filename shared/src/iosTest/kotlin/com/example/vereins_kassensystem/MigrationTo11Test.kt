package com.example.vereins_kassensystem

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.example.vereins_kassensystem.data.AppDatabase
import com.example.vereins_kassensystem.data.Ledger
import com.example.vereins_kassensystem.data.entity.ContainerCloseReason
import com.example.vereins_kassensystem.data.entity.StockEntrySource
import com.example.vereins_kassensystem.data.entity.isOpen
import com.example.vereins_kassensystem.data.repository.AppRepository
import com.example.vereins_kassensystem.data.stock.Inventory
import com.example.vereins_kassensystem.platform.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Die Umstellung 10 → 11 an einer Datenbank, wie sie auf dem Tablet im Vereinsheim liegt:
 * das echte Schema der Version 10, und darin alles, was über die Jahre schiefgehen konnte —
 * ein Saldo, den keine Buchung erklärt, ein Rabatt mit drei Nachkommastellen, ein hart
 * gelöschtes Mitglied, ein gelöschter Lagerartikel, ein verschwundener Beleg, ein offenes
 * und ein verdorbenes Fass.
 *
 * Die Erwartung ist immer dieselbe: Nach der Umstellung zeigt die App dieselben Zahlen wie
 * davor, und jede lässt sich auf Zeilen zurückführen.
 */
class MigrationTo11Test {

    private fun money(expected: Double, actual: Double, what: String) = assertEquals(expected, actual, 0.0001, what)

    @Test
    fun `a version 10 database keeps every balance and every stock figure`() = runTest {
        val path = NSTemporaryDirectory() + "vd-migration-${Ids.new()}.db"
        createVersion10(path)

        var db = open(path)
        try {
            val repository = AppRepository(db)

            // ---------------------------------------------------------- Mitglieder
            val members = repository.allMembers.first().associateBy { it.name }
            assertEquals(setOf("Maria Bauer", "Jonas Prenn", "Alter Herr"), members.keys)
            members.values.forEach { assertTrue(Ids.isValid(it.id), "Schlüssel von ${it.name}: ${it.id}") }
            money(23.5, members.getValue("Maria Bauer").balance, "Maria: 50 − 8,40 − 3,41 laut Buchungen, 23,50 laut Zähler")
            money(-18.0, members.getValue("Jonas Prenn").balance, "Jonas: Buchungen und Zähler waren sich einig")
            money(5.0, members.getValue("Alter Herr").balance, "Guthaben ohne jede Buchung")

            val categories = repository.allCategories.first().associateBy { it.name }
            assertEquals(categories.getValue("Aktive").id, members.getValue("Maria Bauer").categoryId)
            assertNull(members.getValue("Jonas Prenn").categoryId)
            money(-20.0, categories.getValue("Aktive").negativeBalanceLimit, "Limit")
            assertEquals(1_789_000_400_000, members.getValue("Maria Bauer").lastUsedTimestamp, "zuletzt benutzt = letzte Buchung")

            // ----------------------------------------------------------- Buchungen
            val transactions = repository.allTransactions.first()
            transactions.forEach { tx ->
                assertTrue(Ids.isValid(tx.id), "Schlüssel der Buchung ${tx.productName}")
                assertTrue(Ids.isValid(tx.transactionGroupId), "Gruppe der Buchung ${tx.productName}: ${tx.transactionGroupId}")
                assertTrue(Ids.isValid(tx.productId), "Produktverweis der Buchung ${tx.productName}")
            }
            // 11 alte Zeilen, dazu je eine Übernahme für Maria und den Alten Herrn; Jonas braucht keine.
            assertEquals(13, transactions.size)
            val carried = transactions.filter { it.paymentType == "CORRECTION" }
            assertEquals(setOf("Maria Bauer", "Alter Herr"), carried.map { it.memberName }.toSet())
            money(-14.69, carried.first { it.memberName == "Maria Bauer" }.price, "Differenz Zähler − Buchungen")
            assertTrue(carried.all { it.productId == Ledger.TOPUP_REF && it.note!!.contains("übernommen") })

            assertEquals(Ledger.TOPUP_REF, transactions.first { it.productName == "Guthabenaufladung" }.productId)
            assertEquals(Ledger.MANUAL_REF, transactions.first { it.productName == "Pfand" }.productId)
            assertEquals(Ledger.TIP_REF, transactions.first { it.productName == "Trinkgeld" }.productId)
            money(0.39, transactions.first { it.productName == "Radler (0,5l)" }.discountAmount, "Rabatt 0,386 auf Cent gerundet")

            val products = repository.allProductsWithVariants.first().associateBy { it.product.name }
            val beerSales = transactions.filter { it.productName == "Weißbier 0,5l" }
            assertTrue(beerSales.isNotEmpty() && beerSales.all { it.productId == products.getValue("Weißbier 0,5l").product.id })

            val orphanMember = transactions.first { it.memberName == "Ex-Mitglied" }
            assertNull(orphanMember.memberId, "hart gelöschtes Mitglied: Name bleibt, Verweis nicht")
            val legacyGroup = transactions.filter { it.productName.startsWith("Altbestand") }.map { it.transactionGroupId }.toSet()
            assertEquals(1, legacyGroup.size, "zwei Zeilen desselben alten Vorgangs bleiben eine Gruppe")
            val sameDeletedProduct = transactions.filter { it.productName.startsWith("Altbestand") }.map { it.productId }.toSet()
            assertEquals(1, sameDeletedProduct.size, "ein gelöschtes Produkt bleibt ein Produkt")

            // ------------------------------------------------- Produkte und Rezepturen
            assertEquals(setOf("0,3l", "0,5l"), products.getValue("Radler").variants.map { it.name }.toSet())
            val items = repository.allStockItems.first().associateBy { it.name }
            assertEquals(setOf("Bier", "Soda", "Bratwurst"), items.keys, "der Grabstein für 'Chips' bleibt unsichtbar")
            val radlerRecipe = repository.componentsFor(products.getValue("Radler").product.id)
            assertEquals(setOf(items.getValue("Bier").id, items.getValue("Soda").id), radlerRecipe.map { it.stockItemId }.toSet())

            // -------------------------------------------------------------- Keller
            money(37.0, items.getValue("Bratwurst").simpleQuantity, "60 geliefert, Zähler stand auf 37")
            val types = repository.allContainerTypes.first().associateBy { it.label }
            assertEquals(2, types.getValue("50 l Fass").fullCount, "4 geliefert, 2 angestochen")
            assertEquals(0, types.getValue("30 l Fass").fullCount)
            assertEquals(1, types.getValue("20 l Fass").fullCount, "Zähler sagte 1, ohne Wareneingang und mit einem Anstich")

            val tapped = repository.allTappedContainers.first()
            assertEquals(3, tapped.size)
            val emptied = tapped.single { it.closeReason == ContainerCloseReason.EMPTIED }
            val open = tapped.single { it.isOpen }
            val spoiled = tapped.single { it.closeReason == ContainerCloseReason.SPOILED }
            money(47.0, emptied.drawn, "das Ertragsgedächtnis des leeren Fasses")
            money(12.5, open.drawn, "das Fass am Hahn")
            money(8.0, spoiled.drawn, "das verdorbene Fass")
            money(11.0, spoiled.discardedVolume, "Verderb")
            money(48.0, Inventory.yieldFor(types.getValue("50 l Fass"), tapped).perContainer, "(49 + 47) / 2 wie vor der Umstellung")

            val entries = repository.allStockEntries.first()
            val kegDelivery = entries.single { it.unitLabel == "50 l Fass" && it.source == StockEntrySource.MANUAL }
            assertEquals(types.getValue("50 l Fass").id, kegDelivery.containerTypeId, "Gebindegröße aus dem Text wiedererkannt")
            val delivery = repository.allDeliveries.first().single()
            assertEquals("file:///bon.jpg", delivery.photoUri)
            assertEquals(delivery.id, kegDelivery.deliveryId)
            assertNull(entries.single { it.itemName == "Senf" }.deliveryId, "der Beleg dazu wurde einmal gelöscht")
            assertNotNull(entries.single { it.itemName == "Chips" }.stockItemId)

            // ------------------------------------------------------------ Abgleich
            assertEquals(0, db.syncDao().pendingCount(), "ohne Kopplung keine Warteschlange")
            assertNull(db.syncDao().state("enabled"))
        } finally {
            db.close()
        }

        // Zweites Öffnen: Room darf das Schema nicht beanstanden und nichts doppelt umstellen.
        db = open(path)
        try {
            assertEquals(13, AppRepository(db).allTransactions.first().size)
        } finally {
            db.close()
        }
    }

    private fun open(path: String): AppDatabase =
        Room.databaseBuilder<AppDatabase>(name = path)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

    /** Schema 10 wörtlich, wie Room es anlegt — zwei Tabellen in der Fassung, die ein über 7→10 gewandertes Gerät hat. */
    private fun createVersion10(path: String) {
        val connection = BundledSQLiteDriver().open(path)
        try {
            (SCHEMA_10 + FIXTURE).forEach { connection.execSQL(it) }
            connection.execSQL("PRAGMA user_version = 10")
        } finally {
            connection.close()
        }
    }

    private companion object {
        val SCHEMA_10 = listOf(
            "CREATE TABLE `products` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `price` REAL NOT NULL, `category` TEXT NOT NULL, `imageUrl` TEXT, `hasVariants` INTEGER NOT NULL, `servingSize` REAL NOT NULL)",
            "CREATE TABLE `product_variants` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `productId` INTEGER NOT NULL, `name` TEXT NOT NULL, `price` REAL NOT NULL, `servingSize` REAL, FOREIGN KEY(`productId`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX `index_product_variants_productId` ON `product_variants` (`productId`)",
            "CREATE TABLE `member_categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `negativeBalanceLimit` REAL NOT NULL)",
            "CREATE TABLE `members` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `balance` REAL NOT NULL, `categoryId` INTEGER, `lastUsedTimestamp` INTEGER NOT NULL, FOREIGN KEY(`categoryId`) REFERENCES `member_categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
            "CREATE INDEX `index_members_categoryId` ON `members` (`categoryId`)",
            "CREATE TABLE `transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `transactionGroupId` TEXT NOT NULL, `memberId` INTEGER, `memberName` TEXT, `productId` INTEGER NOT NULL, `productName` TEXT NOT NULL, `productCategory` TEXT NOT NULL, `price` REAL NOT NULL, `quantity` INTEGER NOT NULL, `discountAmount` REAL NOT NULL, `paymentType` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `isRefund` INTEGER NOT NULL, `note` TEXT)",
            "CREATE INDEX `index_transactions_memberId` ON `transactions` (`memberId`)",
            "CREATE INDEX `index_transactions_timestamp` ON `transactions` (`timestamp`)",
            // Über MIGRATION_8_9 und 9_10 entstanden, mit DEFAULT-Klauseln:
            "CREATE TABLE `stock_entries` (`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, `stockItemId` INTEGER NOT NULL, `itemName` TEXT NOT NULL, `quantity` REAL NOT NULL, `unitLabel` TEXT NOT NULL, `totalCost` REAL, `note` TEXT, `source` TEXT NOT NULL DEFAULT 'MANUAL', `timestamp` INTEGER NOT NULL, `deliveryId` INTEGER)",
            "CREATE INDEX `index_stock_entries_stockItemId` ON `stock_entries` (`stockItemId`)",
            "CREATE INDEX `index_stock_entries_timestamp` ON `stock_entries` (`timestamp`)",
            "CREATE INDEX `index_stock_entries_deliveryId` ON `stock_entries` (`deliveryId`)",
            "CREATE TABLE `stock_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `unit` TEXT NOT NULL, `tracking` TEXT NOT NULL, `simpleQuantity` REAL NOT NULL, `minLevel` REAL NOT NULL)",
            "CREATE TABLE `container_types` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `stockItemId` INTEGER NOT NULL, `label` TEXT NOT NULL, `nominalSize` REAL NOT NULL, `initialYieldEstimate` REAL NOT NULL, `fullCount` INTEGER NOT NULL, FOREIGN KEY(`stockItemId`) REFERENCES `stock_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX `index_container_types_stockItemId` ON `container_types` (`stockItemId`)",
            "CREATE TABLE `tapped_containers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `containerTypeId` INTEGER NOT NULL, `drawn` REAL NOT NULL, `openedAt` INTEGER NOT NULL, `closedAt` INTEGER, `closeReason` TEXT, `discardedVolume` REAL NOT NULL, `note` TEXT, FOREIGN KEY(`containerTypeId`) REFERENCES `container_types`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX `index_tapped_containers_containerTypeId` ON `tapped_containers` (`containerTypeId`)",
            "CREATE INDEX `index_tapped_containers_openedAt` ON `tapped_containers` (`openedAt`)",
            "CREATE TABLE `product_components` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `productId` INTEGER NOT NULL, `stockItemId` INTEGER NOT NULL, `quantityPerUnit` REAL NOT NULL, FOREIGN KEY(`productId`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`stockItemId`) REFERENCES `stock_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX `index_product_components_productId` ON `product_components` (`productId`)",
            "CREATE INDEX `index_product_components_stockItemId` ON `product_components` (`stockItemId`)",
            "CREATE TABLE `deliveries` (`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, `supplier` TEXT NOT NULL DEFAULT '', `receiptTotal` REAL, `photoUri` TEXT, `note` TEXT, `timestamp` INTEGER NOT NULL)",
            "CREATE INDEX `index_deliveries_timestamp` ON `deliveries` (`timestamp`)",
            "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        )

        private const val T = 1_789_000_000_000

        val FIXTURE = listOf(
            "INSERT INTO member_categories (id, name, negativeBalanceLimit) VALUES (1, 'Aktive', -20.0), (2, 'Gäste', 0.0)",
            "INSERT INTO members (id, name, balance, categoryId, lastUsedTimestamp) VALUES " +
                "(1, 'Maria Bauer', 23.5, 1, ${T + 400_000}), (2, 'Jonas Prenn', -18.0, NULL, 0), (3, 'Alter Herr', 5.0, 2, 0)",

            "INSERT INTO products (id, name, price, category, imageUrl, hasVariants, servingSize) VALUES " +
                "(5, 'Weißbier 0,5l', 4.2, 'Getränke', NULL, 0, 0.5), (6, 'Radler', 0.0, 'Getränke', NULL, 1, 0.5), (7, 'Bratwurst', 3.5, 'Küche', NULL, 0, 1.0)",
            "INSERT INTO product_variants (id, productId, name, price, servingSize) VALUES (1, 6, '0,3l', 2.9, 0.33), (2, 6, '0,5l', 3.8, 0.5)",

            "INSERT INTO stock_items (id, name, unit, tracking, simpleQuantity, minLevel) VALUES " +
                "(1, 'Bier', 'l', 'CONTAINER', 0.0, 30.0), (2, 'Soda', 'l', 'CONTAINER', 0.0, 10.0), (3, 'Bratwurst', 'Stk', 'SIMPLE', 37.0, 10.0)",
            "INSERT INTO container_types (id, stockItemId, label, nominalSize, initialYieldEstimate, fullCount) VALUES " +
                "(1, 1, '50 l Fass', 50.0, 49.0, 2), (2, 1, '30 l Fass', 30.0, 29.2, 0), (3, 2, '20 l Fass', 20.0, 19.2, 1)",
            "INSERT INTO tapped_containers (id, containerTypeId, drawn, openedAt, closedAt, closeReason, discardedVolume, note) VALUES " +
                "(1, 1, 47.0, ${T + 10_000}, ${T + 200_000}, 'EMPTIED', 0.0, NULL), " +
                "(2, 1, 12.5, ${T + 200_000}, NULL, NULL, 0.0, NULL), " +
                "(3, 3, 8.0, ${T + 20_000}, ${T + 90_000}, 'SPOILED', 11.0, 'stand in der Sonne')",
            "INSERT INTO product_components (id, productId, stockItemId, quantityPerUnit) VALUES (1, 5, 1, 1.0), (2, 6, 1, 0.5), (3, 6, 2, 0.5), (4, 7, 3, 1.0)",

            "INSERT INTO deliveries (id, supplier, receiptTotal, photoUri, note, timestamp) VALUES (1, 'Getränke Huber', 412.8, 'file:///bon.jpg', NULL, ${T + 5_000})",
            "INSERT INTO stock_entries (id, stockItemId, itemName, quantity, unitLabel, totalCost, note, source, timestamp, deliveryId) VALUES " +
                "(1, 1, 'Bier', 4.0, '50 l Fass', 380.0, NULL, 'MANUAL', ${T + 5_000}, 1), " +
                "(2, 3, 'Bratwurst', 60.0, 'Stk', 32.8, NULL, 'MANUAL', ${T + 5_000}, 1), " +
                "(3, 99, 'Chips', 12.0, 'Stk', NULL, 'Artikel später gelöscht', 'MANUAL', ${T + 6_000}, NULL), " +
                "(4, 3, 'Senf', 0.0, 'Stk', 2.5, NULL, 'MANUAL', ${T + 7_000}, 77)",

            "INSERT INTO transactions (id, transactionGroupId, memberId, memberName, productId, productName, productCategory, price, quantity, discountAmount, paymentType, timestamp, isRefund, note) VALUES " +
                "(1, '018f2b6c-7d1e-7a00-8000-000000000001', 1, 'Maria Bauer', -1, 'Guthabenaufladung', 'Aufladung', 50.0, 1, 0.0, 'CASH', ${T + 100_000}, 0, NULL), " +
                "(2, '018f2b6c-7d1e-7a00-8000-000000000002', 1, 'Maria Bauer', 5, 'Weißbier 0,5l', 'Getränke', 4.2, 2, 0.0, 'MEMBER_BALANCE', ${T + 300_000}, 0, NULL), " +
                "(3, '018F2B6C-7D1E-7A00-8000-000000000003', 1, 'Maria Bauer', 6, 'Radler (0,5l)', 'Getränke', 3.8, 1, 0.386, 'MEMBER_BALANCE', ${T + 400_000}, 0, NULL), " +
                "(4, '018f2b6c-7d1e-7a00-8000-000000000004', NULL, NULL, 5, 'Weißbier 0,5l', 'Getränke', 4.2, 1, 0.0, 'CASH', ${T + 410_000}, 0, NULL), " +
                "(5, '018f2b6c-7d1e-7a00-8000-000000000004', NULL, NULL, -3, 'Trinkgeld', 'Trinkgeld', 1.0, 1, 0.0, 'CARD', ${T + 410_000}, 0, NULL), " +
                "(6, '018f2b6c-7d1e-7a00-8000-000000000006', NULL, NULL, -2, 'Pfand', 'Manuell', 3.5, 1, 0.0, 'CASH', ${T + 420_000}, 0, NULL), " +
                "(7, '018f2b6c-7d1e-7a00-8000-000000000007', 2, 'Jonas Prenn', 7, 'Bratwurst', 'Küche', 3.5, 4, 0.0, 'MEMBER_BALANCE', ${T + 430_000}, 0, NULL), " +
                "(8, '018f2b6c-7d1e-7a00-8000-000000000008', 2, 'Jonas Prenn', 5, 'Weißbier 0,5l', 'Getränke', 4.0, 1, 0.0, 'MEMBER_BALANCE', ${T + 440_000}, 0, NULL), " +
                "(9, '018f2b6c-7d1e-7a00-8000-000000000009', 42, 'Ex-Mitglied', 5, 'Weißbier 0,5l', 'Getränke', 4.2, 1, 0.0, 'MEMBER_BALANCE', ${T + 450_000}, 0, NULL), " +
                "(10, 'vorgang-2024-17', NULL, NULL, 999, 'Altbestand Limo', 'Getränke', 2.0, 1, 0.0, 'CASH', ${T + 1_000}, 0, NULL), " +
                "(11, 'vorgang-2024-17', NULL, NULL, 999, 'Altbestand Limo groß', 'Getränke', 3.0, 1, 0.0, 'CASH', ${T + 1_000}, 0, NULL)",
        )
    }
}
