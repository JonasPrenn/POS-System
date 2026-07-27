package com.example.vereins_kassensystem.data.repository

import com.example.vereins_kassensystem.data.dao.*
import com.example.vereins_kassensystem.data.entity.*
import kotlinx.coroutines.flow.Flow

class AppRepository(
    private val productDao: ProductDao,
    private val memberDao: MemberDao,
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao
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
}
