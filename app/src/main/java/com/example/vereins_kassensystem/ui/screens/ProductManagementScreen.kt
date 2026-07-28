package com.example.vereins_kassensystem.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.vereins_kassensystem.ui.format.Money
import kotlinx.coroutines.launch
import com.example.vereins_kassensystem.data.entity.Product
import com.example.vereins_kassensystem.data.entity.ProductVariant
import com.example.vereins_kassensystem.data.entity.ProductComponent
import com.example.vereins_kassensystem.data.entity.StockItem
import com.example.vereins_kassensystem.data.dao.ProductWithVariants
import com.example.vereins_kassensystem.viewmodel.ProductViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductManagementScreen(
    viewModel: ProductViewModel
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var productToEdit by remember { mutableStateOf<Product?>(null) }
    var currentVariants by remember { mutableStateOf<List<ProductVariant>>(emptyList()) }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val productsWithVariants by viewModel.allProductsWithVariants.collectAsState()
    val stockItems by viewModel.allStockItems.collectAsState()
    val allComponents by viewModel.allComponents.collectAsState()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                context.contentResolver.openInputStream(it)?.use { stream ->
                    viewModel.importProductsFromCsv(stream)
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            scope.launch {
                context.contentResolver.openOutputStream(it)?.use { stream ->
                    viewModel.exportProductsToCsv(stream)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.importStatus.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Produktverwaltung") },
                actions = {
                    IconButton(onClick = { importLauncher.launch("text/*") }) {
                        Icon(Icons.Default.FileUpload, contentDescription = "Import")
                    }
                    IconButton(onClick = { exportLauncher.launch("produkte.csv") }) {
                        Icon(Icons.Default.FileDownload, contentDescription = "Export")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Neu") }
            )
        }
    ) { padding ->
        if (productsWithVariants.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Inventory2,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("Noch keine Produkte", color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            LazyVerticalGrid(
                columns = if (isLandscape) GridCells.Fixed(2) else GridCells.Fixed(1),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(productsWithVariants, key = { it.product.id }) { productWithVariants ->
                    ProductItem(
                        product = productWithVariants.product,
                        onEdit = { 
                            productToEdit = productWithVariants.product
                            // Store the current variants for editing
                            currentVariants = productWithVariants.variants
                        },
                        onDelete = { viewModel.deleteProduct(it) }
                    )
                }
            }
        }

        if (showAddDialog) {
            ProductDialog(
                stockItems = stockItems,
                onDismiss = { showAddDialog = false },
                onConfirm = { newProduct, variants, components ->
                    viewModel.saveProductWithRecipe(newProduct, variants, components, isNew = true)
                    showAddDialog = false
                }
            )
        }

        if (productToEdit != null) {
            ProductDialog(
                product = productToEdit,
                variants = currentVariants,
                components = allComponents.filter { it.productId == productToEdit!!.id },
                stockItems = stockItems,
                onDismiss = { productToEdit = null },
                onConfirm = { edited, variants, components ->
                    viewModel.saveProductWithRecipe(edited, variants, components, isNew = false)
                    productToEdit = null
                }
            )
        }
    }
}

@Composable
fun ProductItem(product: Product, onEdit: (Product) -> Unit, onDelete: (Product) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Restaurant,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            
            Spacer(Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(text = product.name, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = Money.format(product.price),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(text = " • ", color = MaterialTheme.colorScheme.outlineVariant)
                    Text(text = product.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                
            }
            
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalIconButton(
                    onClick = { onEdit(product) },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(20.dp))
                }
                FilledTonalIconButton(
                    onClick = { onDelete(product) },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/**
 * Takes the assembled [Product] rather than a dozen positional arguments — the bulk
 * stock settings pushed the old signature past the point of being readable.
 */
@Composable
fun ProductDialog(
    product: Product? = null,
    variants: List<ProductVariant> = emptyList(),
    components: List<ProductComponent> = emptyList(),
    stockItems: List<StockItem> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (Product, List<ProductVariant>, List<ProductComponent>) -> Unit
) {
    var name by remember { mutableStateOf(product?.name ?: "") }
    var price by remember { mutableStateOf(product?.price?.toString() ?: "") }
    var category by remember { mutableStateOf(product?.category ?: "") }

    var servingSize by remember { mutableStateOf(product?.servingSize?.toString() ?: "1") }
    var showComponentPicker by remember { mutableStateOf(false) }
    val editedComponents = remember { mutableStateListOf<ProductComponent>().apply { addAll(components) } }

    val editedVariants = remember { mutableStateListOf<ProductVariant>().apply { addAll(variants) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (product == null) "Produkt hinzufügen" else "Produkt bearbeiten") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small
                    )
                }
                item {
                    val hasVariants = editedVariants.isNotEmpty()
                    OutlinedTextField(
                        value = if (hasVariants) "Variantenpreise" else price,
                        onValueChange = { if (!hasVariants) price = it },
                        label = { Text("Preis (€)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        enabled = !hasVariants,
                        colors = if (hasVariants) {
                            OutlinedTextFieldDefaults.colors(
                                disabledTextColor = MaterialTheme.colorScheme.primary,
                                disabledBorderColor = MaterialTheme.colorScheme.outline,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            OutlinedTextFieldDefaults.colors()
                        }
                    )
                }
                item {
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        label = { Text("Kategorie") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small
                    )
                }
                
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("Rezept", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Woraus dieses Produkt gezogen wird. Mengen gelten je Einheit und " +
                            "werden von der Variantengroesse skaliert - ein Radler mit 0,5 Bier " +
                            "und 0,5 Soda ergibt beim 0,3l-Glas 0,15 und 0,15.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                item {
                    OutlinedTextField(
                        value = servingSize,
                        onValueChange = { servingSize = it },
                        label = { Text("Menge je Verkauf") },
                        enabled = editedVariants.isEmpty(),
                        supportingText = {
                            Text(
                                if (editedVariants.isEmpty()) "1 = ein Stueck, 0,5 = ein halber Liter"
                                else "Wird je Variante gesetzt"
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
                    )
                }

                items(editedComponents) { component ->
                    val stockItem = stockItems.firstOrNull { it.id == component.stockItemId }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stockItem?.name ?: "Unbekannt",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = if (component.quantityPerUnit == 0.0) "" else component.quantityPerUnit.toString(),
                            onValueChange = { raw ->
                                val parsed = Money.parse(raw) ?: 0.0
                                val index = editedComponents.indexOf(component)
                                if (index != -1) editedComponents[index] = component.copy(quantityPerUnit = parsed)
                            },
                            label = { Text(stockItem?.unit ?: "Menge") },
                            modifier = Modifier.weight(0.7f),
                            shape = MaterialTheme.shapes.extraSmall,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
                        )
                        IconButton(onClick = { editedComponents.remove(component) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Entfernen", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                item {
                    TextButton(onClick = { showComponentPicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Lagerartikel hinzufuegen")
                    }
                }

                item {
                    Spacer(Modifier.height(8.dp))
                    Text("Varianten", style = MaterialTheme.typography.titleSmall)
                }
                
                items(editedVariants) { variant ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = variant.name,
                            onValueChange = { newName ->
                                val index = editedVariants.indexOf(variant)
                                if (index != -1) editedVariants[index] = editedVariants[index].copy(name = newName)
                            },
                            label = { Text("Variante") },
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.extraSmall
                        )
                        OutlinedTextField(
                            value = if (variant.price == 0.0) "" else variant.price.toString(),
                            onValueChange = { newPrice ->
                                val p = newPrice.replace(",", ".").toDoubleOrNull() ?: 0.0
                                val index = editedVariants.indexOf(variant)
                                if (index != -1) editedVariants[index] = editedVariants[index].copy(price = p)
                            },
                            label = { Text("Preis") },
                            modifier = Modifier.weight(0.6f),
                            shape = MaterialTheme.shapes.extraSmall
                        )
                        // How much of the keg this glass size actually draws. A "0,3l"
                        // is usually 0,33 in the cellar, which is the whole reason this
                        // is a separate number from the variant's name.
                        if (editedComponents.isNotEmpty()) {
                            OutlinedTextField(
                                value = variant.servingSize?.toString() ?: "",
                                onValueChange = { newSize ->
                                    val parsed = Money.parse(newSize)
                                    val index = editedVariants.indexOf(variant)
                                    if (index != -1) editedVariants[index] = editedVariants[index].copy(servingSize = parsed)
                                },
                                label = { Text("Menge") },
                                modifier = Modifier.weight(0.6f),
                                shape = MaterialTheme.shapes.extraSmall,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
                            )
                        }
                        IconButton(onClick = { editedVariants.remove(variant) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Löschen", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                
                item {
                    TextButton(
                        onClick = { editedVariants.add(ProductVariant(productId = product?.id ?: 0, name = "", price = 0.0)) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Variante hinzufügen")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val hasVariants = editedVariants.isNotEmpty()
                    val base = product ?: Product(name = "", price = 0.0, category = "")
                    onConfirm(
                        base.copy(
                            name = name.trim(),
                            price = if (hasVariants) 0.0 else (Money.parse(price) ?: 0.0),
                            category = category.trim(),
                            hasVariants = hasVariants,
                            servingSize = Money.parse(servingSize) ?: 1.0
                        ),
                        editedVariants.toList(),
                        editedComponents.toList()
                    )
                },
                enabled = name.isNotBlank()
            ) {
                Text("Speichern")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        }
    )

    if (showComponentPicker) {
        val available = stockItems.filter { candidate ->
            editedComponents.none { it.stockItemId == candidate.id }
        }
        AlertDialog(
            onDismissRequest = { showComponentPicker = false },
            title = { Text("Lagerartikel wählen") },
            text = {
                if (available.isEmpty()) {
                    Text("Alle Lagerartikel sind bereits im Rezept.")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(available) { candidate ->
                            Text(
                                text = "${candidate.name} (${candidate.unit})",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        editedComponents.add(
                                            ProductComponent(
                                                productId = product?.id ?: 0,
                                                stockItemId = candidate.id,
                                                quantityPerUnit = 1.0
                                            )
                                        )
                                        showComponentPicker = false
                                    }
                                    .padding(vertical = 12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showComponentPicker = false }) { Text("Schließen") } }
        )
    }
}
