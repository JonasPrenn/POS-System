package com.example.vereins_kassensystem.data.repository

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.example.vereins_kassensystem.data.AppDatabase
import com.example.vereins_kassensystem.data.Ledger
import com.example.vereins_kassensystem.data.dao.ProductWithVariants
import com.example.vereins_kassensystem.data.entity.ContainerCloseReason
import com.example.vereins_kassensystem.data.entity.ContainerType
import com.example.vereins_kassensystem.data.entity.ContainerTypeRow
import com.example.vereins_kassensystem.data.entity.Delivery
import com.example.vereins_kassensystem.data.entity.Member
import com.example.vereins_kassensystem.data.entity.MemberCategory
import com.example.vereins_kassensystem.data.entity.MemberRow
import com.example.vereins_kassensystem.data.entity.PendingChange
import com.example.vereins_kassensystem.data.entity.Product
import com.example.vereins_kassensystem.data.entity.ProductComponent
import com.example.vereins_kassensystem.data.entity.ProductVariant
import com.example.vereins_kassensystem.data.entity.StockDraw
import com.example.vereins_kassensystem.data.entity.StockEntry
import com.example.vereins_kassensystem.data.entity.StockEntrySource
import com.example.vereins_kassensystem.data.entity.StockItem
import com.example.vereins_kassensystem.data.entity.StockItemRow
import com.example.vereins_kassensystem.data.entity.StockTracking
import com.example.vereins_kassensystem.data.entity.SyncMeta
import com.example.vereins_kassensystem.data.entity.TappedContainer
import com.example.vereins_kassensystem.data.entity.TappedContainerRow
import com.example.vereins_kassensystem.data.entity.Transaction
import com.example.vereins_kassensystem.data.stock.Inventory
import com.example.vereins_kassensystem.data.sync.RowCodec
import com.example.vereins_kassensystem.data.sync.SyncKeys
import com.example.vereins_kassensystem.data.sync.SyncTables
import com.example.vereins_kassensystem.platform.Ids
import com.example.vereins_kassensystem.platform.nowMillis
import com.example.vereins_kassensystem.ui.format.Money
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs

/**
 * Alles, was die App an Daten liest und schreibt.
 *
 * Seit Schema 11 gelten hier drei Regeln, die der Mehrgerätebetrieb verlangt
 * (Spezifikation, Kapitel 2):
 *
 * **Nichts wird fortgeschrieben.** Saldo und Bestand sind Summen über anfügende Tabellen.
 * Wer einen Deckel belastet, bucht eine Zeile; wer Bestand ändert, bucht einen Wareneingang
 * oder einen Abgang. Ein `balance = balance + x` gibt es nicht mehr.
 *
 * **Gelöscht wird weich.** Eine fehlende Zeile lässt sich nicht übertragen.
 *
 * **Jeder Schreibzugriff ist eine Transaktion, und der Auftrag für den Server gehört dazu.**
 * Buchung und Warteschlangeneintrag stehen beide da oder keiner von beiden. Solange das
 * Gerät nicht gekoppelt ist, entsteht kein Eintrag — die Warteschlange eines Geräts, das
 * nie einen Server sieht, würde sonst ewig wachsen.
 */
class AppRepository(private val database: AppDatabase) {

    private val productDao = database.productDao()
    private val memberDao = database.memberDao()
    private val transactionDao = database.transactionDao()
    private val categoryDao = database.categoryDao()
    private val stockEntryDao = database.stockEntryDao()
    private val stockDao = database.stockDao()
    private val deliveryDao = database.deliveryDao()
    private val syncDao = database.syncDao()

    // ------------------------------------------------------------------ lesen

    val allProducts: Flow<List<Product>> = productDao.getAllProducts()

    val allProductsWithVariants: Flow<List<ProductWithVariants>> =
        combine(productDao.getAllProducts(), productDao.getAllVariants()) { products, variants ->
            val byProduct = variants.groupBy { it.productId }
            products.map { ProductWithVariants(it, byProduct[it.id].orEmpty()) }
        }

    val allMembers: Flow<List<Member>> = memberDao.getAllMembersSortedByUsage()
    val allTransactions: Flow<List<Transaction>> = transactionDao.getAllTransactions()
    val allCategories: Flow<List<MemberCategory>> = categoryDao.getAllCategories()

