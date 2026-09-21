package com.example.vereins_kassensystem.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vereins_kassensystem.data.Ledger
import com.example.vereins_kassensystem.data.entity.Transaction
import com.example.vereins_kassensystem.data.repository.AppRepository
import kotlinx.coroutines.flow.*
import com.example.vereins_kassensystem.platform.nowMillis
import com.example.vereins_kassensystem.platform.VdDate

enum class DateRange {
    TODAY, LAST_7_DAYS, LAST_30_DAYS, ALL_TIME
}

data class AnalyticsSummary(
    val totalSales: Double = 0.0,
    val cashSales: Double = 0.0,
    val cardSales: Double = 0.0,
    val memberSales: Double = 0.0,
    val totalTips: Double = 0.0,
    val transactionCount: Int = 0,
    val topProducts: List<ProductSales> = emptyList(),
    val categoryDistribution: Map<String, Double> = emptyMap()
)

data class ProductSales(
    val productName: String,
    val quantity: Int,
    val totalRevenue: Double
)

class AnalyticsViewModel(private val repository: AppRepository) : ViewModel() {

    private val _dateRange = MutableStateFlow(DateRange.TODAY)
    val dateRange: StateFlow<DateRange> = _dateRange.asStateFlow()

    val summary: StateFlow<AnalyticsSummary> = combine(
        repository.allTransactions,
        _dateRange
    ) { transactions, range ->
        calculateSummary(transactions, range)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AnalyticsSummary())

    fun setDateRange(range: DateRange) {
        _dateRange.value = range
    }

    private fun calculateSummary(transactions: List<Transaction>, range: DateRange): AnalyticsSummary {
        val now = nowMillis()

        val filtered = transactions.filter { tx ->
            when (range) {
                DateRange.TODAY -> VdDate.isSameDay(tx.timestamp, now)
                DateRange.LAST_7_DAYS -> tx.timestamp >= now - (7L * 24 * 60 * 60 * 1000)
                DateRange.LAST_30_DAYS -> tx.timestamp >= now - (30L * 24 * 60 * 60 * 1000)
                DateRange.ALL_TIME -> true
            }
        }

        var totalSales = 0.0
        var cash = 0.0
        var card = 0.0
        var member = 0.0
        var tips = 0.0
        val productMap = mutableMapOf<String, Pair<Int, Double>>()
        val categoryMap = mutableMapOf<String, Double>()

        filtered.forEach { tx ->
            if (tx.productId == Ledger.TIP_REF) { // Tip
                tips += tx.price
            } else if (tx.productId == Ledger.TOPUP_REF) { // Top-up (not a sale of product, but revenue)
                // Decide if top-ups are "sales". Usually not, they are balance increases.
                // But for cash flow, they are cash in.
                // Let's exclude them from product sales but include in payment totals if desired.
            } else {
                val revenue = (tx.price * tx.quantity) - tx.discountAmount
                totalSales += revenue
                
                when (tx.paymentType) {
                    "CASH" -> cash += revenue
                    "CARD" -> card += revenue
                    "MEMBER_BALANCE" -> member += revenue
                }

                // getOrDefault gibt es nur auf der JVM (java.util.Map); der Elvis tut dasselbe.
                val current = productMap[tx.productName] ?: Pair(0, 0.0)
                productMap[tx.productName] = Pair(current.first + tx.quantity, current.second + revenue)

                categoryMap[tx.productCategory] = (categoryMap[tx.productCategory] ?: 0.0) + revenue
            }
        }

        val topProducts = productMap.map { (name, stats) ->
            ProductSales(name, stats.first, stats.second)
        }.sortedByDescending { it.totalRevenue }.take(10)

        return AnalyticsSummary(
            totalSales = totalSales,
            cashSales = cash,
            cardSales = card,
            memberSales = member,
            totalTips = tips,
            transactionCount = filtered.size,
            topProducts = topProducts,
            categoryDistribution = categoryMap
        )
    }
}
