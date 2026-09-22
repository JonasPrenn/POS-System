package com.example.vereins_kassensystem.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.vereins_kassensystem.data.entity.ContainerType
import com.example.vereins_kassensystem.data.entity.StockItem
import com.example.vereins_kassensystem.data.entity.StockTracking
import com.example.vereins_kassensystem.ui.format.Money
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.icons.VdIcons

/**
 * Creating or editing a Lagerartikel, together with the vessel sizes it arrives in.
 *
 * Sizes are edited here rather than on a screen of their own because they only mean
 * anything in the context of their item — a "50 l" on its own is not a thing the cellar
 * has. The starting yield is asked for in plain terms, since it is a guess that real
 * emptied kegs will overwrite anyway.
 */
@Composable
fun StockItemDialog(
    item: StockItem? = null,
    containerTypes: List<ContainerType> = emptyList(),
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onConfirm: (StockItem, List<ContainerType>) -> Unit
) {
    var name by remember { mutableStateOf(item?.name ?: "") }
    var unit by remember { mutableStateOf(item?.unit ?: "Stk") }
    var tracking by remember { mutableStateOf(item?.tracking ?: StockTracking.SIMPLE) }
    var quantity by remember { mutableStateOf(item?.simpleQuantity?.toString() ?: "0") }
    var minLevel by remember { mutableStateOf(item?.minLevel?.toString() ?: "0") }
    val types = remember { mutableStateListOf<ContainerType>().apply { addAll(containerTypes) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item == null) "Lagerartikel anlegen" else "Lagerartikel bearbeiten") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    Text("Wie wird gezählt?", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        FilterChip(
                            selected = tracking == StockTracking.SIMPLE,
                            onClick = { tracking = StockTracking.SIMPLE; if (unit == "l") unit = "Stk" },
                            label = { Text("Stückzahl") },
                            shape = MaterialTheme.shapes.small
                        )
                        FilterChip(
                            selected = tracking == StockTracking.CONTAINER,
                            onClick = { tracking = StockTracking.CONTAINER; if (unit == "Stk") unit = "l" },
                            label = { Text("Gebinde / Fass") },
                            shape = MaterialTheme.shapes.small
                        )
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        OutlinedTextField(
                            value = unit,
                            onValueChange = { unit = it },
                            label = { Text("Einheit") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = minLevel,
                            onValueChange = { minLevel = it },
                            label = { Text("Warnung ab") },
                            suffix = { Text(unit) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (tracking == StockTracking.SIMPLE) {
                    item {
                        OutlinedTextField(
                            value = quantity,
                            onValueChange = { quantity = it },
                            label = { Text("Aktueller Bestand") },
                            suffix = { Text(unit) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    item {
                        Spacer(Modifier.height(Spacing.xs))
                        Text("Gebindegrößen", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "Mehrere Größen dürfen gleichzeitig im Keller stehen. Der " +
                                "Startertrag ist nur eine Schätzung – sobald ein Fass leer " +
                                "gemeldet wird, lernt die App den echten Wert.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    items(types) { type ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            OutlinedTextField(
                                value = type.label,
                                onValueChange = { new ->
                                    val i = types.indexOf(type)
                                    if (i != -1) types[i] = types[i].copy(label = new)
                                },
                                label = { Text("Bezeichnung") },
                                singleLine = true,
                                shape = MaterialTheme.shapes.extraSmall,
                                modifier = Modifier.weight(1.1f)
                            )
                            OutlinedTextField(
                                value = if (type.nominalSize == 0.0) "" else type.nominalSize.toString(),
                                onValueChange = { new ->
                                    val parsed = Money.parse(new) ?: 0.0
                                    val i = types.indexOf(type)
                                    // Keep the estimate tracking the nominal size until it
                                    // is edited, so the common case needs one number.
                                    if (i != -1) types[i] = types[i].copy(
                                        nominalSize = parsed,
                                        initialYieldEstimate = if (types[i].initialYieldEstimate == 0.0)
                                            (parsed * 0.97) else types[i].initialYieldEstimate
                                    )
                                },
                                label = { Text("Inhalt") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                shape = MaterialTheme.shapes.extraSmall,
                                modifier = Modifier.weight(0.8f)
                            )
                            OutlinedTextField(
                                value = if (type.initialYieldEstimate == 0.0) "" else type.initialYieldEstimate.toString(),
                                onValueChange = { new ->
                                    val i = types.indexOf(type)
                                    if (i != -1) types[i] = types[i].copy(initialYieldEstimate = Money.parse(new) ?: 0.0)
                                },
                                label = { Text("Ertrag") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                shape = MaterialTheme.shapes.extraSmall,
                                modifier = Modifier.weight(0.8f)
                            )
                            IconButton(onClick = { types.remove(type) }) {
                                Icon(VdIcons.Delete, contentDescription = "Größe entfernen", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    item {
                        TextButton(
                            onClick = { types.add(ContainerType(stockItemId = item?.id ?: "", label = "", nominalSize = 0.0, initialYieldEstimate = 0.0)) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(VdIcons.Add, contentDescription = null)
                            Spacer(Modifier.width(Spacing.sm))
                            Text("Gebindegröße hinzufügen")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val base = item ?: StockItem(name = "", unit = unit)
                    onConfirm(
                        base.copy(
                            name = name.trim(),
                            unit = unit.trim().ifBlank { "Stk" },
                            tracking = tracking,
                            simpleQuantity = if (tracking == StockTracking.SIMPLE) (Money.parse(quantity) ?: 0.0) else base.simpleQuantity,
                            minLevel = Money.parse(minLevel) ?: 0.0
                        ),
                        types.filter { it.label.isNotBlank() && it.nominalSize > 0.0 }
                    )
                },
                enabled = name.isNotBlank()
            ) { Text("Speichern") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Löschen", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        }
    )
}