    val allStockItems: Flow<List<StockItem>> = stockDao.getAllItems()
    val allContainerTypes: Flow<List<ContainerType>> = stockDao.getAllContainerTypes()
    val allTappedContainers: Flow<List<TappedContainer>> = stockDao.getAllTapped()
    val allComponents: Flow<List<ProductComponent>> = stockDao.getAllComponents()
    val allStockEntries: Flow<List<StockEntry>> = stockEntryDao.getAllEntries()
    val allDeliveries: Flow<List<Delivery>> = deliveryDao.getAllDeliveries()

    suspend fun getMember(id: String): Member? = memberDao.getMemberById(id)
    suspend fun getCategoryById(id: String): MemberCategory? = categoryDao.getCategoryById(id)
    suspend fun getCategoryByName(name: String): MemberCategory? = categoryDao.getCategoryByName(name)
    suspend fun componentsFor(productId: String): List<ProductComponent> = stockDao.getComponentsFor(productId)

    // --------------------------------------------------------------- Produkte

    suspend fun insertProduct(product: Product): String = write {
        val row = product.copy(price = Money.cents(product.price))
        productDao.insertProduct(row)
        insert(SyncTables.PRODUCTS, row.id, RowCodec.encode(row))
        row.id
    }

    suspend fun updateProduct(product: Product) = write { updateProductRow(product) }

    /** Ein Produkt verschwindet mit seinen Varianten und seiner Rezeptur. */
    suspend fun deleteProduct(product: Product) = write {
        val now = nowMillis()
        productDao.getVariantsFor(product.id).forEach { variant ->
            productDao.softDeleteVariant(variant.id, now)
            delete(SyncTables.VARIANTS, variant.id, variant.sync.serverUpdatedAt)
        }
        stockDao.getComponentsFor(product.id).forEach { component ->
            stockDao.softDeleteComponent(component.id, now)
            delete(SyncTables.COMPONENTS, component.id, component.sync.serverUpdatedAt)
        }
        val current = productDao.getProduct(product.id) ?: return@write
        productDao.softDeleteProduct(product.id, now)
        delete(SyncTables.PRODUCTS, product.id, current.sync.serverUpdatedAt)
    }

    suspend fun insertVariant(variant: ProductVariant) = write { insertVariantRow(variant) }

    /**
     * Speichert ein Produkt mit Varianten und Rezeptur in einem Zug.
     *
     * Varianten und Rezepturzeilen werden abgeglichen statt gelöscht und neu angelegt:
     * Was bleibt, behält seinen Schlüssel, und der Server bekommt nur, was sich geändert
     * hat — nicht bei jedem Speichern einen Satz Löschungen und Neuanlagen.
     */
    suspend fun saveProduct(
        product: Product,
        variants: List<ProductVariant>,
        components: List<ProductComponent>,
        isNew: Boolean
    ): String = write {
        val now = nowMillis()
        if (isNew || productDao.getProduct(product.id) == null) {
            val row = product.copy(price = Money.cents(product.price))
            productDao.insertProduct(row)
            insert(SyncTables.PRODUCTS, row.id, RowCodec.encode(row))
        } else {
            updateProductRow(product)
        }

        val existingVariants = productDao.getVariantsFor(product.id).associateBy { it.id }
        val wantedVariants = variants.map { it.copy(productId = product.id, price = Money.cents(it.price)) }
        existingVariants.values.filter { old -> wantedVariants.none { it.id == old.id } }.forEach { old ->
            productDao.softDeleteVariant(old.id, now)
            delete(SyncTables.VARIANTS, old.id, old.sync.serverUpdatedAt)
        }
        wantedVariants.forEach { wanted ->
            val old = existingVariants[wanted.id]
            when {
                old == null -> insertVariantRow(wanted)
                old.name != wanted.name || old.price != wanted.price || old.servingSize != wanted.servingSize -> {
                    val row = wanted.copy(sync = old.sync)
                    syncDao.upsertVariant(row)
                    update(SyncTables.VARIANTS, row.id, RowCodec.encode(row), old.sync.serverUpdatedAt)
                }
            }
        }

        replaceComponents(product.id, components, now)
        product.id
    }

