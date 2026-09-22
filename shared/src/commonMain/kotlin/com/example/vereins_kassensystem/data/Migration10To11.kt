package com.example.vereins_kassensystem.data

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import com.example.vereins_kassensystem.platform.Ids
import com.example.vereins_kassensystem.platform.nowMillis
import com.example.vereins_kassensystem.ui.format.Money
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Schema 10 → 11: die drei Brüche aus Kapitel 2 der Spezifikation, auf einem Gerät mit
 * echten Daten.
 *
 * **Schlüssel.** Jede Zeile bekommt eine UUID, jeder Verweis wird über die Abbildung
 * alt → neu umgeschrieben. Zeilen mit Zeitstempel bekommen einen Schlüssel, der diese Zeit
 * trägt, damit die Historie nach Schlüssel sortiert chronologisch bleibt.
 *
 * **Saldo und Bestand.** `members.balance`, `stock_items.simpleQuantity`,
 * `container_types.fullCount` und `tapped_containers.drawn` verschwinden; die Werte
 * ergeben sich künftig aus Buchungen, Wareneingängen, Anstichen und Lagerabgängen. Weil
 * die alten Zähler mehr wussten als die Bücher — frühere App-Stände ließen Aufladungen
 * ohne Buchung zu, und Verbrauch wurde nie einzeln festgehalten —, schreibt die
 * Umstellung je Mitglied, Artikel und Gebinde eine **Übernahmebuchung** über genau die
 * Differenz. Danach zeigt die App am Umstellungstag dieselben Zahlen wie davor, und jede
 * Zahl lässt sich auf Zeilen zurückführen.
 *
 * **Löschen.** Jede Tabelle bekommt `deleted`, `deletedAt`, `serverUpdatedAt`; die
 * Fremdschlüssel entfallen (siehe `StockItem.kt`). Was früher hart gelöscht wurde und
 * noch in Buchungen oder Wareneingängen steht, wird aufgefangen: ein verschwundenes
 * Mitglied wird zu „kein Mitglied" (der Name bleibt als Schnappschuss), ein verschwundener
 * Lagerartikel zu einer als gelöscht markierten Zeile, damit der Server den Verweis
 * annimmt.
 *
 * Beträge werden dabei auf Cent gerundet. Der Server führt NUMERIC(12,2); ein Rabatt von
 * 1,386 € wäre sonst hier etwas anderes als dort.
 */
object Migration10To11 : Migration(10, 11) {
    override fun migrate(connection: SQLiteConnection) {
        SchemaUpgrade11(connection, nowMillis()).run()
    }
}

internal class SchemaUpgrade11(private val db: SQLiteConnection, private val now: Long) {

    private class StoredMember(val newId: String, val name: String, val balance: Double)
    private class StoredItem(val newId: String, val name: String, val unit: String, val container: Boolean, val quantity: Double)
    private class StoredType(val newId: String, val itemNewId: String, val label: String, val fullCount: Long)

    private val categories = HashMap<Long, String>()
    private val members = HashMap<Long, StoredMember>()
    private val products = HashMap<Long, String>()
    private val orphanProducts = HashMap<Long, String>()
    private val items = HashMap<Long, StoredItem>()
    private val types = HashMap<Long, StoredType>()
    private val deliveries = HashMap<Long, String>()
    private val groupIds = HashMap<String, String>()

    /** Was die Buchungen je Mitglied ergeben, nach `Ledger` gerechnet. */
    private val bookedBalance = HashMap<String, Double>()

    /** Was die Wareneingänge je Stückartikel bzw. je Gebindegröße ergeben. */
    private val receivedSimple = HashMap<String, Double>()
    private val receivedContainers = HashMap<String, Double>()
    private val tappedPerType = HashMap<String, Long>()

