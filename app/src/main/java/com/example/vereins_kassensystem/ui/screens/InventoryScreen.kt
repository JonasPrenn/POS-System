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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.example.vereins_kassensystem.data.entity.ContainerCloseReason
import com.example.vereins_kassensystem.data.entity.ContainerType
import com.example.vereins_kassensystem.data.entity.StockEntry
import com.example.vereins_kassensystem.data.entity.StockTracking
import com.example.vereins_kassensystem.ui.components.EmptyState
import com.example.vereins_kassensystem.ui.components.MoneyText
import com.example.vereins_kassensystem.ui.components.VdTopBar
import com.example.vereins_kassensystem.ui.format.Money
import com.example.vereins_kassensystem.ui.theme.MoneySmall
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.VereinsColors
import com.example.vereins_kassensystem.viewmodel.InventoryRow
import com.example.vereins_kassensystem.viewmodel.InventoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun fmt(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.GERMANY, "%.1f", value)

/**
 * Lagerbestand — what the cellar holds, per Lagerartikel.
 *
 * Draught items show what is on tap and what is still unopened, together with the yield
 * the app has learned for each keg size. The learned figure is shown with the number of
 * kegs behind it, so a forecast built on one observation does not look as certain as one
 * built on ten.
 */
@Composable
fun InventoryScreen(viewModel: InventoryViewModel) {
    val rows by viewModel.rows.collectAsState()
    val entries by viewModel.recentEntries.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.status.collect { snackbarHostState.showSnackbar(it) } }

    var receiveFor by remember { mutableStateOf<InventoryRow?>(null) }
    var closeFor by remember { mutableStateOf<InventoryRow?>(null) }
    var showHistory by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            VdTopBar(
                title = "Lagerbestand",
                subtitle = if (rows.isEmpty()) null else "${rows.size} Lagerartikel",
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

            rows.isEmpty() -> EmptyState(
                icon = Icons.Default.Inventory2,
                title = "Keine Lagerartikel",
                supportingText = "Lagerartikel entstehen automatisch aus deinen Produkten.",
                modifier = Modifier.padding(padding)
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(rows, key = { it.item.id }) { row ->
                    StockCard(
                        row = row,
                        onReceive = { receiveFor = row },
                        onTap = { type -> viewModel.tap(type, row.item.name) },
                        onClose = { closeFor = row }
                    )
                }
                item { Spacer(Modifier.height(Spacing.xxl)) }
            }
        }
    }

    receiveFor?.let { row ->
        ReceiveDialog(
            row = row,
            onDismiss = { receiveFor = null },
            onConfirm = { qty, type, cost, note ->
                viewModel.receive(row.item, qty, type, cost, note)
                receiveFor = null
            }
        )
    }

    closeFor?.let { row ->
        CloseContainerDialog(
            row = row,
            onDismiss = { closeFor = null },
            onConfirm = { reason, note ->
                viewModel.closeOpenContainer(row, reason, note)
                closeFor = null
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StockCard(
    row: InventoryRow,
    onReceive: () -> Unit,
    onTap: (ContainerType) -> Unit,
    onClose: () -> Unit
) {
    val item = row.item
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = when {
            row.isNegative -> MaterialTheme.colorScheme.errorContainer
            row.isLow -> VereinsColors.warningContainer
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        contentColor = when {
            row.isNegative -> MaterialTheme.colorScheme.onErrorContainer
            row.isLow -> VereinsColors.onWarningContainer
            else -> MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        text = "${fmt(row.available)} ${item.unit} verfügbar",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(onClick = onReceive) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(Spacing.xs))
                    Text("Eingang")
                }
            }

            if (item.tracking == StockTracking.CONTAINER) {
                Spacer(Modifier.height(Spacing.sm))

                // What is on tap right now, and the way to close it.
                val open = row.openContainer
                if (open != null) {
                    val type = row.yields.firstOrNull { it.containerType.id == open.containerTypeId }
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Am Hahn: ${type?.containerType?.label ?: "Gebinde"}",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "${fmt(open.drawn)} ${item.unit} gezapft · noch ca. " +
                                        "${fmt(((type?.perContainer ?: 0.0) - open.drawn).coerceAtLeast(0.0))} ${item.unit}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = onClose) { Text("Fasswechsel") }
                        }
                    }
                    Spacer(Modifier.height(Spacing.sm))
                }

                // Unopened kegs, one chip per size — this is the Anstich picker.
                if (row.fullByType.isNotEmpty()) {
                    Text(
                        text = if (open == null) "Anstechen:" else "Auf Lager:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        row.fullByType.forEach { (type, count) ->
                            FilterChip(
                                selected = false,
                                onClick = { onTap(type) },
                                label = { Text("${type.label} · ${count}×") },
                                shape = MaterialTheme.shapes.small
                            )
                        }
                    }
                }

                // The learned yield, with how much evidence is behind it.
                Spacer(Modifier.height(Spacing.sm))
                row.yields.forEach { estimate ->
                    Text(
                        text = "${estimate.containerType.label}: ø ${fmt(estimate.perContainer)} ${item.unit} " +
                            if (estimate.isLearned) "(aus ${estimate.observations} Fässern gelernt)"
                            else "(Schätzwert, noch kein Fass gemessen)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (row.spoiled > 0.0) {
                    Text(
                        text = "Verderb bisher: ${fmt(row.spoiled)} ${item.unit}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (row.isNegative) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = "Es wurde mehr verkauft als vorhanden. Eingang buchen oder korrigieren.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ReceiveDialog(
    row: InventoryRow,
    onDismiss: () -> Unit,
    onConfirm: (Double, ContainerType?, Double?, String?) -> Unit
) {
    val isContainer = row.item.tracking == StockTracking.CONTAINER
    var quantity by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(row.state.containerTypes.firstOrNull()) }

    val parsedQty = Money.parse(quantity)
    val valid = parsedQty != null && parsedQty != 0.0 && (!isContainer || selectedType != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Eingang · ${row.item.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                if (isContainer) {
                    Text("Gebindegröße", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        row.state.containerTypes.forEach { type ->
                            FilterChip(
                                selected = selectedType?.id == type.id,
                                onClick = { selectedType = type },
                                label = { Text(type.label) },
                                shape = MaterialTheme.shapes.small
                            )
                        }
                    }
                    if (row.state.containerTypes.isEmpty()) {
                        Text(
                            "Für diesen Artikel ist noch keine Gebindegröße angelegt.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

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
                    label = { Text(if (isContainer) "Anzahl Gebinde" else "Menge (${row.item.unit})") },
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        parsedQty ?: 0.0,
                        if (isContainer) selectedType else null,
                        if (cost.isBlank()) null else Money.parse(cost),
                        note.ifBlank { null }
                    )
                },
                enabled = valid
            ) { Text("Buchen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

/**
 * Closing the keg on tap.
 *
 * The distinction matters more than it looks: a keg that emptied normally is a real
 * measurement the forecast learns from, while one that went warm is a loss that must stay
 * out of the average — otherwise the app would conclude this size only ever gives up what
 * happened to be poured before it turned.
 */
@Composable
private fun CloseContainerDialog(
    row: InventoryRow,
    onDismiss: () -> Unit,
    onConfirm: (ContainerCloseReason, String?) -> Unit
) {
    var reason by remember { mutableStateOf(ContainerCloseReason.EMPTIED) }
    var note by remember { mutableStateOf("") }

    val open = row.openContainer
    val expected = row.yields.firstOrNull { it.containerType.id == open?.containerTypeId }?.perContainer ?: 0.0
    val rest = (expected - (open?.drawn ?: 0.0)).coerceAtLeast(0.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fasswechsel · ${row.item.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    text = "Bisher gezapft: ${fmt(open?.drawn ?: 0.0)} ${row.item.unit}",
                    style = MaterialTheme.typography.bodyMedium
                )

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FilterChip(
                        selected = reason == ContainerCloseReason.EMPTIED,
                        onClick = { reason = ContainerCloseReason.EMPTIED },
                        label = { Text("Leer") },
                        shape = MaterialTheme.shapes.small
                    )
                    FilterChip(
                        selected = reason == ContainerCloseReason.SPOILED,
                        onClick = { reason = ContainerCloseReason.SPOILED },
                        label = { Text("Kaputt / verdorben") },
                        shape = MaterialTheme.shapes.small
                    )
                }

                Text(
                    text = when (reason) {
                        ContainerCloseReason.EMPTIED ->
                            "Wird als echter Ertrag gewertet: die App lernt daraus, wie viel " +
                                "ein ${row.yields.firstOrNull { it.containerType.id == open?.containerTypeId }?.containerType?.label ?: "Gebinde"} " +
                                "wirklich hergibt."
                        ContainerCloseReason.SPOILED ->
                            "Rest von ca. ${fmt(rest)} ${row.item.unit} wird als Verderb gebucht " +
                                "und fließt NICHT in die Ertragsberechnung ein."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (reason == ContainerCloseReason.SPOILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(if (reason == ContainerCloseReason.SPOILED) "Grund" else "Notiz (optional)") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(reason, note.ifBlank { null }) },
                enabled = open != null && (reason == ContainerCloseReason.EMPTIED || note.isNotBlank())
            ) { Text("Buchen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
private fun EntryHistory(entries: List<StockEntry>, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Inventory2,
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
                    modifier = Modifier.padding(Spacing.md).heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.itemName, style = MaterialTheme.typography.titleSmall)
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
                            text = (if (entry.quantity >= 0) "+" else "") + fmt(entry.quantity),
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