    suspend fun setComponents(productId: String, components: List<ProductComponent>) = write {
        replaceComponents(productId, components, nowMillis())
    }

    // ------------------------------------------------------------- Mitglieder

    suspend fun insertMember(member: Member): String = write {
        val row = MemberRow(id = member.id, name = member.name, categoryId = member.categoryId)
        memberDao.insert(row)
        insert(SyncTables.MEMBERS, row.id, RowCodec.encode(row))
        row.id
    }

    suspend fun updateMember(member: Member) = write {
        val current = memberDao.getRow(member.id) ?: return@write
        val row = current.copy(name = member.name, categoryId = member.categoryId)
        memberDao.update(row)
        update(SyncTables.MEMBERS, row.id, RowCodec.encode(row), current.sync.serverUpdatedAt)
    }

    /** Die Buchungen des Mitglieds bleiben; sie tragen seinen Namen als Schnappschuss. */
    suspend fun deleteMember(member: Member) = write {
        val current = memberDao.getRow(member.id) ?: return@write
        memberDao.softDelete(member.id, nowMillis())
        delete(SyncTables.MEMBERS, member.id, current.sync.serverUpdatedAt)
    }

    suspend fun insertCategory(category: MemberCategory): String = write {
        val row = category.copy(negativeBalanceLimit = Money.cents(category.negativeBalanceLimit))
        categoryDao.insert(row)
        insert(SyncTables.CATEGORIES, row.id, RowCodec.encode(row))
        row.id
    }

    suspend fun updateCategory(category: MemberCategory) = write {
        val current = categoryDao.getCategoryById(category.id) ?: return@write
        val row = category.copy(negativeBalanceLimit = Money.cents(category.negativeBalanceLimit), sync = current.sync)
        categoryDao.update(row)
        update(SyncTables.CATEGORIES, row.id, RowCodec.encode(row), current.sync.serverUpdatedAt)
    }

    /**
     * Löscht die Gruppe — außer sie hat noch Mitglieder; dann bleibt sie, und der Aufrufer
     * bekommt false. Früher hat das ein Fremdschlüssel erzwungen, und zwar mit einem
     * Absturz; die Regel ist dieselbe, nur sagt sie es jetzt.
     */
    suspend fun deleteCategory(category: MemberCategory): Boolean = write {
        if (memberDao.countInCategory(category.id) > 0) return@write false
        val current = categoryDao.getCategoryById(category.id) ?: return@write true
        categoryDao.softDelete(category.id, nowMillis())
        delete(SyncTables.CATEGORIES, category.id, current.sync.serverUpdatedAt)
        true
    }

    // ----------------------------------------------------------------- Keller

    suspend fun insertStockItem(item: StockItem): String = write { saveStockItemRow(item) }

    suspend fun updateStockItem(item: StockItem) = write { saveStockItemRow(item) }

    /** Der Artikel verschwindet mit seinen Gebindegrößen und aus allen Rezepturen. */
    suspend fun deleteStockItem(item: StockItem) = write {
        val now = nowMillis()
        stockDao.getContainerTypeRowsFor(item.id).forEach { type ->
            stockDao.softDeleteContainerType(type.id, now)
            delete(SyncTables.CONTAINER_TYPES, type.id, type.sync.serverUpdatedAt)
        }
        stockDao.getComponentsUsing(item.id).forEach { component ->
            stockDao.softDeleteComponent(component.id, now)
            delete(SyncTables.COMPONENTS, component.id, component.sync.serverUpdatedAt)
        }
        val current = stockDao.getItemRow(item.id) ?: return@write
        stockDao.softDeleteItem(item.id, now)
        delete(SyncTables.STOCK_ITEMS, item.id, current.sync.serverUpdatedAt)
    }

    suspend fun insertContainerType(type: ContainerType): String = write { saveContainerTypeRow(type) }

    suspend fun updateContainerType(type: ContainerType) = write { saveContainerTypeRow(type) }

    suspend fun deleteContainerType(type: ContainerType) = write {
        val current = stockDao.getContainerTypeRow(type.id) ?: return@write
        stockDao.softDeleteContainerType(type.id, nowMillis())
        delete(SyncTables.CONTAINER_TYPES, type.id, current.sync.serverUpdatedAt)
    }

