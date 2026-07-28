package com.example.vereins_kassensystem.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.vereins_kassensystem.data.entity.Product
import com.example.vereins_kassensystem.data.entity.StockMode
import com.example.vereins_kassensystem.data.stock.Stock
import com.example.vereins_kassensystem.ui.components.EmptyState
import com.example.vereins_kassensystem.ui.components.MoneyText
import com.example.vereins_kassensystem.ui.components.VdTopBar
import com.example.vereins_kassensystem.ui.format.Money
import com.example.vereins_kassensystem.ui.theme.MoneySmall
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.VereinsColors
import com.example.vereins_kassensystem.ui.theme.categoryColor
import com.example.vereins_kassensystem.viewmodel.InventoryViewModel
import com.example.vereins_kassensystem.viewmodel.StockLine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lagerbestand — what is in the cellar and what arrived.
 *
 * For draught products the headline number is **servings left**, not litres, because
 * "still about 60 Halbe" is the question actually being asked before a match. The litre
 * figure sits underneath for anyone doing the ordering.
 */
@Composable
fun InventoryScreen(viewModel: InventoryViewModel) {
    val lines by viewModel.stockLines.collectAsState()
    val entries by viewModel.recentEntries.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.status.collect { snackbarHostState.showSnackbar(it) } }

    var receiveFor by remember { mutableStateOf<Product?>(null) }
    var showHistory by remember { mutableStateOf(false) }

    val tracked = lines.filter { it.product.trackInventory }

    Scaffold(
        topBar = {
            VdTopBar(
                title = "Lagerbestand",
                subtitle = if (tracked.isEmpty()) null else "${tracked.size} Artikel im Bestand",
                actions = {
                    TextButton(onClick = { showHistory = !showHistory }) {
                        Text(if (showHistory) "Bestand" else "Eingänge")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when {
            showHistory -> EntryHistory(entries, Modifier.padding(padding))

            tracked.isEmpty() -> EmptyState(
                icon = Icons.Default.Inventory2,
                title = "Kein Artikel im Bestand",
                supportingText = "Aktiviere bei einem Produkt die Bestandsführung, damit es hier erscheint.",
                modifier = Modifier.padding(padding)
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(tracked, key = { it.product.id }) { line ->
                    StockCard(line = line, onReceive = { receiveFor = line.product })
                }
                item { Spacer(Modifier.height(Spacing.xxl)) }
            }
        }
    }

    receiveFor?.let { product ->
        ReceiveStockDialog(
            product = product,
            onDismiss = { receiveFor = null },
            onConfirm = { quantity, cost, note ->
                viewModel.receive(product, quantity, cost, note)
                receiveFor = null
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StockCard(line: StockLine, onReceive: () -> Unit) {
    val product = line.product
    val accent = categoryColor(product.category)

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = when {
            line.isNegative -> MaterialTheme.colorScheme.errorContainer
            line.isLow -> VereinsColors.warningContainer
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        contentColor = when {
            line.isNegative -> MaterialTheme.colorScheme.onErrorContainer
            line.isLow -> VereinsColors.onWarningContainer
            else -> MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.width(4.dp).height(28.dp),
                    shape = MaterialTheme.shapes.extraSmall,
                    color = accent,
                    content = {}
                )
                Spacer(Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(product.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        text = stockSummary(product),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(onClick = onReceive) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(Spacing.xs))
                    Text("Eingang")
                }
            }

            if (line.servingsByVariant.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.sm))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    line.servingsByVariant.forEach { (label, count) ->
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ) {
                            Text(
                                text = "$label · ${count}×",
                                style = MoneySmall,
                                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                            )
                        }
                    }
                }
            }

            if (line.isNegative) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = "Es wurde mehr verkauft als vorhanden. Eingang buchen oder korrigieren.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/** One line of plain German describing what is physically there. */
private fun stockSummary(product: Product): String = when (product.stockMode) {
    StockMode.PIECE -> "${product.stockQuantity} ${product.stockUnit}"
    StockMode.BULK -> {
        val volume = Stock.servableVolume(product)
        val open = product.openContainerRemaining
        buildString {
            append(format1(volume)).append(" ").append(product.stockUnit).append(" verfügbar")
            append(" · ").append(product.fullContainers).append(" Gebinde")
            if (open > 0.0) append(" + ").append(format1(open)).append(" ").append(product.stockUnit).append(" offen")
        }
    }
}

private fun format1(value: Double) = String.format(Locale.GERMANY, "%.1f", value)

@Composable
private fun ReceiveStockDialog(
    product: Product,
    onDismiss: () -> Unit,
    onConfirm: (quantity: Double, cost: Double?, note: String?) -> Unit
) {
    val isBulk = product.stockMode == StockMode.BULK
    var quantity by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    val parsedQuantity = Money.parse(quantity)
    val parsedCost = if (cost.isBlank()) null else Money.parse(cost)
    val quantityValid = parsedQuantity != null && parsedQuantity != 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Eingang · ${product.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    text = if (isBulk) {
                        "Anzahl Gebinde à ${format1(product.containerSize)} ${product.stockUnit}. " +
                            "Pro Gebinde werden ${format1(product.containerLoss)} ${product.stockUnit} " +
                            "Schwund abgezogen."
                    } else {
                        "Anzahl in ${product.stockUnit}."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    listOf(1, 2, 5, 10).forEach { preset ->
                        FilterChip(
                            selected = quantity == preset.toString(),
                            onClick = { quantity = preset.toString() },
                            label = { Text("+$preset") },
                            shape = MaterialTheme.shapes.small
                        )
                    }
                }

                OutlinedTextField(
                    value = quantity,
                    onValueChange = { quantity = it },
                    label = { Text(if (isBulk) "Gebinde" else "Menge") },
                    isError = quantity.isNotBlank() && !quantityValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = cost,
                    onValueChange = { cost = it },
                    label = { Text("Einkaufspreis gesamt (optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Notiz / Lieferant (optional)") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )

                if (quantityValid && isBulk) {
                    val preview = Stock.receive(product, parsedQuantity!!)
                    Text(
                        text = "Danach: ${Stock.servingsRemaining(preview)} × " +
                            "${format1(product.servingSize)} ${product.stockUnit} verfügbar",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(parsedQuantity ?: 0.0, parsedCost, note.ifBlank { null }) },
                enabled = quantityValid
            ) { Text("Buchen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
private fun EntryHistory(
    entries: List<com.example.vereins_kassensystem.data.entity.StockEntry>,
    modifier: Modifier = Modifier
) {
    if (entries.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Tune,
            title = "Noch keine Eingänge",
            supportingText = "Gebuchte Lieferungen und Korrekturen erscheinen hier.",
            modifier = modifier
        )
        return
    }

    val dateFormat = remember { SimpleDateFormat("dd. MMM · HH:mm", Locale.GERMANY) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        items(entries, key = { it.id }) { entry ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .padding(Spacing.md)
                        .heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.productName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = buildString {
                                append(dateFormat.format(Date(entry.timestamp)))
                                append(" · ").append(entry.unitLabel)
                                entry.note?.let { append(" · ").append(it) }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = (if (entry.quantity >= 0) "+" else "") + format1(entry.quantity),
                            style = MoneySmall,
                            color = if (entry.quantity >= 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                        entry.totalCost?.let { MoneyText(amount = it, style = MoneySmall) }
                    }
                }
            }
        }
    }
}
