package com.example.vereins_kassensystem.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.vereins_kassensystem.data.CashCount
import com.example.vereins_kassensystem.data.entity.Member
import com.example.vereins_kassensystem.ui.format.Money
import com.example.vereins_kassensystem.ui.icons.VdIcons
import com.example.vereins_kassensystem.ui.theme.MoneyLarge
import com.example.vereins_kassensystem.ui.theme.MoneyMedium
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.TouchTarget
import com.example.vereins_kassensystem.ui.theme.VereinsColors

/** So breit wird ein Zähldialog höchstens: Kacheln und Tastenfeld nebeneinander, ohne dass die Tasten zu Balken werden. */
private val CountDialogMaxWidth = 920.dp

/** Ohne Zählung reicht die Breite eines gewöhnlichen Dialogs. */
private val PlainDialogMaxWidth = 560.dp

/**
 * Kasse öffnen (Konzept 4.5) — aus dem Warenkorb heraus, denn ohne offene Kasse wird nicht
 * kassiert. Mit Barkasse wird das Wechselgeld nach Stückelung gezählt; ohne Barkasse nimmt
 * die Theke kein Bargeld, dann gibt es nichts zu zählen, und Deckel und Karte gehen. Wer
 * öffnet, ist ein Mitglied aus der Liste, kein freier Name.
 *
 * [onOpen] bekommt das gezählte Wechselgeld, oder null für eine Kasse ohne Barkasse.
 */
