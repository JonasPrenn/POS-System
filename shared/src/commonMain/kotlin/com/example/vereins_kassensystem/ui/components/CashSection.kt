package com.example.vereins_kassensystem.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.example.vereins_kassensystem.data.entity.CashMovementKind
import com.example.vereins_kassensystem.data.entity.Member
import com.example.vereins_kassensystem.platform.VdDate
import com.example.vereins_kassensystem.ui.format.Money
import com.example.vereins_kassensystem.ui.icons.VdIcons
import com.example.vereins_kassensystem.ui.theme.moneyButtonColors
import com.example.vereins_kassensystem.ui.theme.Pill
import com.example.vereins_kassensystem.ui.theme.VereinsColors
import com.example.vereins_kassensystem.ui.theme.MoneyMedium
import com.example.vereins_kassensystem.ui.theme.MoneySmall
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.TouchTarget
import com.example.vereins_kassensystem.viewmodel.CashState

/**
 * Die Kasse dieses Geräts auf der Übersicht (Konzept 4.5). Geöffnet wird sie im Verkauf, im
 * Warenkorb — ohne offene Kasse wird nicht kassiert. Hier steht, wer sie seit wann hat, und
 * hier wird Geld entnommen oder eingelegt und die Kasse geschlossen: mit Barkasse nach
 * Zählung des Bestands, ohne Barkasse ohne Zählung. Wer zählt oder schließt, ist ein Mitglied
 * aus der Liste — kein freier Name.
 */
@Composable
fun CashSection(
    state: CashState,
    members: List<Member>,
    onGoToSales: () -> Unit,
    onMove: (kind: CashMovementKind, amount: Double, reason: String, by: String) -> Unit,
    onClose: (closingCount: Double?, by: String, note: String?) -> Unit,
) {
    var dialog by remember { mutableStateOf<CashDialog?>(null) }
    val session = state.session

    VdSection(title = "Kasse", icon = VdIcons.PointOfSale) {
        when {
            session == null -> {
                Text(
                    "Die Kasse ist zu. Geöffnet wird im Verkauf, im Warenkorb — mit Barkasse und gezähltem Wechselgeld, oder ohne Barkasse, wenn kein Bargeld genommen wird. Vorher wird nicht kassiert.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = onGoToSales,
                    modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.min),
                    shape = Pill
                ) { Text("Zum Verkauf") }
            }
            session.cashless -> {
                Text(
                    "${session.openedBy} · seit ${VdDate.timeOfDay(session.openedAt)} · ohne Barkasse",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Die Theke nimmt kein Bargeld: „Bar“ ist im Verkauf aus, Deckel und Karte gehen. Gezählt wird nichts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    colors = moneyButtonColors(),
                    onClick = { dialog = CashDialog.End },
                    modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.min),
                    shape = Pill
                ) { Text("Kasse schließen") }
            }
            else -> {
                Text(
                    "${session.openedBy} · seit ${VdDate.timeOfDay(session.openedAt)} · mit Barkasse",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Müsste in der Lade sein", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MoneyText(amount = state.expected, style = MoneyMedium)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Bar seit Beginn", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MoneyText(amount = state.cashIn, style = MoneySmall, color = VereinsColors.money)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(
                        onClick = { dialog = CashDialog.Move },
                        modifier = Modifier.weight(1f).heightIn(min = TouchTarget.min),
                        shape = Pill
                    ) { Text("Entnahme · Einlage") }
                    Button(
                        colors = moneyButtonColors(),
                        onClick = { dialog = CashDialog.Close },
                        modifier = Modifier.weight(1f).heightIn(min = TouchTarget.min),
                        shape = Pill
                    ) { Text("Kasse schließen") }
                }
            }
        }
    }

    when (dialog) {
        CashDialog.Move -> MovementDialog(
            members = members, defaultBy = session?.openedBy.orEmpty(),
            onDismiss = { dialog = null }, onConfirm = { kind, amount, reason, by -> onMove(kind, amount, reason, by); dialog = null }
        )
        CashDialog.Close -> CloseCashDialog(
            expected = state.expected, members = members, defaultBy = session?.openedBy.orEmpty(),
            onDismiss = { dialog = null }, onClose = { amount, by, note -> onClose(amount, by, note); dialog = null }
        )
        CashDialog.End -> EndDialog(
            members = members, defaultBy = session?.openedBy.orEmpty(),
            onDismiss = { dialog = null }, onConfirm = { by, note -> onClose(null, by, note); dialog = null }
        )
        null -> Unit
    }
}

private enum class CashDialog { Move, Close, End }

@Composable
private fun EndDialog(members: List<Member>, defaultBy: String, onDismiss: () -> Unit, onConfirm: (by: String, note: String?) -> Unit) {
    var by by remember { mutableStateOf(defaultBy) }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kasse schließen") },
        text = {
            Column {
                Text("Ohne Barkasse gibt es nichts zu zählen.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(Spacing.lg))
                WhoRow("Wer", by, members) { by = it }
                Spacer(Modifier.height(Spacing.lg))
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Notiz") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(onClick = { onConfirm(by, note.trim().ifEmpty { null }) }, enabled = by.isNotBlank(), colors = moneyButtonColors()) { Text("Schließen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
private fun MovementDialog(members: List<Member>, defaultBy: String, onDismiss: () -> Unit, onConfirm: (CashMovementKind, Double, String, String) -> Unit) {
    var kind by remember { mutableStateOf(CashMovementKind.WITHDRAWAL) }
    var amount by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var by by remember { mutableStateOf(defaultBy) }
    val parsed = Money.parse(amount)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Geld aus der Lade oder hinein") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FilterPill(selected = kind == CashMovementKind.WITHDRAWAL, onClick = { kind = CashMovementKind.WITHDRAWAL }, label = { Text("Entnahme") })
                    FilterPill(selected = kind == CashMovementKind.DEPOSIT, onClick = { kind = CashMovementKind.DEPOSIT }, label = { Text("Einlage") })
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
                WhoRow("Wer", by, members) { by = it }
            }
        },
        confirmButton = {
            Button(
                colors = moneyButtonColors(),
                onClick = { onConfirm(kind, parsed ?: 0.0, reason.trim(), by.trim()) },
                enabled = parsed != null && parsed > 0 && reason.isNotBlank() && by.isNotBlank()
            ) { Text("Buchen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}