    /** Saves an item together with the vessel sizes it arrives in. */
    suspend fun saveItemWithContainers(item: StockItem, types: List<ContainerType>): String = write {
        val id = saveStockItemRow(item)
        val now = nowMillis()
        stockDao.getContainerTypeRowsFor(id)
            .filter { old -> types.none { it.id == old.id } }
            .forEach { old ->
                stockDao.softDeleteContainerType(old.id, now)
                delete(SyncTables.CONTAINER_TYPES, old.id, old.sync.serverUpdatedAt)
            }
        types.forEach { saveContainerTypeRow(it.copy(stockItemId = id)) }
        id
    }

    /** One line of a receipt, as entered in the delivery dialog. */
    data class ReceiptLine(
        val item: StockItem,
        val containerType: ContainerType?,
        val quantity: Double,
        val cost: Double?
    )

    /**
     * Books a whole Kassabon: the receipt itself, its lines, and the stock each moves.
     *
     * One call rather than a loop of single receipts, because a delivery is one event —
     * splitting it would make the photo belong to nothing in particular and leave the
     * money impossible to reconcile against the club account.
     */
    suspend fun bookDelivery(
        supplier: String,
        receiptTotal: Double?,
        photoUri: String?,
        note: String?,
        lines: List<ReceiptLine>
    ): String = write {
        val delivery = Delivery(
            supplier = supplier,
            receiptTotal = receiptTotal?.let(Money::cents),
            photoUri = photoUri,
            note = note
        )
        deliveryDao.insertDelivery(delivery)
        insert(SyncTables.DELIVERIES, delivery.id, RowCodec.encode(delivery))
        lines.forEach { line ->
            insertEntry(
                StockEntry(
                    stockItemId = line.item.id,
                    itemName = line.item.name,
                    quantity = line.quantity,
                    unitLabel = line.containerType?.label ?: line.item.unit,
                    totalCost = line.cost?.let(Money::cents),
                    source = StockEntrySource.MANUAL,
                    timestamp = delivery.timestamp,
                    deliveryId = delivery.id,
                    containerTypeId = line.containerType?.id
                )
            )
        }
        delivery.id
    }

    /** Der Beleg verschwindet, seine Positionen bleiben gebucht — die Ware ist ja da. */
    suspend fun deleteDelivery(delivery: Delivery) = write {
        val current = deliveryDao.getDelivery(delivery.id) ?: return@write
        deliveryDao.softDelete(delivery.id, nowMillis())
        delete(SyncTables.DELIVERIES, delivery.id, current.sync.serverUpdatedAt)
    }

    /** Trägt den Schlüssel nach, unter dem der Server das Belegfoto hält. */
    suspend fun setDeliveryPhotoKey(deliveryId: String, photoKey: String) = write {
        val current = deliveryDao.getDelivery(deliveryId) ?: return@write
        val row = current.copy(photoKey = photoKey)
        deliveryDao.updateDelivery(row)
        update(SyncTables.DELIVERIES, row.id, RowCodec.encode(row), current.sync.serverUpdatedAt)
    }

    suspend fun deliveriesAwaitingUpload(): List<Delivery> = deliveryDao.getDeliveriesAwaitingUpload()

    /**
     * Books a delivery and moves the stock in one step, so a stock figure can always be
     * traced back to a receipt rather than having been quietly edited.
     */
    suspend fun receiveStock(
        item: StockItem,
        quantity: Double,
        containerType: ContainerType? = null,
        totalCost: Double? = null,
        note: String? = null,
        source: StockEntrySource = StockEntrySource.MANUAL
    ) = write {
        insertEntry(
            StockEntry(
                stockItemId = item.id,
                itemName = item.name,
                quantity = quantity,
                unitLabel = containerType?.label ?: item.unit,
                totalCost = totalCost?.let(Money::cents),
                note = note,
                source = source,
                containerTypeId = containerType?.id
            )
        )
    }

