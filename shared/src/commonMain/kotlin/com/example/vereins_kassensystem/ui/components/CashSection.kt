package com.example.vereins_kassensystem.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.example.vereins_kassensystem.data.entity.CashMovementKind
import com.example.vereins_kassensystem.platform.VdDate
import com.example.vereins_kassensystem.ui.format.Money
import com.example.vereins_kassensystem.ui.icons.VdIcons
import com.example.vereins_kassensystem.ui.theme.MoneyMedium
import com.example.vereins_kassensystem.ui.theme.MoneySmall
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.TouchTarget
import com.example.vereins_kassensystem.viewmodel.CashState

/**
 * Die Lade dieses Geräts auf der Übersicht (Konzept 4.5): Schicht öffnen mit gezähltem
 * Wechselgeld, Entnahme und Einlage mit Grund, Schicht schließen mit gezähltem Bestand.
 * Das Soll rechnet die App vor; die Differenz steht danach in der Verwaltung, mit Namen.
 *
 * Nichts davon liegt im Verkaufsweg: Wer nicht zählt, verkauft trotzdem.
 */
@Composable
fun CashSection(
    state: CashState,
    onOpen: (openingCount: Double, by: String) -> Unit,
    onMove: (kind: CashMovementKind, amount: Double, reason: String, by: String) -> Unit,
    onClose: (closingCount: Double, by: String, note: String?) -> Unit,
) {
    var dialog by remember { mutableStateOf<CashDialog?>(null) }
    val session = state.session

    VdSection(title = "Kasse", icon = VdIcons.PointOfSale) {
        if (session == null) {
            Text(
                "Keine Schicht offen. Beim Öffnen wird das Wechselgeld gezählt; beim Schließen der Bestand — die Differenz zum Soll landet im Kassenbuch.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { dialog = CashDialog.Open },
                modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.min),
                shape = MaterialTheme.shapes.small
            ) { Text("Schicht öffnen") }
        } else {
            Text(
                "Offen seit ${VdDate.timeOfDay(session.openedAt)} · ${session.openedBy}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Müsste in der Lade sein", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MoneyText(amount = state.expected, style = MoneyMedium)
                }
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    Text("Bar seit Öffnung", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MoneyText(amount = state.cashIn, style = MoneySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(
                    onClick = { dialog = CashDialog.Move },
                    modifier = Modifier.weight(1f).heightIn(min = TouchTarget.min),
                    shape = MaterialTheme.shapes.small
                ) { Text("Entnahme · Einlage") }
                Button(
                    onClick = { dialog = CashDialog.Close },
                    modifier = Modifier.weight(1f).heightIn(min = TouchTarget.min),
                    shape = MaterialTheme.shapes.small
                ) { Text("Schicht schließen") }
            }
        }
    }

    when (dialog) {
        CashDialog.Open -> CountDialog(
            title = "Schicht öffnen", amountLabel = "Wechselgeld in der Lade, gezählt", confirm = "Öffnen",
            onDismiss = { dialog = null }, onConfirm = { amount, by, _ -> onOpen(amount, by); dialog = null }
        )
        CashDialog.Move -> MovementDialog(onDismiss = { dialog = null }, onConfirm = { kind, amount, reason, by -> onMove(kind, amount, reason, by); dialog = null })
        CashDialog.Close -> CountDialog(
            title = "Schicht schließen", amountLabel = "Bestand in der Lade, gezählt", confirm = "Schließen", expected = state.expected, withNote = true,
            onDismiss = { dialog = null }, onConfirm = { amount, by, note -> onClose(amount, by, note); dialog = null }
        )
        null -> Unit
    }
}

private enum class CashDialog { Open, Move, Close }

@Composable
private fun CountDialog(
    title: String,
    amountLabel: String,
    confirm: String,
    expected: Double? = null,
    withNote: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (amount: Double, by: String, note: String?) -> Unit,
) {
    var amount by remember { mutableStateOf("") }
    var by by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val parsed = Money.parse(amount)
    val difference = if (expected != null && parsed != null) Money.cents(parsed - expected) else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it }, label = { Text(amountLabel) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
                )
                if (expected != null) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text("Soll: ${Money.format(expected)}", style = MaterialTheme.typography.bodyMedium)
                    if (difference != null && difference != 0.0) {
                        Text(
                            "Differenz ${Money.formatSigned(difference)} — wird mit Namen vermerkt.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.lg))
                OutlinedTextField(value = by, onValueChange = { by = it }, label = { Text("Wer zählt") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (withNote) {
                    Spacer(Modifier.height(Spacing.lg))
                    OutlinedTextField(
                        value = note, onValueChange = { note = it },
                        label = { Text(if (difference != null && difference != 0.0) "Grund für die Differenz" else "Notiz") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(parsed ?: 0.0, by.trim(), note.trim().ifEmpty { null }) },
                enabled = parsed != null && parsed >= 0 && by.isNotBlank() && !(withNote && difference != null && difference != 0.0 && note.isBlank())
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
private fun MovementDialog(onDismiss: () -> Unit, onConfirm: (CashMovementKind, Double, String, String) -> Unit) {
    var kind by remember { mutableStateOf(CashMovementKind.WITHDRAWAL) }
    var amount by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var by by remember { mutableStateOf("") }
    val parsed = Money.parse(amount)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Geld aus der Lade oder hinein") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FilterChip(selected = kind == CashMovementKind.WITHDRAWAL, onClick = { kind = CashMovementKind.WITHDRAWAL }, label = { Text("Entnahme") })
                    FilterChip(selected = kind == CashMovementKind.DEPOSIT, onClick = { kind = CashMovementKind.DEPOSIT }, label = { Text("Einlage") })
                }
                Spacer(Modifier.height(Spacing.lg))
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it }, label = { Text("Betrag") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Spacing.lg))
                OutlinedTextField(
                    value = reason, onValueChange = { reason = it },
                    label = { Text(if (kind == CashMovementKind.WITHDRAWAL) "Wofür (Bank, Bareinkauf …)" else "Woher (Wechselgeld, Bank …)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Spacing.lg))
                OutlinedTextField(value = by, onValueChange = { by = it }, label = { Text("Wer") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(kind, parsed ?: 0.0, reason.trim(), by.trim()) },
                enabled = parsed != null && parsed > 0 && reason.isNotBlank() && by.isNotBlank()
            ) { Text("Buchen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}
