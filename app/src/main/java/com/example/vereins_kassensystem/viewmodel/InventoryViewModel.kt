package com.example.vereins_kassensystem.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.vereins_kassensystem.data.entity.ContainerCloseReason
import com.example.vereins_kassensystem.data.entity.ContainerType
import com.example.vereins_kassensystem.data.entity.StockEntry
import com.example.vereins_kassensystem.data.entity.StockItem
import com.example.vereins_kassensystem.data.entity.StockTracking
import com.example.vereins_kassensystem.data.entity.TappedContainer
import com.example.vereins_kassensystem.data.repository.AppRepository
import com.example.vereins_kassensystem.data.stock.Inventory
import com.example.vereins_kassensystem.data.stock.StockItemState
import com.example.vereins_kassensystem.data.stock.YieldEstimate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the Lagerbestand screen needs for one item. */
data class InventoryRow(
    val state: StockItemState,
    val available: Double,
    val yields: List<YieldEstimate>,
    val openContainer: TappedContainer?,
    val fullByType: List<Pair<ContainerType, Int>>,
    val isLow: Boolean,
    val isNegative: Boolean,
    val spoiled: Double
) {
    val item: StockItem get() = state.item
}

class InventoryViewModel(private val repository: AppRepository) : ViewModel() {

    private val _status = MutableSharedFlow<String>()
    val status = _status.asSharedFlow()

    val rows: StateFlow<List<InventoryRow>> = combine(
        repository.allStockItems,
        repository.allContainerTypes,
        repository.allTappedContainers
    ) { items, types, tapped ->
        items.map { item ->
            val state = StockItemState(
                item = item,
                containerTypes = types.filter { it.stockItemId == item.id },
                tapped = tapped.filter { t -> types.any { it.id == t.containerTypeId && it.stockItemId == item.id } }
            )
            InventoryRow(
                state = state,
                available = Inventory.available(state),
                yields = Inventory.yields(state),
                openContainer = Inventory.openContainer(state),
                fullByType = Inventory.fullContainers(state),
                isLow = Inventory.isLow(state),
                isNegative = Inventory.isNegative(state),
                spoiled = Inventory.spoiledVolume(state)
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentEntries: StateFlow<List<StockEntry>> = repository.allStockEntries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val containerTypes: StateFlow<List<ContainerType>> = repository.allContainerTypes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // -------------------------------------------------------------- receipts

    fun receive(
        item: StockItem,
        quantity: Double,
        containerType: ContainerType?,
        totalCost: Double?,
        note: String?
    ) = viewModelScope.launch {
        repository.receiveStock(item, quantity, containerType, totalCost, note)
        _status.emit("${item.name}: ${trim(quantity)} ${containerType?.label ?: item.unit} gebucht")
    }

    // --------------------------------------------------------- keg handling

    fun tap(type: ContainerType, itemName: String) = viewModelScope.launch {
        repository.tapContainer(type)
        _status.emit("$itemName: ${type.label} angestochen")
    }

    /**
     * Closes the vessel on tap.
     *
     * Emptied normally is a measurement the forecast learns from; spoiled is a loss that
     * is deliberately kept out of it.
     */
    fun closeOpenContainer(
        row: InventoryRow,
        reason: ContainerCloseReason,
        note: String?
    ) = viewModelScope.launch {
        val open = row.openContainer ?: return@launch
        val expected = row.yields.firstOrNull { it.containerType.id == open.containerTypeId }?.perContainer ?: 0.0
        val rest = (expected - open.drawn).coerceAtLeast(0.0)

        repository.closeContainer(
            container = open,
            reason = reason,
            discardedVolume = if (reason == ContainerCloseReason.SPOILED) rest else 0.0,
            note = note
        )
        _status.emit(
            when (reason) {
                ContainerCloseReason.EMPTIED ->
                    "${row.item.name}: Fass leer, ${trim(open.drawn)} ${row.item.unit} gezapft"
                ContainerCloseReason.SPOILED ->
                    "${row.item.name}: Fass verworfen, ${trim(rest)} ${row.item.unit} Verderb"
            }
        )
    }

    // ------------------------------------------------------------ item admin

    fun saveItem(item: StockItem) = viewModelScope.launch {
        if (item.id == 0L) repository.insertStockItem(item) else repository.updateStockItem(item)
    }

    fun deleteItem(item: StockItem) = viewModelScope.launch { repository.deleteStockItem(item) }

    fun saveContainerType(type: ContainerType) = viewModelScope.launch {
        if (type.id == 0L) repository.insertContainerType(type) else repository.updateContainerType(type)
    }

    fun deleteContainerType(type: ContainerType) = viewModelScope.launch {
        repository.deleteContainerType(type)
    }

    fun newItemDefaults(name: String, tracking: StockTracking) = StockItem(
        name = name,
        unit = if (tracking == StockTracking.CONTAINER) "l" else "Stk",
        tracking = tracking
    )

    private fun trim(value: Double) =
        if (value % 1.0 == 0.0) value.toInt().toString() else String.format(java.util.Locale.GERMANY, "%.1f", value)
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
