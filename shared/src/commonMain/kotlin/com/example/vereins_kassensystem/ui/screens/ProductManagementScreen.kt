package com.example.vereins_kassensystem.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.vereins_kassensystem.ui.components.EmptyState
import com.example.vereins_kassensystem.ui.components.MoneyText
import com.example.vereins_kassensystem.ui.components.RowMenuItem
import com.example.vereins_kassensystem.ui.components.VdListRow
import com.example.vereins_kassensystem.ui.components.VdRowMenu
import com.example.vereins_kassensystem.ui.components.VdTopBar
import com.example.vereins_kassensystem.ui.format.Money
import com.example.vereins_kassensystem.ui.theme.MoneySmall
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.categoryColor
import com.example.vereins_kassensystem.ui.theme.contrastingOn
import kotlinx.coroutines.launch
import com.example.vereins_kassensystem.data.entity.Product
import com.example.vereins_kassensystem.data.entity.ProductVariant
import com.example.vereins_kassensystem.data.entity.ProductComponent
import com.example.vereins_kassensystem.data.entity.StockItem
import com.example.vereins_kassensystem.viewmodel.ProductViewModel
import com.example.vereins_kassensystem.ui.icons.VdIcons
import com.example.vereins_kassensystem.platform.rememberTextFileReader
import com.example.vereins_kassensystem.platform.rememberTextFileWriter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductManagementScreen(
    viewModel: ProductViewModel
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var productToEdit by remember { mutableStateOf<Product?>(null) }
    var currentVariants by remember { mutableStateOf<List<ProductVariant>>(emptyList()) }

    val productsWithVariants by viewModel.allProductsWithVariants.collectAsState()
    val stockItems by viewModel.allStockItems.collectAsState()
    val allComponents by viewModel.allComponents.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val importer = rememberTextFileReader { csv ->
        scope.launch { viewModel.importProductsFromCsv(csv) }
    }
    val exporter = rememberTextFileWriter(
        suggestedName = "produkte.csv",
        content = { viewModel.exportProductsToCsv() }
    ) { ok ->
        scope.launch { snackbarHostState.showSnackbar(if (ok) "Export erfolgreich" else "Export fehlgeschlagen") }
    }

    LaunchedEffect(Unit) {
        viewModel.importStatus.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            VdTopBar(
                title = "Produkte",
                subtitle = if (productsWithVariants.isEmpty()) null else "${productsWithVariants.size} Produkte",
                actions = {
                    IconButton(onClick = { importer.open() }) {
                        Icon(VdIcons.FileUpload, contentDescription = "Produkte importieren")
                    }
                    IconButton(onClick = { exporter.open() }) {
                        Icon(VdIcons.FileDownload, contentDescription = "Produkte exportieren")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(VdIcons.Add, contentDescription = null) },
                text = { Text("Neu") }
            )
        }
    ) { padding ->
        if (productsWithVariants.isEmpty()) {
            EmptyState(
                icon = VdIcons.Inventory2,
                title = "Noch keine Produkte",
                supportingText = "Lege an, was über die Theke geht — Preis und Kategorie reichen für den Anfang.",
                actionLabel = "Produkt anlegen",
                onAction = { showAddDialog = true },
                modifier = Modifier.padding(padding)
            )
        } else {
            // Width decides the column count, not orientation: a tablet in portrait has
            // room for two, a phone in landscape does not.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 320.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(productsWithVariants, key = { it.product.id }) { productWithVariants ->
                    ProductItem(
                        product = productWithVariants.product,
                        variantCount = productWithVariants.variants.size,
                        onEdit = {
                            productToEdit = productWithVariants.product
                            // Store the current variants for editing
                            currentVariants = productWithVariants.variants
                        },
                        onDelete = { viewModel.deleteProduct(it) }
                    )
                }
                // Clears the FAB, which otherwise sits on top of the last row.
                item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(Spacing.xxl)) }
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

/**
 * A product in the management list.
 *
 * Carries the same category colour the sales grid uses, so a product is recognisable in
 * both places by the same mark. Editing is the whole row rather than a 40dp pencil —
 * it is the frequent action here — and delete moves behind the overflow, where a
 * mistimed tap cannot reach it.
 */
@Composable
fun ProductItem(
    product: Product,
    variantCount: Int,
    onEdit: (Product) -> Unit,
    onDelete: (Product) -> Unit
) {
    val accent = categoryColor(product.category)

    VdListRow(
        title = product.name,
        supportingText = product.category.ifBlank { "Ohne Kategorie" },
        onClick = { onEdit(product) },
        leading = {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.small,
                color = accent,
                contentColor = contrastingOn(accent)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = product.name.take(1).uppercase(),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        },
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                if (product.hasVariants) {
                    Text(
                        text = if (variantCount == 1) "1 Variante" else "$variantCount Varianten",
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    MoneyText(amount = product.price, style = MoneySmall)
                }
                VdRowMenu(
                    items = listOf(
                        RowMenuItem("Bearbeiten", VdIcons.Edit) { onEdit(product) },
                        RowMenuItem("Löschen", VdIcons.Delete, destructive = true) {
                            onDelete(product)
                        }
                    ),
                    contentDescription = "Aktionen für ${product.name}"
                )
            }
        }
    )
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
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
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
                    Spacer(Modifier.height(Spacing.sm))
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
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
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
                            Icon(VdIcons.Delete, contentDescription = "Entfernen", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                item {
                    TextButton(onClick = { showComponentPicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(VdIcons.Add, contentDescription = null)
                        Spacer(Modifier.width(Spacing.sm))
                        Text("Lagerartikel hinzufuegen")
                    }
                }

                item {
                    Spacer(Modifier.height(Spacing.sm))
                    Text("Varianten", style = MaterialTheme.typography.titleSmall)
                }
                
                items(editedVariants) { variant ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
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
                            Icon(VdIcons.Delete, contentDescription = "Löschen", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                
                item {
                    TextButton(
                        onClick = { editedVariants.add(ProductVariant(productId = product?.id ?: "", name = "", price = 0.0)) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(VdIcons.Add, contentDescription = null)
                        Spacer(Modifier.width(Spacing.sm))
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
                                                productId = product?.id ?: "",
                                                stockItemId = candidate.id,
                                                quantityPerUnit = 1.0
                                            )
                                        )
                                        showComponentPicker = false
                                    }
                                    .padding(vertical = Spacing.md)
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