    /**
     * Broaches a vessel of the chosen size: takes one off the unopened pile and puts it
     * on tap. The volunteer picks the size because only they know which keg was actually
     * connected. Der Stapel der vollen Gebinde schrumpft von selbst — er ist
     * Wareneingänge minus Anstiche.
     */
    suspend fun tapContainer(type: ContainerType) = write {
        val row = TappedContainerRow(containerTypeId = type.id)
        stockDao.insertTapped(row)
        insert(SyncTables.TAPPED, row.id, RowCodec.encode(row))
    }

    /**
     * Closes the vessel on tap.
     *
     * [ContainerCloseReason.EMPTIED] records a real yield measurement. A spoiled vessel
     * records the thrown-away rest instead and is excluded from the yield average, so a
     * keg that went warm cannot teach the app that this size only gives up a third of
     * what it holds.
     */
    suspend fun closeContainer(
        container: TappedContainer,
        reason: ContainerCloseReason,
        discardedVolume: Double = 0.0,
        note: String? = null
    ) = write {
        val current = stockDao.getTappedRow(container.id) ?: return@write
        val row = current.copy(
            closedAt = nowMillis(),
            closeReason = reason,
            discardedVolume = if (reason == ContainerCloseReason.SPOILED) discardedVolume else 0.0,
            note = note
        )
        stockDao.updateTapped(row)
        update(SyncTables.TAPPED, row.id, RowCodec.encode(row), current.sync.serverUpdatedAt)
    }

    // ---------------------------------------------------------------- Buchen

    /** Eine Position an der Kasse, wie [bookCheckout] sie braucht. */
    data class SaleLine(
        val product: Product,
        val variant: ProductVariant?,
        val quantity: Int,
        /** Rabatt auf die ganze Position, in Euro. */
        val discount: Double
    )

    /**
     * Bucht einen Kassiervorgang: Positionen, Aufladung, Trinkgeld und was der Verkauf dem
     * Keller entnimmt — alles oder nichts.
     *
     * Früher waren das einzelne Schreibzugriffe aus dem ViewModel, und zwischen der
     * Buchungszeile und dem Abzug vom Deckel konnte die App sterben. Einen Abzug gibt es
     * nicht mehr; der Deckel ergibt sich aus den Zeilen (siehe `Ledger`).
     */
    suspend fun bookCheckout(
        lines: List<SaleLine>,
        topUp: Double,
        tip: Double,
        member: Member?,
        paymentType: String,
        transactionGroupId: String
    ) = write {
        val now = nowMillis()
        lines.forEach { line ->
            val unitPrice = line.variant?.price ?: line.product.price
            val transaction = Transaction(
                transactionGroupId = transactionGroupId,
                memberId = member?.id,
                memberName = member?.name,
                productId = line.product.id,
                productName = if (line.variant != null) "${line.product.name} (${line.variant.name})" else line.product.name,
                productCategory = line.product.category,
                price = Money.cents(unitPrice),
                quantity = line.quantity,
                discountAmount = Money.cents(line.discount),
                paymentType = paymentType,
                timestamp = now
            )
            insertTransactionRow(transaction)

            // Every product draws through its recipe, so one Radler takes from the beer
            // keg and the soda keg at once. The variant's size scales the recipe.
            val servingSize = line.variant?.servingSize ?: line.product.servingSize
            val recipe = stockDao.getComponentsFor(line.product.id)
            Inventory.drawForSale(recipe, servingSize, line.quantity).forEach { (stockItemId, volume) ->
                if (volume > 0.0) insertDraw(StockDraw(stockItemId = stockItemId, transactionId = transaction.id, volume = volume, timestamp = now))
            }
        }

        if (topUp > 0.0 && member != null) {
            insertTransactionRow(
                Transaction(
                    transactionGroupId = transactionGroupId,
                    memberId = member.id,
                    memberName = member.name,
                    productId = Ledger.TOPUP_REF,
                    productName = "Guthabenaufladung",
                    productCategory = "Aufladung",
                    price = Money.cents(topUp),
                    quantity = 1,
                    paymentType = paymentType,
                    timestamp = now
                )
            )
        }

        if (tip > 0.0) {
            insertTransactionRow(
                Transaction(
                    transactionGroupId = transactionGroupId,
                    memberId = member?.id,
                    memberName = member?.name,
                    productId = Ledger.TIP_REF,
                    productName = "Trinkgeld",
                    productCategory = "Trinkgeld",
                    price = Money.cents(tip),
                    quantity = 1,
                    paymentType = paymentType,
                    timestamp = now
                )
            )
        }
    }

