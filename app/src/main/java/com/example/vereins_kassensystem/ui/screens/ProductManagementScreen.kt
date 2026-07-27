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
import kotlinx.coroutines.launch
import com.example.vereins_kassensystem.data.entity.Product
import com.example.vereins_kassensystem.data.entity.ProductVariant
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
                title = { Text("Produktverwaltung", fontWeight = FontWeight.Bold) },
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
                onDismiss = { showAddDialog = false },
                onConfirm = { name, price, category, variants, track, stock, minStock ->
                    viewModel.insertProductWithVariants(
                        Product(
                            name = name, 
                            price = price, 
                            category = category, 
                            hasVariants = variants.isNotEmpty(),
                            trackInventory = track,
                            stockQuantity = stock,
                            minStockLevel = minStock
                        ),
                        variants
                    )
                    showAddDialog = false
                }
            )
        }

        if (productToEdit != null) {
            ProductDialog(
                product = productToEdit,
                variants = currentVariants,
                onDismiss = { productToEdit = null },
                onConfirm = { name, price, category, variants, track, stock, minStock ->
                    viewModel.updateProductWithVariants(
                        productToEdit!!.copy(
                            name = name, 
                            price = price, 
                            category = category, 
                            hasVariants = variants.isNotEmpty(),
                            trackInventory = track,
                            stockQuantity = stock,
                            minStockLevel = minStock
                        ),
                        variants
                    )
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
        shape = RoundedCornerShape(24.dp),
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
                shape = RoundedCornerShape(16.dp),
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
                Text(text = product.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${String.format( "%.2f", product.price)} €",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Black
                    )
                    Text(text = " • ", color = MaterialTheme.colorScheme.outlineVariant)
                    Text(text = product.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                
                if (product.trackInventory) {
                    val isLowStock = product.stockQuantity <= product.minStockLevel
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (isLowStock) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(8.dp)
                        ) {}
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Lager: ${product.stockQuantity}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isLowStock) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isLowStock) FontWeight.Bold else FontWeight.Normal
                        )
                    }
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

@Composable
fun ProductDialog(
    product: Product? = null,
    variants: List<ProductVariant> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (String, Double, String, List<ProductVariant>, Boolean, Int, Int) -> Unit
) {
    var name by remember { mutableStateOf(product?.name ?: "") }
    var price by remember { mutableStateOf(product?.price?.toString() ?: "") }
    var category by remember { mutableStateOf(product?.category ?: "") }
    
    var trackInventory by remember { mutableStateOf(product?.trackInventory ?: false) }
    var stockQuantity by remember { mutableStateOf(product?.stockQuantity?.toString() ?: "0") }
    var minStockLevel by remember { mutableStateOf(product?.minStockLevel?.toString() ?: "5") }
    
    val editedVariants = remember { mutableStateListOf<ProductVariant>().apply { addAll(variants) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (product == null) "Produkt hinzufügen" else "Produkt bearbeiten", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
                item {
                    val hasVariants = editedVariants.isNotEmpty()
                    OutlinedTextField(
                        value = if (hasVariants) "Variantenpreise" else price,
                        onValueChange = { if (!hasVariants) price = it },
                        label = { Text("Preis (€)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
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
                        shape = RoundedCornerShape(12.dp)
                    )
                }
                
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("Lagerverwaltung", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { trackInventory = !trackInventory },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Lagerbestand verfolgen", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = trackInventory, onCheckedChange = { trackInventory = it })
                    }
                }
                
                if (trackInventory) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = stockQuantity,
                                onValueChange = { stockQuantity = it },
                                label = { Text("Bestand") },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                            )
                            OutlinedTextField(
                                value = minStockLevel,
                                onValueChange = { minStockLevel = it },
                                label = { Text("Warnung bei") },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                            )
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(8.dp))
                    Text("Varianten", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
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
                            shape = RoundedCornerShape(8.dp)
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
                            shape = RoundedCornerShape(8.dp)
                        )
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
            Button(onClick = {
                val hasVariants = editedVariants.isNotEmpty()
                val p = if (hasVariants) 0.0 else (price.replace(",", ".").toDoubleOrNull() ?: 0.0)
                val stock = stockQuantity.toIntOrNull() ?: 0
                val minStock = minStockLevel.toIntOrNull() ?: 5
                onConfirm(name, p, category, editedVariants.toList(), trackInventory, stock, minStock)
            }) {
                Text("Speichern")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        }
    )
}
