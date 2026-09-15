package com.example.vereins_kassensystem.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.vereins_kassensystem.data.entity.ContainerCloseReason
import com.example.vereins_kassensystem.data.entity.ContainerType
import com.example.vereins_kassensystem.data.entity.StockEntry
import com.example.vereins_kassensystem.data.entity.StockItem
import com.example.vereins_kassensystem.data.entity.StockTracking
import com.example.vereins_kassensystem.data.entity.TappedContainer
import com.example.vereins_kassensystem.data.entity.Delivery
import com.example.vereins_kassensystem.data.repository.AppRepository
import com.example.vereins_kassensystem.data.stock.Inventory
import com.example.vereins_kassensystem.data.stock.StockItemState
import com.example.vereins_kassensystem.data.stock.YieldEstimate
import com.example.vereins_kassensystem.ui.format.Quantity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

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
        _status.emit("${item.name}: ${Quantity.format(quantity)} ${containerType?.label ?: item.unit} gebucht")
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
                    "${row.item.name}: Fass leer, ${Quantity.format(open.drawn)} ${row.item.unit} gezapft"
                ContainerCloseReason.SPOILED ->
                    "${row.item.name}: Fass verworfen, ${Quantity.format(rest)} ${row.item.unit} Verderb"
            }
        )
    }

    val deliveries: StateFlow<List<Delivery>> = repository.allDeliveries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Books a whole Kassabon: lines, prices and the photo, as one record. */
    fun bookDelivery(
        supplier: String,
        receiptTotal: Double?,
        photoUri: String?,
        note: String?,
        lines: List<Triple<StockItem, ContainerType?, Pair<Double, Double?>>>
    ) = viewModelScope.launch {
        repository.bookDelivery(
            supplier = supplier,
            receiptTotal = receiptTotal,
            photoUri = photoUri,
            note = note,
            lines = lines.map { (item, type, qtyCost) ->
                AppRepository.ReceiptLine(item, type, qtyCost.first, qtyCost.second)
            }
        )
        _status.emit("Wareneingang gebucht: ${lines.size} Positionen")
    }

    // ---------------------------------------------------------------- import

    /**
     * Reads Lagerartikel from a semicolon file.
     *
     * `Name;Einheit;Verwaltung;Warnung;Gebinde`
     * where Verwaltung is STK or GEBINDE and Gebinde is a comma-separated list of
     * `Bezeichnung:Inhalt:Ertrag`, e.g. `50 l:50:49,30 l:30:29,2`.
     *
     * Existing items are matched by name and updated rather than duplicated, so the same
     * file can be re-imported after an edit without leaving two of everything.
     *
     * Nimmt den Inhalt als Zeichenkette statt als Strom, siehe `platform/FileExchange.kt`.
     */
    suspend fun importFromCsv(csv: String) {
        try {
            var count = 0
            for (line in csv.lineSequence().drop(1)) {
                if (line.isBlank()) continue
                val parts = line.split(";")
                if (parts.size < 2) continue
                val name = parts[0].trim()
                if (name.isEmpty()) continue

                val unit = parts.getOrNull(1)?.trim().orEmpty().ifBlank { "Stk" }
                val tracking = if (parts.getOrNull(2)?.trim()?.uppercase()?.startsWith("GEB") == true)
                    StockTracking.CONTAINER else StockTracking.SIMPLE
                val minLevel = parseNumber(parts.getOrNull(3)) ?: 0.0

                val existing = repository.allStockItems.first().firstOrNull { it.name.equals(name, true) }
                val item = (existing ?: StockItem(name = name)).copy(
                    name = name, unit = unit, tracking = tracking, minLevel = minLevel
                )
                val id = if (existing == null) repository.insertStockItem(item)
                else { repository.updateStockItem(item); item.id }

                parts.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() }?.split(",")?.forEach { spec ->
                    val fields = spec.split(":")
                    if (fields.size >= 2) {
                        val size = parseNumber(fields[1]) ?: return@forEach
                        repository.insertContainerType(
                            ContainerType(
                                stockItemId = id,
                                label = fields[0].trim(),
                                nominalSize = size,
                                initialYieldEstimate = parseNumber(fields.getOrNull(2)) ?: (size * 0.97)
                            )
                        )
                    }
                }
                count++
            }
            _status.emit("$count Lagerartikel importiert")
        } catch (e: Exception) {
            _status.emit("Fehler beim Import: ${e.message}")
        }
    }

    /**
     * Alle Lagerartikel samt Gebinden als Semikolon-Datei. Das Schreiben übernimmt der
     * Bildschirm. Liest aus den gehaltenen Zuständen statt aus dem Repository, damit der
     * Export beim Öffnen der Dateiauswahl sofort feststeht.
     */
    fun exportToCsv(): String {
        val items = rows.value.map { it.item }
        val types = containerTypes.value
        return buildString {
            append("Name;Einheit;Verwaltung;Warnung;Gebinde\n")
            items.forEach { item ->
                val spec = types.filter { it.stockItemId == item.id }
                    .joinToString(",") { "${it.label}:${it.nominalSize}:${it.initialYieldEstimate}" }
                append(
                    "${item.name};${item.unit};" +
                        "${if (item.tracking == StockTracking.CONTAINER) "GEBINDE" else "STK"};" +
                        "${item.minLevel};$spec\n"
                )
            }
        }
    }

    /** Accepts both decimal separators, since these files come from spreadsheets. */
    private fun parseNumber(raw: String?): Double? =
        raw?.trim()?.replace(",", ".")?.toDoubleOrNull()

    /** Saves an item together with the vessel sizes it arrives in. */
    fun saveItemWithContainers(item: StockItem, types: List<ContainerType>) = viewModelScope.launch {
        val id = if (item.id == 0L) repository.insertStockItem(item) else {
            repository.updateStockItem(item); item.id
        }
        val existing = repository.allContainerTypes.first().filter { it.stockItemId == id }
        existing.filter { old -> types.none { it.id == old.id } }.forEach { repository.deleteContainerType(it) }
        types.forEach { repository.insertContainerType(it.copy(stockItemId = id)) }
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
}

class InventoryViewModelFactory(private val repository: AppRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
        require(modelClass == InventoryViewModel::class) { "Unbekanntes ViewModel: $modelClass" }
        @Suppress("UNCHECKED_CAST")
        return InventoryViewModel(repository) as T
    }
}
