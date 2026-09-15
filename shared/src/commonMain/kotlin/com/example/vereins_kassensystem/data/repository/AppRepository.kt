package com.example.vereins_kassensystem.data.repository

import com.example.vereins_kassensystem.data.dao.*
import com.example.vereins_kassensystem.data.entity.*
import com.example.vereins_kassensystem.data.stock.Inventory
import kotlinx.coroutines.flow.Flow
import com.example.vereins_kassensystem.platform.nowMillis
import com.example.vereins_kassensystem.platform.Ids

class AppRepository(
    private val productDao: ProductDao,
    private val memberDao: MemberDao,
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao,
    private val stockEntryDao: StockEntryDao,
    private val stockDao: StockDao,
    private val deliveryDao: DeliveryDao
) {
    val allProducts: Flow<List<Product>> = productDao.getAllProducts()
    val allProductsWithVariants: Flow<List<ProductWithVariants>> = productDao.getAllProductsWithVariants()
    val allMembers: Flow<List<Member>> = memberDao.getAllMembersSortedByUsage()
    val allTransactions: Flow<List<Transaction>> = transactionDao.getAllTransactions()
    val allCategories: Flow<List<MemberCategory>> = categoryDao.getAllCategories()

    suspend fun insertProduct(product: Product) = productDao.insertProduct(product)
    suspend fun updateProduct(product: Product) = productDao.updateProduct(product)
    suspend fun deleteProduct(product: Product) = productDao.deleteProduct(product)

    suspend fun insertVariant(variant: ProductVariant) = productDao.insertVariant(variant)
    suspend fun updateVariant(variant: ProductVariant) = productDao.updateVariant(variant)
    suspend fun deleteVariant(variant: ProductVariant) = productDao.deleteVariant(variant)
    suspend fun deleteVariantsForProduct(productId: Long) = productDao.deleteVariantsForProduct(productId)

    suspend fun insertMember(member: Member) = memberDao.insertMember(member)
    suspend fun updateMember(member: Member) = memberDao.updateMember(member)
    suspend fun deleteMember(member: Member) = memberDao.deleteMember(member)
    suspend fun updateMemberBalance(memberId: Long, amount: Double) = memberDao.updateBalance(memberId, amount)
    suspend fun updateMemberLastUsed(memberId: Long) = memberDao.updateLastUsedTimestamp(memberId, nowMillis())

    suspend fun insertCategory(category: MemberCategory) = categoryDao.insertCategory(category)
    suspend fun updateCategory(category: MemberCategory) = categoryDao.updateCategory(category)
    suspend fun deleteCategory(category: MemberCategory) = categoryDao.deleteCategory(category)
    suspend fun getCategoryById(id: Long) = categoryDao.getCategoryById(id)
    suspend fun getCategoryByName(name: String) = categoryDao.getCategoryByName(name)

    suspend fun insertTransaction(transaction: Transaction) = transactionDao.insertTransaction(transaction)

    // ----------------------------------------------------------------- inventory

    val allStockItems: Flow<List<StockItem>> = stockDao.getAllItems()
    val allContainerTypes: Flow<List<ContainerType>> = stockDao.getAllContainerTypes()
    val allTappedContainers: Flow<List<TappedContainer>> = stockDao.getAllTapped()
    val allComponents: Flow<List<ProductComponent>> = stockDao.getAllComponents()
    val allStockEntries: Flow<List<StockEntry>> = stockEntryDao.getAllEntries()

    suspend fun insertStockItem(item: StockItem) = stockDao.insertItem(item)
    suspend fun updateStockItem(item: StockItem) = stockDao.updateItem(item)
    suspend fun deleteStockItem(item: StockItem) = stockDao.deleteItem(item)

    suspend fun insertContainerType(type: ContainerType) = stockDao.insertContainerType(type)
    suspend fun updateContainerType(type: ContainerType) = stockDao.updateContainerType(type)
    suspend fun deleteContainerType(type: ContainerType) = stockDao.deleteContainerType(type)

    suspend fun setComponents(productId: Long, components: List<ProductComponent>) =
        stockDao.replaceComponents(productId, components)

    suspend fun componentsFor(productId: Long) = stockDao.getComponentsFor(productId)

    val allDeliveries: Flow<List<Delivery>> = deliveryDao.getAllDeliveries()

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
    ): Long {
        val deliveryId = deliveryDao.insertDelivery(
            Delivery(
                supplier = supplier,
                receiptTotal = receiptTotal,
                photoUri = photoUri,
                note = note
            )
        )
        lines.forEach { line ->
            stockEntryDao.insertEntry(
                StockEntry(
                    stockItemId = line.item.id,
                    itemName = line.item.name,
                    quantity = line.quantity,
                    unitLabel = line.containerType?.label ?: line.item.unit,
                    totalCost = line.cost,
                    source = StockEntrySource.MANUAL,
                    deliveryId = deliveryId
                )
            )
            if (line.containerType != null) {
                stockDao.addFullCount(line.containerType.id, line.quantity.toInt())
            } else {
                stockDao.addSimpleQuantity(line.item.id, line.quantity)
            }
        }
        return deliveryId
    }

    suspend fun deleteDelivery(delivery: Delivery) = deliveryDao.deleteDelivery(delivery)

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
    ) {
        stockEntryDao.insertEntry(
            StockEntry(
                stockItemId = item.id,
                itemName = item.name,
                quantity = quantity,
                unitLabel = containerType?.label ?: item.unit,
                totalCost = totalCost,
                note = note,
                source = source
            )
        )
        if (containerType != null) {
            stockDao.addFullCount(containerType.id, quantity.toInt())
        } else {
            stockDao.addSimpleQuantity(item.id, quantity)
        }
    }

    /**
     * Broaches a vessel of the chosen size: takes one off the unopened pile and puts it
     * on tap. The volunteer picks the size because only they know which keg was actually
     * connected.
     */
    suspend fun tapContainer(type: ContainerType) {
        stockDao.addFullCount(type.id, -1)
        stockDao.insertTapped(TappedContainer(containerTypeId = type.id))
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
    ) {
        stockDao.updateTapped(
            container.copy(
                closedAt = nowMillis(),
                closeReason = reason,
                discardedVolume = if (reason == ContainerCloseReason.SPOILED) discardedVolume else 0.0,
                note = note
            )
        )
    }

    /** Applies the stock effect of a sale across every line of the product's recipe. */
    suspend fun drawForSale(productId: Long, servingSize: Double, quantity: Int) {
        val components = stockDao.getComponentsFor(productId)
        Inventory.drawForSale(components, servingSize, quantity).forEach { (itemId, volume) ->
            val item = stockDao.getItem(itemId) ?: return@forEach
            if (item.tracking == StockTracking.CONTAINER) {
                val open = stockDao.getOpenContainerFor(itemId)
                if (open != null) stockDao.addDrawn(open.id, volume)
            } else {
                stockDao.addSimpleQuantity(itemId, -volume)
            }
        }
    }

    // ------------------------------------------------------------------- balances

    /**
     * Credits or debits a member's Deckel **and** writes the matching transaction.
     *
     * A reason is required by the signature, not by a UI check, because the balance
     * previously moved with nothing written down: money appeared on a Deckel and could
     * not be reconciled against the cash box afterwards. Anything that changes a balance
     * outside the till goes through here.
     */
    suspend fun adjustMemberBalance(
        member: Member,
        amount: Double,
        reason: String,
        paymentType: String
    ) {
        memberDao.updateBalance(member.id, amount)
        transactionDao.insertTransaction(
            Transaction(
                transactionGroupId = Ids.new(),
                memberId = member.id,
                memberName = member.name,
                productId = TOPUP_PRODUCT_ID,
                productName = if (amount >= 0) "Guthabenaufladung" else "Guthabenkorrektur",
                productCategory = "Guthaben",
                price = amount,
                quantity = 1,
                paymentType = paymentType,
                note = reason
            )
        )
        memberDao.updateLastUsedTimestamp(member.id, nowMillis())
    }

    companion object {
        /** Sentinel id for balance movements, which are not a product. */
        const val TOPUP_PRODUCT_ID = -1L
    }
}
