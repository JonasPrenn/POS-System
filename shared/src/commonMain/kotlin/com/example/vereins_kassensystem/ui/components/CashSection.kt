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
import androidx.compose.material3.FilterChip
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
import com.example.vereins_kassensystem.ui.theme.MoneyMedium
import com.example.vereins_kassensystem.ui.theme.MoneySmall
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.TouchTarget
import com.example.vereins_kassensystem.viewmodel.CashState

/**
 * Der Bardienst dieses Geräts auf der Übersicht (Konzept 4.5): Wer die Theke übernimmt,
 * beginnt hier — mit Barkasse, dann wird das Wechselgeld gezählt, Entnahme und Einlage
 * brauchen einen Grund, und am Ende wird der Bestand gezählt; oder ohne Barkasse, dann nimmt
 * die Theke kein Bargeld, und es gibt nichts zu zählen. Wer beginnt, zählt oder beendet, ist
 * ein Mitglied aus der Liste — kein freier Name.
 *
 * Nichts davon liegt im Verkaufsweg: Wer nicht zählt, verkauft trotzdem. Nur „Bar“ ist aus,
 * solange ein Bardienst ohne Barkasse läuft.
 */
@Composable
fun CashSection(
    state: CashState,
    members: List<Member>,
    onOpen: (by: String, openingCount: Double?) -> Unit,
    onMove: (kind: CashMovementKind, amount: Double, reason: String, by: String) -> Unit,
    onClose: (closingCount: Double?, by: String, note: String?) -> Unit,
) {
    var dialog by remember { mutableStateOf<CashDialog?>(null) }
    val session = state.session

    VdSection(title = "Bardienst", icon = VdIcons.PointOfSale) {
        when {
            session == null -> {
                Text(
                    "Kein Bardienst. Wer die Theke übernimmt, beginnt hier — mit Barkasse und gezähltem Wechselgeld, oder ohne Barkasse, wenn kein Bargeld genommen wird.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { dialog = CashDialog.Start },
                    modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.min),
                    shape = MaterialTheme.shapes.small
                ) { Text("Bardienst beginnen") }
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
                    onClick = { dialog = CashDialog.End },
                    modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.min),
                    shape = MaterialTheme.shapes.small
                ) { Text("Bardienst beenden") }
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
                    ) { Text("Kasse schließen") }
                }
            }
        }
    }

    when (dialog) {
        CashDialog.Start -> StartDialog(members = members, onDismiss = { dialog = null }, onConfirm = { by, amount -> onOpen(by, amount); dialog = null })
        CashDialog.Move -> MovementDialog(
            members = members, defaultBy = session?.openedBy.orEmpty(),
            onDismiss = { dialog = null }, onConfirm = { kind, amount, reason, by -> onMove(kind, amount, reason, by); dialog = null }
        )
        CashDialog.Close -> CountDialog(
            title = "Kasse schließen", amountLabel = "Bestand in der Lade, gezählt", confirm = "Schließen", expected = state.expected,
            members = members, defaultBy = session?.openedBy.orEmpty(),
            onDismiss = { dialog = null }, onConfirm = { amount, by, note -> onClose(amount, by, note); dialog = null }
        )
        CashDialog.End -> EndDialog(
            members = members, defaultBy = session?.openedBy.orEmpty(),
            onDismiss = { dialog = null }, onConfirm = { by, note -> onClose(null, by, note); dialog = null }
        )
        null -> Unit
    }
}

private enum class CashDialog { Start, Move, Close, End }

/** Wer — ein Mitglied aus der Liste. Der Knopf zeigt den Namen und öffnet die Auswahl. */
@Composable
private fun WhoRow(label: String, name: String, members: List<Member>, onPicked: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { picking = true },
        modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.min),
        shape = MaterialTheme.shapes.small
    ) {
        Icon(VdIcons.Groups, contentDescription = null)
        Spacer(Modifier.width(Spacing.sm))
        Text(if (name.isBlank()) "$label: Mitglied wählen" else "$label: $name")
    }
    if (picking) MemberSelectionDialog(members = members, onDismiss = { picking = false }, onMemberSelected = { onPicked(it.name) })
}

@Composable
private fun StartDialog(members: List<Member>, onDismiss: () -> Unit, onConfirm: (by: String, openingCount: Double?) -> Unit) {
    var by by remember { mutableStateOf("") }
    var withCash by remember { mutableStateOf(true) }
    var amount by remember { mutableStateOf("") }
    val parsed = Money.parse(amount)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bardienst beginnen") },
        text = {
            Column {
                WhoRow("Wer", by, members) { by = it }
                Spacer(Modifier.height(Spacing.lg))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FilterChip(selected = withCash, onClick = { withCash = true }, label = { Text("Mit Barkasse") })
                    FilterChip(selected = !withCash, onClick = { withCash = false }, label = { Text("Ohne Barkasse") })
                }
                Spacer(Modifier.height(Spacing.md))
                if (withCash) {
                    OutlinedTextField(
                        value = amount, onValueChange = { amount = it }, label = { Text("Wechselgeld in der Lade, gezählt") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        "Kein Bargeld an dieser Theke: „Bar“ ist im Verkauf aus, gezählt wird nichts. Deckel und Karte gehen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(by, if (withCash) parsed ?: 0.0 else null) },
                enabled = by.isNotBlank() && (!withCash || (parsed != null && parsed >= 0))
            ) { Text("Beginnen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
private fun EndDialog(members: List<Member>, defaultBy: String, onDismiss: () -> Unit, onConfirm: (by: String, note: String?) -> Unit) {
    var by by remember { mutableStateOf(defaultBy) }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bardienst beenden") },
        text = {
            Column {
                Text("Ohne Barkasse gibt es nichts zu zählen.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(Spacing.lg))
                WhoRow("Wer", by, members) { by = it }
                Spacer(Modifier.height(Spacing.lg))
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Notiz") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(onClick = { onConfirm(by, note.trim().ifEmpty { null }) }, enabled = by.isNotBlank()) { Text("Beenden") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
private fun CountDialog(
    title: String,
    amountLabel: String,
    confirm: String,
    members: List<Member>,
    defaultBy: String,
    expected: Double? = null,
    onDismiss: () -> Unit,
    onConfirm: (amount: Double, by: String, note: String?) -> Unit,
) {
    var amount by remember { mutableStateOf("") }
    var by by remember { mutableStateOf(defaultBy) }
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
                WhoRow("Wer zählt", by, members) { by = it }
                Spacer(Modifier.height(Spacing.lg))
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text(if (difference != null && difference != 0.0) "Grund für die Differenz" else "Notiz") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(parsed ?: 0.0, by.trim(), note.trim().ifEmpty { null }) },
                enabled = parsed != null && parsed >= 0 && by.isNotBlank() && !(difference != null && difference != 0.0 && note.isBlank())
            ) { Text(confirm) }
        },
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
                WhoRow("Wer", by, members) { by = it }
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