    /** Eine einzelne Buchungszeile, für Tests und Importe. Der Kassiervorgang nimmt [bookCheckout]. */
    suspend fun insertTransaction(transaction: Transaction) = write { insertTransactionRow(transaction) }

    /**
     * Credits or debits a member's Deckel — als Buchungszeile, denn eine andere Art, einen
     * Deckel zu bewegen, gibt es nicht mehr.
     *
     * A reason is required by the signature, not by a UI check, because the balance
     * previously moved with nothing written down: money appeared on a Deckel and could
     * not be reconciled against the cash box afterwards.
     */
    suspend fun adjustMemberBalance(
        member: Member,
        amount: Double,
        reason: String,
        paymentType: String
    ) = write {
        insertTransactionRow(
            Transaction(
                transactionGroupId = Ids.new(),
                memberId = member.id,
                memberName = member.name,
                productId = Ledger.TOPUP_REF,
                productName = if (amount >= 0) "Guthabenaufladung" else "Guthabenkorrektur",
                productCategory = "Guthaben",
                price = Money.cents(amount),
                quantity = 1,
                paymentType = paymentType,
                note = reason
            )
        )
    }

    // ------------------------------------------------------------ Innenleben

    /**
     * Der Rahmen jedes Schreibzugriffs: eine Transaktion, und darin die Auskunft, ob das
     * Gerät gekoppelt ist. Aus der Datenbank gelesen statt aus dem Speicher, damit die
     * Kopplung (die in derselben Datenbank passiert) und ein gleichzeitiger Verkauf sich
     * nicht um eine Zeile verfehlen.
     */
    private suspend fun <T> write(block: suspend Outbox.() -> T): T {
        var queued = false
        val result = database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val outbox = Outbox(enabled = syncDao.state(SyncKeys.ENABLED) == "1")
                outbox.block().also { queued = outbox.queued }
            }
        }
        // Erst nach dem Commit: Der Abgleich soll finden, was er abholen kommt.
        if (queued) _outboxSignals.tryEmit(Unit)
        return result
    }

    private val _outboxSignals = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Meldet sich, sobald etwas Neues in der Warteschlange liegt — der Abgleich schiebt dann
     * sofort, statt auf den nächsten Minutentakt zu warten (Spezifikation 4.4).
     */
    val outboxSignals: SharedFlow<Unit> = _outboxSignals

    private inner class Outbox(private val enabled: Boolean) {
        var queued = false
            private set

        suspend fun insert(entity: String, id: String, row: JsonObject) = enqueue(entity, id, "insert", row, null)

        suspend fun update(entity: String, id: String, row: JsonObject, base: String?) = enqueue(entity, id, "update", row, base)

        suspend fun delete(entity: String, id: String, base: String?) =
            enqueue(entity, id, "delete", buildJsonObject { put("id", id) }, base)

        private suspend fun enqueue(entity: String, id: String, op: String, row: JsonObject, base: String?) {
            if (!enabled) return
            queued = true
            syncDao.enqueue(
                PendingChange(changeId = Ids.new(), entity = entity, entityId = id, op = op, payload = row.toString(), baseUpdatedAt = base)
            )
        }
    }

    private suspend fun Outbox.updateProductRow(product: Product) {
        val current = productDao.getProduct(product.id) ?: return
        val row = product.copy(price = Money.cents(product.price), sync = current.sync)
        productDao.updateProduct(row)
        update(SyncTables.PRODUCTS, row.id, RowCodec.encode(row), current.sync.serverUpdatedAt)
    }

    private suspend fun Outbox.insertVariantRow(variant: ProductVariant) {
        val row = variant.copy(price = Money.cents(variant.price))
        productDao.insertVariant(row)
        insert(SyncTables.VARIANTS, row.id, RowCodec.encode(row))
    }

    /** Gleicht die Rezeptur ab: je Lagerartikel höchstens eine lebende Zeile. */
    private suspend fun Outbox.replaceComponents(productId: String, components: List<ProductComponent>, now: Long) {
        val wanted = components.filter { it.quantityPerUnit > 0.0 }.associateBy { it.stockItemId }
        val existing = stockDao.getComponentsFor(productId)
        existing.forEach { old ->
            val new = wanted[old.stockItemId]
            when {
                new == null -> {
                    stockDao.softDeleteComponent(old.id, now)
                    delete(SyncTables.COMPONENTS, old.id, old.sync.serverUpdatedAt)
                }
                new.quantityPerUnit != old.quantityPerUnit -> {
                    val row = old.copy(quantityPerUnit = new.quantityPerUnit)
                    syncDao.upsertComponent(row)
                    update(SyncTables.COMPONENTS, row.id, RowCodec.encode(row), old.sync.serverUpdatedAt)
                }
            }
        }
        wanted.values.filter { new -> existing.none { it.stockItemId == new.stockItemId } }.forEach { new ->
            val row = ProductComponent(productId = productId, stockItemId = new.stockItemId, quantityPerUnit = new.quantityPerUnit)
            stockDao.insertComponent(row)
            insert(SyncTables.COMPONENTS, row.id, RowCodec.encode(row))
        }
    }

    /**
     * Legt den Artikel an oder ändert ihn. Trägt das Lesemodell einen anderen Stückbestand
     * als die Bücher, wird daraus eine Korrekturbuchung — der Dialog „Lagerartikel
     * bearbeiten" hat ein Feld dafür, und ein Feld, das still einen Zähler überschreibt,
     * gibt es nicht mehr.
     */
    private suspend fun Outbox.saveStockItemRow(item: StockItem): String {
        val current = stockDao.getItemRow(item.id)
        val booked = if (current == null) 0.0 else stockDao.getItem(item.id)?.simpleQuantity ?: 0.0
        val row = StockItemRow(
            id = item.id, name = item.name, unit = item.unit, tracking = item.tracking, minLevel = item.minLevel,
            sync = current?.sync ?: SyncMeta()
        )
        if (current == null) {
            stockDao.insertItem(row)
            insert(SyncTables.STOCK_ITEMS, row.id, RowCodec.encode(row))
        } else {
            stockDao.updateItem(row)
            update(SyncTables.STOCK_ITEMS, row.id, RowCodec.encode(row), current.sync.serverUpdatedAt)
        }

        val delta = item.simpleQuantity - booked
        if (item.tracking == StockTracking.SIMPLE && abs(delta) > 1e-9) {
            insertEntry(
                StockEntry(
                    stockItemId = row.id,
                    itemName = row.name,
                    quantity = delta,
                    unitLabel = row.unit,
                    note = if (current == null) "Anfangsbestand" else "Bestand im Artikel korrigiert",
                    source = StockEntrySource.CORRECTION
                )
            )
        }
        return row.id
    }

    private suspend fun Outbox.saveContainerTypeRow(type: ContainerType): String {
        val current = stockDao.getContainerTypeRow(type.id)
        val row = ContainerTypeRow(
            id = type.id, stockItemId = type.stockItemId, label = type.label,
            nominalSize = type.nominalSize, initialYieldEstimate = type.initialYieldEstimate,
            sync = current?.sync ?: SyncMeta()
        )
        if (current == null) {
            stockDao.insertContainerType(row)
            insert(SyncTables.CONTAINER_TYPES, row.id, RowCodec.encode(row))
        } else if (current.copy(sync = row.sync) != row) {
            stockDao.updateContainerType(row)
            update(SyncTables.CONTAINER_TYPES, row.id, RowCodec.encode(row), current.sync.serverUpdatedAt)
        }
        return row.id
    }

    private suspend fun Outbox.insertEntry(entry: StockEntry) {
        stockEntryDao.insertEntry(entry)
        insert(SyncTables.STOCK_ENTRIES, entry.id, RowCodec.encode(entry))
    }

    private suspend fun Outbox.insertDraw(draw: StockDraw) {
        stockEntryDao.insertDraw(draw)
        insert(SyncTables.STOCK_DRAWS, draw.id, RowCodec.encode(draw))
    }

    private suspend fun Outbox.insertTransactionRow(transaction: Transaction) {
        transactionDao.insertTransaction(transaction)
        insert(SyncTables.TRANSACTIONS, transaction.id, RowCodec.encode(transaction))
    }
}