@Composable
fun OpenCashDialog(
    members: List<Member>,
    onDismiss: () -> Unit,
    onOpen: (by: String, openingCount: Double?) -> Unit,
) {
    var by by remember { mutableStateOf("") }
    var withDrawer by remember { mutableStateOf(true) }
    var count by remember { mutableStateOf(CashCount()) }

    CashDialogFrame(
        title = "Kasse öffnen",
        wide = withDrawer,
        onDismiss = onDismiss,
        content = {
            WhoRow("Wer öffnet", by, members) { by = it }
            Spacer(Modifier.height(Spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                ModeTile(
                    title = "Mit Barkasse",
                    detail = "Das Wechselgeld wird gezählt.",
                    selected = withDrawer,
                    onClick = { withDrawer = true },
                    modifier = Modifier.weight(1f)
                )
                ModeTile(
                    title = "Ohne Barkasse",
                    detail = "Kein Bargeld — nur Deckel und Karte.",
                    selected = !withDrawer,
                    onClick = { withDrawer = false },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(Spacing.lg))
            if (withDrawer) {
                CashCountPane(count = count, onCountChange = { count = it })
            } else {
                Text(
                    "An dieser Theke wird kein Bargeld genommen: „Bar“ ist im Verkauf aus, gezählt wird nichts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        footer = {
            if (withDrawer) {
                Text("Wechselgeld gezählt", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MoneyText(amount = count.total, style = MoneyLarge)
                Spacer(Modifier.height(Spacing.md))
            }
            DialogButtons(
                confirm = when {
                    !withDrawer -> "Ohne Barkasse öffnen"
                    // Nichts gezählt ist erlaubt — eine leere Lade gibt es —, aber es steht auf dem Knopf.
                    count.totalCents == 0L -> "Mit leerer Lade öffnen"
                    else -> "Öffnen"
                },
                enabled = by.isNotBlank(),
                onDismiss = onDismiss,
                onConfirm = { onOpen(by, if (withDrawer) count.total else null) }
            )
        }
    )
}

/**
 * Kasse schließen mit Barkasse: den Bestand nach Stückelung zählen. Die Kasse rechnet, was in
 * der Lade sein müsste; weicht die Zählung ab, braucht es einen Grund, und die Differenz wird
 * mit Namen vermerkt.
 */
@Composable
fun CloseCashDialog(
    expected: Double,
    members: List<Member>,
    defaultBy: String,
    onDismiss: () -> Unit,
    onClose: (closingCount: Double, by: String, note: String?) -> Unit,
) {
    var by by remember { mutableStateOf(defaultBy) }
    var count by remember { mutableStateOf(CashCount()) }
    var note by remember { mutableStateOf("") }
    val difference = Money.cents(count.total - expected)
    val differs = difference != 0.0

    CashDialogFrame(
        title = "Kasse schließen",
        wide = true,
        onDismiss = onDismiss,
        content = {
            WhoRow("Wer zählt", by, members) { by = it }
            Spacer(Modifier.height(Spacing.lg))
            CashCountPane(count = count, onCountChange = { count = it })
            Spacer(Modifier.height(Spacing.lg))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(if (differs) "Grund für die Differenz" else "Notiz") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl), verticalAlignment = Alignment.Bottom) {
                Column {
                    Text("Soll", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MoneyText(amount = expected, style = MoneyMedium)
                }
                Column {
                    Text("Gezählt", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MoneyText(amount = count.total, style = MoneyLarge)
                }
                Column {
                    Text("Differenz", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Bernstein: Aufmerksamkeit, kein Fehler — eine Differenz wird erklärt, nicht verweigert.
                    MoneyText(
                        amount = difference,
                        style = MoneyMedium,
                        signed = true,
                        color = if (differs) VereinsColors.warning else MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(Spacing.md))
            DialogButtons(
                confirm = "Schließen",
                enabled = by.isNotBlank() && !(differs && note.isBlank()),
                onDismiss = onDismiss,
                onConfirm = { onClose(count.total, by.trim(), note.trim().ifEmpty { null }) }
            )
        }
    )
}

/** Wer — ein Mitglied aus der Liste. Der Knopf zeigt den Namen und öffnet die Auswahl. */
@Composable
internal fun WhoRow(label: String, name: String, members: List<Member>, onPicked: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { picking = true },
        modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.sales),
        shape = MaterialTheme.shapes.small
    ) {
        Icon(VdIcons.Groups, contentDescription = null)
        Spacer(Modifier.width(Spacing.sm))
        Text(if (name.isBlank()) "$label: Mitglied wählen" else "$label: $name")
    }
    if (picking) MemberSelectionDialog(members = members, onDismiss = { picking = false }, onMemberSelected = { onPicked(it.name) })
}

/**
 * Mit oder ohne Barkasse: zwei Flächen statt zweier Chips, denn das hier liegt im Verkaufsweg.
 * Die gewählte trägt den Rahmen wie ein gewähltes Feld — keine Füllung in Messing, das ist der Deckel.
 */
@Composable
private fun ModeTile(title: String, detail: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = modifier.heightIn(min = TouchTarget.sales),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DialogButtons(confirm: String, enabled: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onDismiss) { Text("Abbrechen") }
        Spacer(Modifier.width(Spacing.sm))
        Button(
            onClick = onConfirm,
            enabled = enabled,
            modifier = Modifier.heightIn(min = TouchTarget.sales),
            shape = MaterialTheme.shapes.medium
        ) { Text(confirm, style = MaterialTheme.typography.labelLarge) }
    }
}

/**
 * Ein eigener Rahmen im Aussehen des Material-Dialogs, wie beim Bezahlen: Der AlertDialog wird
 * nie breiter als 560 dp, und die Zählung braucht die Breite für das Tastenfeld daneben. Der
 * Inhalt rollt, Summe und Knöpfe bleiben stehen.
 */
@Composable
private fun CashDialogFrame(
    title: String,
    wide: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .padding(Spacing.xl)
                .widthIn(max = if (wide) CountDialogMaxWidth else PlainDialogMaxWidth),
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation
        ) {
            Column(modifier = Modifier.padding(Spacing.xl)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = AlertDialogDefaults.titleContentColor)
                Spacer(Modifier.height(Spacing.lg))
                Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = content)
                Spacer(Modifier.height(Spacing.lg))
                footer()
            }
        }
    }
}
