package com.example.vereins_kassensystem.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.vereins_kassensystem.data.entity.Product
import com.example.vereins_kassensystem.data.entity.ProductVariant
import com.example.vereins_kassensystem.data.dao.ProductWithVariants
import com.example.vereins_kassensystem.data.entity.ProductComponent
import com.example.vereins_kassensystem.data.entity.StockItem
import com.example.vereins_kassensystem.data.repository.AppRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

class ProductViewModel(private val repository: AppRepository) : ViewModel() {

    /** Lagerartikel available to build a recipe from. */
    val allStockItems: StateFlow<List<StockItem>> = repository.allStockItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allComponents: StateFlow<List<ProductComponent>> = repository.allComponents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Saves the product, its variants and its recipe together. */
    fun saveProductWithRecipe(
        product: Product,
        variants: List<ProductVariant>,
        components: List<ProductComponent>,
        isNew: Boolean
    ) = viewModelScope.launch {
        val id = if (isNew) {
            repository.insertProduct(product)
        } else {
            repository.updateProduct(product); product.id
        }
        repository.deleteVariantsForProduct(id)
        variants.filter { it.name.isNotBlank() }.forEach {
            repository.insertVariant(it.copy(id = 0, productId = id))
        }
        repository.setComponents(id, components.filter { it.quantityPerUnit > 0.0 })
    }

    private val _importStatus = MutableSharedFlow<String>()
    val importStatus = _importStatus.asSharedFlow()

    val allProductsWithVariants: StateFlow<List<ProductWithVariants>> = repository.allProductsWithVariants
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun insertProductWithVariants(product: Product, variants: List<ProductVariant>) = viewModelScope.launch {
        val productId = repository.insertProduct(product)
        for (variant in variants) {
            repository.insertVariant(variant.copy(productId = productId))
        }
    }

    fun updateProductWithVariants(product: Product, variants: List<ProductVariant>) = viewModelScope.launch {
        repository.updateProduct(product)
        repository.deleteVariantsForProduct(product.id)
        for (variant in variants) {
            repository.insertVariant(variant.copy(productId = product.id))
        }
    }

    fun deleteProduct(product: Product) = viewModelScope.launch {
        repository.deleteProduct(product)
    }

    /**
     * Liest Produkte aus einer Semikolon-Datei.
     *
     * `Name;Grundpreis;Kategorie;Varianten(Name:Preis,...)`, erste Zeile ist die Kopfzeile.
     * Nimmt den Inhalt als Zeichenkette statt als Strom, damit der Bildschirm die Datei
     * über `platform/FileExchange.kt` holen kann — Ströme gibt es nur auf der JVM.
     */
    suspend fun importProductsFromCsv(csv: String) {
        try {
            var count = 0
            for (line in csv.lineSequence().drop(1)) {
                val parts = line.split(";")
                if (parts.size < 3) continue
                val name = parts[0].trim()
                val basePrice = parts[1].trim().replace(",", ".").toDoubleOrNull() ?: 0.0
                val category = parts[2].trim()
                val variantsString = if (parts.size >= 4) parts[3].trim() else ""

                if (name.isEmpty()) continue
                val variantList = mutableListOf<ProductVariant>()
                if (variantsString.isNotEmpty()) {
                    variantsString.split(",").forEach { vPart ->
                        val vSubParts = vPart.split(":")
                        if (vSubParts.size == 2) {
                            val vName = vSubParts[0].trim()
                            val vPrice = vSubParts[1].trim().replace(",", ".").toDoubleOrNull() ?: 0.0
                            variantList.add(ProductVariant(name = vName, price = vPrice, productId = 0))
                        }
                    }
                }

                val productId = repository.insertProduct(
                    Product(
                        name = name,
                        price = if (variantList.isNotEmpty()) 0.0 else basePrice,
                        category = category,
                        hasVariants = variantList.isNotEmpty()
                    )
                )
                for (variant in variantList) {
                    repository.insertVariant(variant.copy(productId = productId))
                }
                count++
            }
            _importStatus.emit("Erfolgreich $count Produkte importiert")
        } catch (e: Exception) {
            _importStatus.emit("Fehler beim Import: ${e.message}")
        }
    }

    /** Alle Produkte als Semikolon-Datei, Kopfzeile inklusive. Das Schreiben übernimmt der Bildschirm. */
    fun exportProductsToCsv(): String = buildString {
        append("Name;Grundpreis;Kategorie;Varianten(Name:Preis,...)\n")
        allProductsWithVariants.value.forEach { pwv ->
            val variantsString = pwv.variants.joinToString(",") { "${it.name}:${it.price}" }
            append("${pwv.product.name};${pwv.product.price};${pwv.product.category};$variantsString\n")
        }
    }
}

class ProductViewModelFactory(private val repository: AppRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
        require(modelClass == ProductViewModel::class) { "Unbekanntes ViewModel: $modelClass" }
        @Suppress("UNCHECKED_CAST")
        return ProductViewModel(repository) as T
    }
}
