package com.example.vereins_kassensystem.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.InputStream
import java.io.OutputStream

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

    suspend fun importProductsFromCsv(inputStream: InputStream) = withContext(Dispatchers.IO) {
        try {
            inputStream.bufferedReader().use { reader ->
                reader.readLine() // skip header: Name;Grundpreis;Kategorie;Varianten(Name:Preis,...)
                var count = 0
                for (line in reader.lineSequence()) {
                    val parts = line.split(";")
                    if (parts.size >= 3) {
                        val name = parts[0].trim()
                        val basePrice = parts[1].trim().replace(",", ".").toDoubleOrNull() ?: 0.0
                        val category = parts[2].trim()
                        val variantsString = if (parts.size >= 4) parts[3].trim() else ""
                        
                        if (name.isNotEmpty()) {
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
                            
                            val productId = repository.insertProduct(Product(
                                name = name, 
                                price = if (variantList.isNotEmpty()) 0.0 else basePrice, 
                                category = category,
                                hasVariants = variantList.isNotEmpty()
                            ))
                            
                            for (variant in variantList) {
                                repository.insertVariant(variant.copy(productId = productId))
                            }
                            count++
                        }
                    }
                }
                _importStatus.emit("Erfolgreich $count Produkte importiert")
            }
        } catch (e: Exception) {
            _importStatus.emit("Fehler beim Import: ${e.message}")
        }
    }

    suspend fun exportProductsToCsv(outputStream: OutputStream) = withContext(Dispatchers.IO) {
        try {
            outputStream.bufferedWriter().use { writer ->
                writer.write("Name;Grundpreis;Kategorie;Varianten(Name:Preis,...)\n")
                val productsWithVariants = allProductsWithVariants.value
                productsWithVariants.forEach { pwv ->
                    val variantsString = pwv.variants.joinToString(",") { "${it.name}:${it.price}" }
                    writer.write("${pwv.product.name};${pwv.product.price};${pwv.product.category};$variantsString\n")
                }
                writer.flush()
            }
            _importStatus.emit("Export erfolgreich")
        } catch (e: Exception) {
            _importStatus.emit("Fehler beim Export: ${e.message}")
        }
    }
}

class ProductViewModelFactory(private val repository: AppRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ProductViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ProductViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