    fun run() {
        // Wie in 8 → 9: Beim Abräumen der alten Tabellen sollen keine Kaskaden laufen.
        db.execSQL("PRAGMA defer_foreign_keys = TRUE")
        NEW_TABLES.forEach { db.execSQL(it) }

        copyCategories()
        copyMembers()
        copyProducts()
        copyVariants()
        copyStockItems()
        copyContainerTypes()
        copyComponents()
        copyDeliveries()
        copyStockEntries()
        copyTappedContainers()
        copyTransactions()

        carryOverBalances()
        carryOverStock()

        // Kinder vor Eltern, damit weder CASCADE noch RESTRICT der alten Tabellen greift.
        OLD_TABLES_CHILDREN_FIRST.forEach { db.execSQL("DROP TABLE `$it`") }
        OLD_TABLES_CHILDREN_FIRST.forEach { db.execSQL("ALTER TABLE `${it}_new` RENAME TO `$it`") }
        // Erst jetzt: Indexnamen gelten in SQLite datenbankweit, und die alten hießen genauso.
        INDICES.forEach { db.execSQL(it) }
    }

    // ------------------------------------------------------------ Stammdaten

    private fun copyCategories() = insertInto(
        "INSERT INTO `member_categories_new` (`id`,`name`,`negativeBalanceLimit`,`deleted`) VALUES (?,?,?,0)"
    ) { insert ->
        db.forEachRow("SELECT id, name, negativeBalanceLimit FROM member_categories") { row ->
            val id = Ids.new()
            categories[row.long("id")] = id
            insert(id, row.text("name"), Money.cents(row.double("negativeBalanceLimit")))
        }
    }

    private fun copyMembers() = insertInto(
        "INSERT INTO `members_new` (`id`,`name`,`categoryId`,`deleted`) VALUES (?,?,?,0)"
    ) { insert ->
        db.forEachRow("SELECT id, name, balance, categoryId FROM members") { row ->
            val id = Ids.new()
            val name = row.text("name")
            members[row.long("id")] = StoredMember(id, name, row.double("balance"))
            insert(id, name, row.longOrNull("categoryId")?.let { categories[it] })
        }
    }

    private fun copyProducts() = insertInto(
        "INSERT INTO `products_new` (`id`,`name`,`price`,`category`,`imageUrl`,`hasVariants`,`servingSize`,`deleted`) VALUES (?,?,?,?,?,?,?,0)"
    ) { insert ->
        db.forEachRow("SELECT id, name, price, category, imageUrl, hasVariants, servingSize FROM products") { row ->
            val id = Ids.new()
            products[row.long("id")] = id
            insert(
                id, row.text("name"), Money.cents(row.double("price")), row.text("category"),
                row.textOrNull("imageUrl"), row.long("hasVariants"), row.double("servingSize")
            )
        }
    }

    private fun copyVariants() = insertInto(
        "INSERT INTO `product_variants_new` (`id`,`productId`,`name`,`price`,`servingSize`,`deleted`) VALUES (?,?,?,?,?,0)"
    ) { insert ->
        db.forEachRow("SELECT productId, name, price, servingSize FROM product_variants") { row ->
            val productId = products[row.long("productId")] ?: return@forEachRow
            insert(Ids.new(), productId, row.text("name"), Money.cents(row.double("price")), row.doubleOrNull("servingSize"))
        }
    }

    private fun copyStockItems() = insertInto(STOCK_ITEM_INSERT) { insert ->
        db.forEachRow("SELECT id, name, unit, tracking, simpleQuantity, minLevel FROM stock_items") { row ->
            val id = Ids.new()
            val tracking = row.text("tracking")
            items[row.long("id")] = StoredItem(id, row.text("name"), row.text("unit"), tracking == "CONTAINER", row.double("simpleQuantity"))
            insert(id, row.text("name"), row.text("unit"), tracking, row.double("minLevel"), 0L, null)
        }
    }

