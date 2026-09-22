package com.example.vereins_kassensystem.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.vereins_kassensystem.data.entity.ContainerType
import com.example.vereins_kassensystem.data.entity.StockItem
import com.example.vereins_kassensystem.data.entity.StockTracking
import com.example.vereins_kassensystem.ui.components.MoneyText
import com.example.vereins_kassensystem.ui.format.Money
import com.example.vereins_kassensystem.ui.theme.MoneyMedium
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.icons.VdIcons
import androidx.compose.foundation.Image
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import com.example.vereins_kassensystem.platform.loadImageBitmap
import com.example.vereins_kassensystem.platform.rememberPhotoCapture
import com.example.vereins_kassensystem.platform.rememberPhotoPicker

/** One editable row of the receipt being entered. */
data class DeliveryLineDraft(
    val key: Long,
    val itemId: String? = null,
    val containerTypeId: String? = null,
    val quantity: String = "",
    val cost: String = ""
)

/**
 * Booking a Kassabon.
 *
 * Entered the way the receipt reads — several lines with their prices, plus a photo of
 * the receipt itself. A delivery is one event, so it is booked as one: the stock movement
 * and the money that left the club account stay attached to each other, and the photo is
 * the evidence behind both when the treasurer asks in November.
 *
 * The receipt total is asked for separately and compared against the lines. Receipts
 * routinely carry things the club does not track as stock, so a difference is shown as
 * information rather than treated as a mistake.
 */
