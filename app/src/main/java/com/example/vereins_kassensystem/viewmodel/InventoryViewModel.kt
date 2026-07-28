package com.example.vereins_kassensystem.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.vereins_kassensystem.data.dao.ProductWithVariants
import com.example.vereins_kassensystem.data.entity.Product
import com.example.vereins_kassensystem.data.entity.StockEntry
import com.example.vereins_kassensystem.data.entity.StockEntrySource
import com.example.vereins_kassensystem.data.entity.StockMode
import com.example.vereins_kassensystem.data.repository.AppRepository
import com.example.vereins_kassensystem.data.stock.Stock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the Lagerbestand screen shows for one product: the stock figure plus, for
 * draught products, how many of each glass size are actually still in there.
 */
data class StockLine(
    val product: Product,
    val servingsByVariant: List<Pair<String, Int>>,
    val isLow: Boolean,
    val isNegative: Boolean
)

class InventoryViewModel(private val repository: AppRepository) : ViewModel() {

    private val _status = MutableSharedFlow<String>()
    val status = _status.asSharedFlow()

    val stockLines: StateFlow<List<StockLine>> = repository.allProductsWithVariants
        .map { list -> list.map { it.toStockLine() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentEntries: StateFlow<List<StockEntry>> = repository.allStockEntries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun ProductWithVariants.toStockLine(): StockLine {
        val servings = when (product.stockMode) {
            StockMode.BULK ->
                if (variants.isEmpty()) {
                    listOf("${trim(product.servingSize)} ${product.stockUnit}" to Stock.servingsRemaining(product))
                } else {
                    variants.map { variant ->
                        variant.name to Stock.servingsRemaining(
                            product,
                            Stock.servingSizeFor(product, variant)
                        )
                    }
                }
            StockMode.PIECE -> emptyList()
        }
        return StockLine(
            product = product,
            servingsByVariant = servings,
            isLow = Stock.isLow(product),
            isNegative = Stock.isNegative(product)
        )
    }

    private fun trim(value: Double) =
        if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

    /** Books a delivery. Quantity is containers for draught products, pieces otherwise. */
    fun receive(
        product: Product,
        quantity: Double,
        totalCost: Double?,
        note: String?,
        source: StockEntrySource = StockEntrySource.MANUAL
    ) = viewModelScope.launch {
        repository.receiveStock(product, quantity, totalCost, note, source)
        _status.emit("${product.name}: ${trim(quantity)} gebucht")
    }

    /**
     * Records a stocktake correction as a signed entry rather than overwriting the
     * figure, so a surprise in the cellar stays visible in the history.
     */
    fun correct(product: Product, delta: Double, note: String) = viewModelScope.launch {
        repository.receiveStock(product, delta, null, note, StockEntrySource.CORRECTION)
        _status.emit("${product.name}: Korrektur ${trim(delta)} gebucht")
    }
}

class InventoryViewModelFactory(private val repository: AppRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(InventoryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return InventoryViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