    private fun copyContainerTypes() = insertInto(
        "INSERT INTO `container_types_new` (`id`,`stockItemId`,`label`,`nominalSize`,`initialYieldEstimate`,`deleted`) VALUES (?,?,?,?,?,0)"
    ) { insert ->
        db.forEachRow("SELECT id, stockItemId, label, nominalSize, initialYieldEstimate, fullCount FROM container_types") { row ->
            val item = items[row.long("stockItemId")] ?: return@forEachRow
            val id = Ids.new()
            types[row.long("id")] = StoredType(id, item.newId, row.text("label"), row.long("fullCount"))
            insert(id, item.newId, row.text("label"), row.double("nominalSize"), row.double("initialYieldEstimate"))
        }
    }

    private fun copyComponents() = insertInto(
        "INSERT INTO `product_components_new` (`id`,`productId`,`stockItemId`,`quantityPerUnit`,`deleted`) VALUES (?,?,?,?,0)"
    ) { insert ->
        db.forEachRow("SELECT productId, stockItemId, quantityPerUnit FROM product_components") { row ->
            val productId = products[row.long("productId")] ?: return@forEachRow
            val item = items[row.long("stockItemId")] ?: return@forEachRow
            insert(Ids.new(), productId, item.newId, row.double("quantityPerUnit"))
        }
    }

    // --------------------------------------------------------- Bewegungsdaten

    private fun copyDeliveries() = insertInto(
        "INSERT INTO `deliveries_new` (`id`,`supplier`,`receiptTotal`,`photoUri`,`photoKey`,`note`,`timestamp`,`deleted`) VALUES (?,?,?,?,NULL,?,?,0)"
    ) { insert ->
        db.forEachRow("SELECT id, supplier, receiptTotal, photoUri, note, timestamp FROM deliveries") { row ->
            val timestamp = row.long("timestamp")
            val id = Ids.at(timestamp)
            deliveries[row.long("id")] = id
            insert(id, row.text("supplier"), row.doubleOrNull("receiptTotal")?.let(Money::cents), row.textOrNull("photoUri"), row.textOrNull("note"), timestamp)
        }
    }

    private fun copyStockEntries() {
        val tombstones = db.prepare(STOCK_ITEM_INSERT)
        try {
            insertInto(STOCK_ENTRY_INSERT) { insert ->
                db.forEachRow("SELECT stockItemId, itemName, quantity, unitLabel, totalCost, note, source, timestamp, deliveryId FROM stock_entries") { row ->
                    val oldItemId = row.long("stockItemId")
                    val itemName = row.text("itemName")
                    val unitLabel = row.text("unitLabel")
                    // Der Artikel wurde irgendwann hart gelöscht. Die Zeile bleibt lesbar (der
                    // Name ist ein Schnappschuss), braucht aber ein Ziel, das der Server kennt.
                    val item = items.getOrPut(oldItemId) {
                        StoredItem(Ids.new(), itemName, unitLabel, container = false, quantity = 0.0).also {
                            tombstones.bindAll(it.newId, it.name, it.unit, "SIMPLE", 0.0, 1L, now)
                            tombstones.step()
                            tombstones.reset()
                        }
                    }
                    // Die Gebindegröße stand bisher nur als Text da.
                    val type = if (item.container) types.values.firstOrNull { it.itemNewId == item.newId && it.label == unitLabel } else null
                    val quantity = row.double("quantity")
                    if (type != null) receivedContainers.add(type.newId, quantity)
                    else if (!item.container) receivedSimple.add(item.newId, quantity)

                    val timestamp = row.long("timestamp")
                    insert(
                        Ids.at(timestamp), item.newId, itemName, quantity, unitLabel,
                        row.doubleOrNull("totalCost")?.let(Money::cents), row.textOrNull("note"), row.text("source"),
                        timestamp, row.longOrNull("deliveryId")?.let { deliveries[it] }, type?.newId
                    )
                }
            }
        } finally {
            tombstones.close()
        }
    }