@Composable
fun DeliveryDialog(
    stockItems: List<StockItem>,
    containerTypes: List<ContainerType>,
    onDismiss: () -> Unit,
    onConfirm: (
        supplier: String,
        receiptTotal: Double?,
        photoUri: String?,
        note: String?,
        lines: List<Triple<StockItem, ContainerType?, Pair<Double, Double?>>>
    ) -> Unit
) {
    var supplier by remember { mutableStateOf("") }
    var receiptTotal by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var photoUri by remember { mutableStateOf<String?>(null) }

    var nextKey by remember { mutableStateOf(1L) }
    val lines = remember { mutableStateListOf(DeliveryLineDraft(key = 0L)) }
    var pickerFor by remember { mutableStateOf<Long?>(null) }

    // Kamera und Mediathek liefern beide einen Verweis auf eine Datei im eigenen
    // Ordner; wie sie dorthin kommt, weiß nur die Plattform.
    val camera = rememberPhotoCapture { photoUri = it }
    val gallery = rememberPhotoPicker { photoUri = it }

    // What the entered lines add up to, for comparison with the receipt.
    val linesTotal = lines.sumOf { Money.parse(it.cost) ?: 0.0 }
    val receiptTotalValue = Money.parse(receiptTotal)
    val difference = receiptTotalValue?.let { it - linesTotal }

    val resolved = lines.mapNotNull { draft ->
        val item = stockItems.firstOrNull { it.id == draft.itemId } ?: return@mapNotNull null
        val qty = Money.parse(draft.quantity) ?: return@mapNotNull null
        if (qty == 0.0) return@mapNotNull null
        val type = containerTypes.firstOrNull { it.id == draft.containerTypeId }
        if (item.tracking == StockTracking.CONTAINER && type == null) return@mapNotNull null
        Triple(item, type, qty to Money.parse(draft.cost))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(Spacing.lg).fillMaxWidth(0.92f),
        title = { Text("Wareneingang erfassen") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        OutlinedTextField(
                            value = supplier,
                            onValueChange = { supplier = it },
                            label = { Text("Lieferant") },
                            placeholder = { Text("z. B. Metro") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.weight(1.4f)
                        )
                        OutlinedTextField(
                            value = receiptTotal,
                            onValueChange = { receiptTotal = it },
                            label = { Text("Bon gesamt") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }

                items(lines, key = { it.key }) { draft ->
                    val item = stockItems.firstOrNull { it.id == draft.itemId }
                    val itemTypes = containerTypes.filter { it.stockItemId == draft.itemId }

                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { pickerFor = draft.key },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = item?.name ?: "Artikel wählen",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (item == null) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(
                                onClick = { lines.remove(draft) },
                                enabled = lines.size > 1
                            ) {
                                Icon(VdIcons.Delete, contentDescription = "Position entfernen")
                            }
                        }

                        if (item?.tracking == StockTracking.CONTAINER && itemTypes.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                itemTypes.forEach { type ->
                                    FilterChip(
                                        selected = draft.containerTypeId == type.id,
                                        onClick = {
                                            val i = lines.indexOf(draft)
                                            if (i != -1) lines[i] = draft.copy(containerTypeId = type.id)
                                        },
                                        label = { Text(type.label) },
                                        shape = MaterialTheme.shapes.small
                                    )
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            OutlinedTextField(
                                value = draft.quantity,
                                onValueChange = { new ->
                                    val i = lines.indexOf(draft)
                                    if (i != -1) lines[i] = draft.copy(quantity = new)
                                },
                                label = {
                                    Text(if (item?.tracking == StockTracking.CONTAINER) "Gebinde" else "Menge")
                                },
                                suffix = { if (item?.tracking != StockTracking.CONTAINER) Text(item?.unit ?: "") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = draft.cost,
                                onValueChange = { new ->
                                    val i = lines.indexOf(draft)
                                    if (i != -1) lines[i] = draft.copy(cost = new)
                                },
                                label = { Text("Preis (€)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }

                item {
                    TextButton(
                        onClick = {
                            lines.add(DeliveryLineDraft(key = nextKey))
                            nextKey += 1
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(VdIcons.Add, contentDescription = null)
                        Spacer(Modifier.width(Spacing.sm))
                        Text("Position hinzufügen")
                    }
                }

                item {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(Spacing.md)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Summe Positionen", modifier = Modifier.weight(1f))
                                MoneyText(amount = linesTotal, style = MoneyMedium)
                            }
                            if (difference != null && kotlin.math.abs(difference) >= 0.01) {
                                Text(
                                    text = "Differenz zum Bon: ${Money.formatSigned(difference)} — " +
                                        "auf dem Bon stehen vermutlich Positionen, die nicht als " +
                                        "Lagerartikel geführt werden.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                item {
                    Text("Kassabon", style = MaterialTheme.typography.labelMedium)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { camera.open() }) {
                            Icon(VdIcons.PhotoCamera, contentDescription = null)
                            Spacer(Modifier.width(Spacing.xs))
                            Text("Foto aufnehmen")
                        }
                        TextButton(onClick = { gallery.open() }) { Text("Aus Galerie") }
                    }
                    photoUri?.let { ref ->
                        // Wird je Verweis einmal geladen; solange das läuft, bleibt die
                        // Fläche leer statt zu springen.
                        val bitmap by produceState<ImageBitmap?>(null, ref) { value = loadImageBitmap(ref) }
                        Box(modifier = Modifier.fillMaxWidth()) {
                            bitmap?.let { image ->
                                Image(
                                    bitmap = image,
                                    contentDescription = "Kassabon",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 220.dp)
                                )
                            }
                            IconButton(
                                onClick = { photoUri = null },
                                modifier = Modifier.align(Alignment.TopEnd)
                            ) {
                                Icon(VdIcons.Delete, contentDescription = "Foto entfernen")
                            }
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Notiz (optional)") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        supplier.trim(),
                        receiptTotalValue,
                        photoUri,
                        note.ifBlank { null },
                        resolved
                    )
                },
                enabled = resolved.isNotEmpty()
            ) { Text("Buchen (${resolved.size})") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )

    pickerFor?.let { key ->
        AlertDialog(
            onDismissRequest = { pickerFor = null },
            title = { Text("Lagerartikel wählen") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(stockItems, key = { it.id }) { candidate ->
                        TextButton(
                            onClick = {
                                val i = lines.indexOfFirst { it.key == key }
                                if (i != -1) {
                                    val defaultType = containerTypes.firstOrNull { it.stockItemId == candidate.id }
                                    lines[i] = lines[i].copy(
                                        itemId = candidate.id,
                                        containerTypeId = defaultType?.id
                                    )
                                }
                                pickerFor = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${candidate.name} (${candidate.unit})",
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickerFor = null }) { Text("Schließen") } }
        )
    }
}
