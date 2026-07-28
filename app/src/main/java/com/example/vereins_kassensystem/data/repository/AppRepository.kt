package com.example.vereins_kassensystem.data.repository

import com.example.vereins_kassensystem.data.dao.*
import com.example.vereins_kassensystem.data.entity.*
import com.example.vereins_kassensystem.data.stock.Stock
import kotlinx.coroutines.flow.Flow

class AppRepository(
    private val productDao: ProductDao,
    private val memberDao: MemberDao,
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao,
    private val stockEntryDao: StockEntryDao
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
    suspend fun updateMemberLastUsed(memberId: Long) = memberDao.updateLastUsedTimestamp(memberId, System.currentTimeMillis())

    suspend fun insertCategory(category: MemberCategory) = categoryDao.insertCategory(category)
    suspend fun updateCategory(category: MemberCategory) = categoryDao.updateCategory(category)
    suspend fun deleteCategory(category: MemberCategory) = categoryDao.deleteCategory(category)
    suspend fun getCategoryById(id: Long) = categoryDao.getCategoryById(id)
    suspend fun getCategoryByName(name: String) = categoryDao.getCategoryByName(name)

    suspend fun insertTransaction(transaction: Transaction) = transactionDao.insertTransaction(transaction)

    // ---------------------------------------------------------------- stock entries

    val allStockEntries: Flow<List<StockEntry>> = stockEntryDao.getAllEntries()

    fun stockEntriesForProduct(productId: Long) = stockEntryDao.getEntriesForProduct(productId)

    /**
     * Books a goods receipt and moves the product's stock in one step.
     *
     * Deliberately the only way stock goes up, so the number on the product can always be
     * traced back to a receipt rather than having been quietly edited.
     */
    suspend fun receiveStock(
        product: Product,
        quantity: Double,
        totalCost: Double? = null,
        note: String? = null,
        source: StockEntrySource = StockEntrySource.MANUAL
    ) {
        val unitLabel = when (product.stockMode) {
            StockMode.BULK -> "Gebinde ${trimNumber(product.containerSize)} ${product.stockUnit}"
            StockMode.PIECE -> product.stockUnit
        }
        stockEntryDao.insertEntry(
            StockEntry(
                productId = product.id,
                productName = product.name,
                quantity = quantity,
                unitLabel = unitLabel,
                totalCost = totalCost,
                note = note,
                source = source
            )
        )
        productDao.updateProduct(Stock.receive(product, quantity))
    }

    private fun trimNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

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
                transactionGroupId = java.util.UUID.randomUUID().toString(),
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
        memberDao.updateLastUsedTimestamp(member.id, System.currentTimeMillis())
    }

    companion object {
        /** Sentinel id for balance movements, which are not a product. */
        const val TOPUP_PRODUCT_ID = -1L
    }
}