    /**
     * Anstiche, und mit ihnen das Gedächtnis der Erträge: Was aus einem Fass gezapft wurde,
     * wird zu einem Lagerabgang zum Zeitpunkt des Anstichs — er liegt damit im Zeitfenster
     * genau dieses Fasses und nirgends sonst.
     */
    private fun copyTappedContainers() = insertInto(
        "INSERT INTO `tapped_containers_new` (`id`,`containerTypeId`,`openedAt`,`closedAt`,`closeReason`,`discardedVolume`,`note`,`deleted`) VALUES (?,?,?,?,?,?,?,0)"
    ) { insert ->
        insertInto(STOCK_DRAW_INSERT) { insertDraw ->
            db.forEachRow("SELECT containerTypeId, drawn, openedAt, closedAt, closeReason, discardedVolume, note FROM tapped_containers") { row ->
                val type = types[row.long("containerTypeId")] ?: return@forEachRow
                val openedAt = row.long("openedAt")
                // Ein Fenster der Länge null fasste den eigenen Abgang nicht.
                val closedAt = row.longOrNull("closedAt")?.let { if (it <= openedAt) openedAt + 1 else it }
                tappedPerType[type.newId] = (tappedPerType[type.newId] ?: 0L) + 1L
                insert(Ids.at(openedAt), type.newId, openedAt, closedAt, row.textOrNull("closeReason"), row.double("discardedVolume"), row.textOrNull("note"))

                val drawn = row.double("drawn")
                if (drawn > 0.0) {
                    insertDraw(Ids.at(openedAt), type.itemNewId, null, drawn, openedAt, "Übernahme bei der Umstellung: bis dahin gezapft")
                }
            }
        }
    }

    private fun copyTransactions() = insertInto(TRANSACTION_INSERT) { insert ->
        db.forEachRow(
            "SELECT transactionGroupId, memberId, memberName, productId, productName, productCategory, price, quantity, " +
                "discountAmount, paymentType, timestamp, isRefund, note FROM transactions"
        ) { row ->
            val timestamp = row.long("timestamp")
            val oldGroup = row.text("transactionGroupId")
            val group = if (Ids.isValid(oldGroup)) oldGroup.lowercase() else groupIds.getOrPut(oldGroup) { Ids.at(timestamp) }
            val member = row.longOrNull("memberId")?.let { members[it] }
            val productRef = when (val old = row.long("productId")) {
                -1L -> Ledger.TOPUP_REF
                -2L -> Ledger.MANUAL_REF
                -3L -> Ledger.TIP_REF
                else -> products[old] ?: orphanProducts.getOrPut(old) { Ids.new() }
            }
            val price = Money.cents(row.double("price"))
            val quantity = row.long("quantity")
            val discount = Money.cents(row.double("discountAmount"))
            val paymentType = row.text("paymentType")
            val isRefund = row.long("isRefund") != 0L

            if (member != null) {
                bookedBalance.add(member.newId, Ledger.balanceEffect(productRef, paymentType, price, quantity.toInt(), discount, isRefund))
            }
            insert(
                Ids.at(timestamp), group, member?.newId, row.textOrNull("memberName"), productRef, row.text("productName"),
                row.text("productCategory"), price, quantity, discount, paymentType, timestamp, if (isRefund) 1L else 0L,
                row.textOrNull("note")
            )
        }
    }

    // ------------------------------------------------------ Übernahmebuchungen

    private fun carryOverBalances() = insertInto(TRANSACTION_INSERT) { insert ->
        members.values.forEach { member ->
            val stored = Money.cents(member.balance)
            val booked = Money.cents(bookedBalance[member.newId] ?: 0.0)
            val difference = Money.cents(stored - booked)
            if (abs(difference) < 0.005) return@forEach
            insert(
                Ids.at(now), Ids.at(now), member.newId, member.name, Ledger.TOPUP_REF, "Übernahme bei der Umstellung",
                "Guthaben", difference, 1L, 0.0, "CORRECTION", now, 0L,
                "Saldo ${Money.format(stored)} beim Wechsel auf abgeleitete Salden übernommen; " +
                    "die Buchungen ergaben ${Money.format(booked)}."
            )
        }
    }

    private fun carryOverStock() = insertInto(STOCK_ENTRY_INSERT) { insertEntry ->
        insertInto(STOCK_DRAW_INSERT) { insertDraw ->
            items.values.filter { !it.container }.forEach { item ->
                val difference = item.quantity - (receivedSimple[item.newId] ?: 0.0)
                when {
                    abs(difference) < 1e-9 -> Unit
                    // Verbraucht wurde mehr, als je ein Abgang festhielt — also alles bisher.
                    difference < 0 -> insertDraw(Ids.at(now), item.newId, null, -difference, now, "Übernahme bei der Umstellung: Verbrauch bis dahin")
                    else -> insertEntry(
                        Ids.at(now), item.newId, item.name, difference, item.unit, null,
                        "Übernahme bei der Umstellung: Bestand ohne Wareneingang", "CORRECTION", now, null, null
                    )
                }
            }
            types.values.forEach { type ->
                val received = (receivedContainers[type.newId] ?: 0.0).roundToLong()
                val difference = type.fullCount - (received - (tappedPerType[type.newId] ?: 0L))
                if (difference == 0L) return@forEach
                val item = items.values.first { it.newId == type.itemNewId }
                insertEntry(
                    Ids.at(now), type.itemNewId, item.name, difference.toDouble(), type.label, null,
                    "Übernahme bei der Umstellung: volle Gebinde laut Zähler", "CORRECTION", now, null, type.newId
                )
            }
        }
    }

    // ---------------------------------------------------------------- Helfer

    private fun HashMap<String, Double>.add(key: String, value: Double) {
        this[key] = (this[key] ?: 0.0) + value
    }

    /** Ein vorbereitetes INSERT, das Zeile um Zeile dieselbe Anweisung neu bindet. */
    private class Inserter(private val statement: SQLiteStatement) {
        operator fun invoke(vararg values: Any?) {
            statement.bindAll(*values)
            statement.step()
            statement.reset()
        }
    }

    private inline fun insertInto(sql: String, block: (Inserter) -> Unit) {
        val statement = db.prepare(sql)
        try {
            block(Inserter(statement))
        } finally {
            statement.close()
        }
    }

    private class Row(private val statement: SQLiteStatement, private val index: Map<String, Int>) {
        private fun at(name: String) = index[name] ?: error("Spalte '$name' fehlt in der alten Tabelle")
        fun long(name: String): Long = statement.getLong(at(name))
        fun longOrNull(name: String): Long? = at(name).let { if (statement.isNull(it)) null else statement.getLong(it) }
        fun double(name: String): Double = statement.getDouble(at(name))
        fun doubleOrNull(name: String): Double? = at(name).let { if (statement.isNull(it)) null else statement.getDouble(it) }
        fun text(name: String): String = at(name).let { if (statement.isNull(it)) "" else statement.getText(it) }
        fun textOrNull(name: String): String? = at(name).let { if (statement.isNull(it)) null else statement.getText(it) }
    }

    private inline fun SQLiteConnection.forEachRow(sql: String, block: (Row) -> Unit) {
        val statement = prepare(sql)
        try {
            val index = (0 until statement.getColumnCount()).associate { statement.getColumnName(it) to it }
            val row = Row(statement, index)
            while (statement.step()) block(row)
        } finally {
            statement.close()
        }
    }

    private companion object {

        fun SQLiteStatement.bindAll(vararg values: Any?) {
            values.forEachIndexed { i, value ->
                when (value) {
                    null -> bindNull(i + 1)
                    is String -> bindText(i + 1, value)
                    is Long -> bindLong(i + 1, value)
                    is Int -> bindLong(i + 1, value.toLong())
                    is Double -> bindDouble(i + 1, value)
                    is Boolean -> bindLong(i + 1, if (value) 1L else 0L)
                    else -> error("Wert vom Typ ${value::class} lässt sich nicht binden")
                }
            }
        }

        const val STOCK_ITEM_INSERT =
            "INSERT INTO `stock_items_new` (`id`,`name`,`unit`,`tracking`,`minLevel`,`deleted`,`deletedAt`) VALUES (?,?,?,?,?,?,?)"

        const val STOCK_ENTRY_INSERT =
            "INSERT INTO `stock_entries_new` (`id`,`stockItemId`,`itemName`,`quantity`,`unitLabel`,`totalCost`,`note`,`source`," +
                "`timestamp`,`deliveryId`,`containerTypeId`,`deleted`) VALUES (?,?,?,?,?,?,?,?,?,?,?,0)"

        const val STOCK_DRAW_INSERT =
            "INSERT INTO `stock_draws` (`id`,`stockItemId`,`transactionId`,`volume`,`timestamp`,`note`,`deleted`) VALUES (?,?,?,?,?,?,0)"

        const val TRANSACTION_INSERT =
            "INSERT INTO `transactions_new` (`id`,`transactionGroupId`,`memberId`,`memberName`,`productId`,`productName`," +
                "`productCategory`,`price`,`quantity`,`discountAmount`,`paymentType`,`timestamp`,`isRefund`,`note`,`deleted`) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)"

        /** Reihenfolge, in der die alten Tabellen fallen: Kinder vor ihren Eltern. */
        val OLD_TABLES_CHILDREN_FIRST = listOf(
            "product_variants", "product_components", "tapped_containers", "container_types", "members",
            "stock_entries", "deliveries", "transactions", "products", "stock_items", "member_categories"
        )

        private const val SYNC = "`deleted` INTEGER NOT NULL, `deletedAt` INTEGER, `serverUpdatedAt` TEXT"

        /**
         * Genau das Schema, das Room aus den Entitäten erzeugt — Room vergleicht nach der
         * Umstellung Spalte für Spalte. Die elf alten Tabellen entstehen als `<name>_new`;
         * was es vorher nicht gab, gleich unter seinem Namen.
         */
        val NEW_TABLES = listOf(
            "CREATE TABLE `products_new` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `price` REAL NOT NULL, `category` TEXT NOT NULL, `imageUrl` TEXT, `hasVariants` INTEGER NOT NULL, `servingSize` REAL NOT NULL, $SYNC, PRIMARY KEY(`id`))",
            "CREATE TABLE `product_variants_new` (`id` TEXT NOT NULL, `productId` TEXT NOT NULL, `name` TEXT NOT NULL, `price` REAL NOT NULL, `servingSize` REAL, $SYNC, PRIMARY KEY(`id`))",
            "CREATE TABLE `members_new` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `categoryId` TEXT, $SYNC, PRIMARY KEY(`id`))",
            "CREATE TABLE `transactions_new` (`id` TEXT NOT NULL, `transactionGroupId` TEXT NOT NULL, `memberId` TEXT, `memberName` TEXT, `productId` TEXT NOT NULL, `productName` TEXT NOT NULL, `productCategory` TEXT NOT NULL, `price` REAL NOT NULL, `quantity` INTEGER NOT NULL, `discountAmount` REAL NOT NULL, `paymentType` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `isRefund` INTEGER NOT NULL, `note` TEXT, $SYNC, PRIMARY KEY(`id`))",
            "CREATE TABLE `member_categories_new` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `negativeBalanceLimit` REAL NOT NULL, $SYNC, PRIMARY KEY(`id`))",
            "CREATE TABLE `stock_entries_new` (`id` TEXT NOT NULL, `stockItemId` TEXT NOT NULL, `itemName` TEXT NOT NULL, `quantity` REAL NOT NULL, `unitLabel` TEXT NOT NULL, `totalCost` REAL, `note` TEXT, `source` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `deliveryId` TEXT, `containerTypeId` TEXT, $SYNC, PRIMARY KEY(`id`))",
            "CREATE TABLE `stock_items_new` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `unit` TEXT NOT NULL, `tracking` TEXT NOT NULL, `minLevel` REAL NOT NULL, $SYNC, PRIMARY KEY(`id`))",
            "CREATE TABLE `container_types_new` (`id` TEXT NOT NULL, `stockItemId` TEXT NOT NULL, `label` TEXT NOT NULL, `nominalSize` REAL NOT NULL, `initialYieldEstimate` REAL NOT NULL, $SYNC, PRIMARY KEY(`id`))",
            "CREATE TABLE `tapped_containers_new` (`id` TEXT NOT NULL, `containerTypeId` TEXT NOT NULL, `openedAt` INTEGER NOT NULL, `closedAt` INTEGER, `closeReason` TEXT, `discardedVolume` REAL NOT NULL, `note` TEXT, $SYNC, PRIMARY KEY(`id`))",
            "CREATE TABLE `product_components_new` (`id` TEXT NOT NULL, `productId` TEXT NOT NULL, `stockItemId` TEXT NOT NULL, `quantityPerUnit` REAL NOT NULL, $SYNC, PRIMARY KEY(`id`))",
            "CREATE TABLE `deliveries_new` (`id` TEXT NOT NULL, `supplier` TEXT NOT NULL, `receiptTotal` REAL, `photoUri` TEXT, `photoKey` TEXT, `note` TEXT, `timestamp` INTEGER NOT NULL, $SYNC, PRIMARY KEY(`id`))",
            "CREATE TABLE `stock_draws` (`id` TEXT NOT NULL, `stockItemId` TEXT NOT NULL, `transactionId` TEXT, `volume` REAL NOT NULL, `timestamp` INTEGER NOT NULL, `note` TEXT, $SYNC, PRIMARY KEY(`id`))",
            "CREATE TABLE `pending_changes` (`seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `changeId` TEXT NOT NULL, `entity` TEXT NOT NULL, `entityId` TEXT NOT NULL, `op` TEXT NOT NULL, `payload` TEXT NOT NULL, `baseUpdatedAt` TEXT, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE `sync_state` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
        )

        val INDICES = listOf(
            "CREATE INDEX `index_product_variants_productId` ON `product_variants` (`productId`)",
            "CREATE INDEX `index_members_categoryId` ON `members` (`categoryId`)",
            "CREATE INDEX `index_transactions_memberId` ON `transactions` (`memberId`)",
            "CREATE INDEX `index_transactions_timestamp` ON `transactions` (`timestamp`)",
            "CREATE INDEX `index_stock_entries_stockItemId` ON `stock_entries` (`stockItemId`)",
            "CREATE INDEX `index_stock_entries_timestamp` ON `stock_entries` (`timestamp`)",
            "CREATE INDEX `index_stock_entries_deliveryId` ON `stock_entries` (`deliveryId`)",
            "CREATE INDEX `index_stock_entries_containerTypeId` ON `stock_entries` (`containerTypeId`)",
            "CREATE INDEX `index_container_types_stockItemId` ON `container_types` (`stockItemId`)",
            "CREATE INDEX `index_tapped_containers_containerTypeId` ON `tapped_containers` (`containerTypeId`)",
            "CREATE INDEX `index_tapped_containers_openedAt` ON `tapped_containers` (`openedAt`)",
            "CREATE INDEX `index_product_components_productId` ON `product_components` (`productId`)",
            "CREATE INDEX `index_product_components_stockItemId` ON `product_components` (`stockItemId`)",
            "CREATE INDEX `index_deliveries_timestamp` ON `deliveries` (`timestamp`)",
            "CREATE INDEX `index_stock_draws_stockItemId_timestamp` ON `stock_draws` (`stockItemId`, `timestamp`)",
            "CREATE INDEX `index_stock_draws_transactionId` ON `stock_draws` (`transactionId`)",
            "CREATE UNIQUE INDEX `index_pending_changes_changeId` ON `pending_changes` (`changeId`)",
        )
    }
}
