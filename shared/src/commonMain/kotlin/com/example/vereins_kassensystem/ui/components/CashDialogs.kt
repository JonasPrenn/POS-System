package com.example.vereins_kassensystem.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.vereins_kassensystem.data.CashCount
import com.example.vereins_kassensystem.data.entity.Member
import com.example.vereins_kassensystem.ui.format.Money
import com.example.vereins_kassensystem.ui.icons.VdIcons
import com.example.vereins_kassensystem.ui.theme.moneyButtonColors
import com.example.vereins_kassensystem.ui.theme.Pill
import com.example.vereins_kassensystem.ui.theme.MoneyLarge
import com.example.vereins_kassensystem.ui.theme.MoneyMedium
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.TouchTarget
import com.example.vereins_kassensystem.ui.theme.VereinsColors

/** So breit wird ein Zähldialog höchstens: Kacheln und Tastenfeld nebeneinander, ohne dass die Tasten zu Balken werden. */
private val CountDialogMaxWidth = 920.dp

/** Ohne Zählung reicht die Breite eines gewöhnlichen Dialogs. */
private val PlainDialogMaxWidth = 560.dp

/** Nebeneinander — Bedienung links, Zählen rechts — braucht es mindestens diese Fensterbreite. */
private val SideBySideMinWidth = 840.dp

/** Nebeneinander auf einem niedrigen, sehr breiten Fenster: nicht breiter als das. */
private val SideBySideMaxWidth = 1100.dp

/** Darunter stehen Soll, Gezählt und Differenz untereinander statt nebeneinander. */
private val NarrowSummaryWidth = 600.dp

/** Die linke Spalte nebeneinander: wer, womit, die Summe und die Knöpfe. */
private val ControlsWidth = 280.dp

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
        counting = withDrawer,
        summaryBesideButtons = true,
        onDismiss = onDismiss,
        controls = { sideBySide ->
            WhoRow("Wer öffnet", by, members) { by = it }
            Spacer(Modifier.height(Spacing.md))
            ModeChoice(withDrawer = withDrawer, onChange = { withDrawer = it }, stacked = sideBySide)
        },
        counter = { sideBySide ->
            if (withDrawer) {
                CashCountPane(count = count, onCountChange = { count = it }, keypadBeside = if (sideBySide) true else null)
            } else {
                Text(
                    "An dieser Theke wird kein Bargeld genommen: „Bar“ ist im Verkauf aus, gezählt wird nichts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        summary = {
            if (withDrawer) {
                Text("Wechselgeld gezählt", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MoneyText(amount = count.total, style = MoneyLarge)
            }
        },
        buttons = { sideBySide ->
            DialogButtons(
                confirm = when {
                    !withDrawer -> "Ohne Barkasse öffnen"
                    // Nichts gezählt ist erlaubt — eine leere Lade gibt es —, aber es steht auf dem Knopf.
                    count.totalCents == 0L -> "Mit leerer Lade öffnen"
                    else -> "Öffnen"
                },
                enabled = by.isNotBlank(),
                hint = if (by.isBlank()) "Erst oben wählen, wer öffnet." else null,
                stacked = sideBySide,
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
    // Vor der ersten Eingabe ist nichts gezählt — dann gibt es auch keine Differenz zu erklären.
    // Eine eingetippte 0 zählt als gezählt: Eine leere Lade muss sich schließen lassen.
    var touched by remember { mutableStateOf(false) }
    val counted = touched || expected == 0.0
    val differs = counted && difference != 0.0
    // Bernstein: Aufmerksamkeit, kein Fehler — eine Differenz wird erklärt, nicht verweigert.
    val differenceColor = if (differs) VereinsColors.warning else VereinsColors.money

    CashDialogFrame(
        title = "Kasse schließen",
        counting = true,
        onDismiss = onDismiss,
        controls = {
            WhoRow("Wer zählt", by, members) { by = it }
        },
        counter = { sideBySide ->
            CashCountPane(count = count, onCountChange = { count = it; touched = true }, keypadBeside = if (sideBySide) true else null)
        },
        // Die Notiz kommt nach dem Zählen: Untereinander steht sie unter dem Tastenfeld, damit das ganz im Bild bleibt.
        details = {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(if (differs) "Grund für die Differenz" else "Notiz (freiwillig)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        summary = { sideBySide ->
            if (!counted) {
                SummaryLine("Soll in der Lade", expected, MoneyMedium)
                Text(
                    "Zähle die Lade rechts, Schein für Schein. Die Differenz steht hier, sobald gezählt ist.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (sideBySide || windowSizeDp().width < NarrowSummaryWidth) {
                // Untereinander auch am Telefon: Nebeneinander fiel dort die Differenz rechts aus dem Bild.
                SummaryLine("Soll", expected, MoneyMedium)
                SummaryLine("Gezählt", count.total, MoneyLarge)
                SummaryLine("Differenz", difference, MoneyMedium, signed = true, color = differenceColor)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl), verticalAlignment = Alignment.Bottom) {
                    SummaryColumn("Soll", expected, MoneyMedium)
                    SummaryColumn("Gezählt", count.total, MoneyLarge)
                    SummaryColumn("Differenz", difference, MoneyMedium, signed = true, color = differenceColor)
                }
            }
        },
        buttons = { sideBySide ->
            DialogButtons(
                confirm = "Schließen",
                enabled = by.isNotBlank() && counted && !(differs && note.isBlank()),
                hint = when {
                    by.isBlank() -> "Erst oben wählen, wer zählt."
                    !counted -> "Noch nichts gezählt."
                    differs && note.isBlank() -> "Die Zählung weicht ab — bitte einen Grund eintragen."
                    else -> null
                },
                stacked = sideBySide,
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
        shape = Pill
    ) {
        Icon(VdIcons.Groups, contentDescription = null)
        Spacer(Modifier.width(Spacing.sm))
        Text(if (name.isBlank()) "$label: Mitglied wählen" else "$label: $name")
    }
    if (picking) MemberSelectionDialog(members = members, onDismiss = { picking = false }, onMemberSelected = { onPicked(it.name) })
}

/** Mit oder ohne Barkasse: nebeneinander, oder untereinander, wenn die Spalte schmal ist. */
@Composable
private fun ModeChoice(withDrawer: Boolean, onChange: (Boolean) -> Unit, stacked: Boolean) {
    val withTile: @Composable (Modifier) -> Unit = { modifier ->
        ModeTile("Mit Barkasse", "Das Wechselgeld wird gezählt.", selected = withDrawer, onClick = { onChange(true) }, modifier = modifier)
    }
    val withoutTile: @Composable (Modifier) -> Unit = { modifier ->
        ModeTile("Ohne Barkasse", "Kein Bargeld — nur Deckel und Karte.", selected = !withDrawer, onClick = { onChange(false) }, modifier = modifier)
    }
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            withTile(Modifier.fillMaxWidth())
            withoutTile(Modifier.fillMaxWidth())
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            withTile(Modifier.weight(1f))
            withoutTile(Modifier.weight(1f))
        }
    }
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
        // Beide als Fläche erkennbar, auch die nicht gewählte: weiß mit Rand, gewählt mit Tintenlinie.
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = if (selected) selectedOutline() else hairline(MaterialTheme.colorScheme.outline)
    ) {
        // Ein Radioknopf vorne: Dass genau eins von beiden gilt, soll man sehen, nicht aus der Rahmenstärke erraten.
        Row(modifier = Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(Spacing.sm))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Ein Betrag mit Beschriftung darüber, für die Summe unter der Zählung. */
@Composable
private fun SummaryColumn(label: String, amount: Double, style: TextStyle, signed: Boolean = false, color: Color = Color.Unspecified) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoneyText(amount = amount, style = style, signed = signed, color = color)
    }
}

/** Ein Betrag mit Beschriftung davor, für die schmale Spalte nebeneinander. */
@Composable
private fun SummaryLine(label: String, amount: Double, style: TextStyle, signed: Boolean = false, color: Color = Color.Unspecified) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        MoneyText(amount = amount, style = style, signed = signed, color = color)
    }
}

@Composable
private fun DialogButtons(
    confirm: String,
    enabled: Boolean,
    stacked: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    /** Warum der Knopf noch nicht geht. Ein grauer Knopf ohne Grund lässt raten. */
    hint: String? = null
) {
    if (hint != null) {
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = VereinsColors.warning,
            modifier = Modifier.padding(bottom = Spacing.sm)
        )
    }
    val confirmButton: @Composable (Modifier) -> Unit = { modifier ->
        Button(
            onClick = onConfirm,
            enabled = enabled,
            modifier = modifier.heightIn(min = TouchTarget.sales),
            shape = Pill,
            // Kasse öffnen und schließen heißt Bargeld zählen: Das ist Geld, also grün.
            colors = moneyButtonColors()
        ) { Text(confirm, style = MaterialTheme.typography.labelLarge) }
    }
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            confirmButton(Modifier.fillMaxWidth())
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Abbrechen") }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
            Spacer(Modifier.width(Spacing.sm))
            confirmButton(Modifier)
        }
    }
}

/**
 * Ein eigener Rahmen im Aussehen des Material-Dialogs, wie beim Bezahlen: Der AlertDialog wird
 * nie breiter als 560 dp, und die Zählung braucht die Breite für das Tastenfeld daneben.
 *
 * Genug Höhe: alles untereinander, der Inhalt rollt, Summe und Knöpfe bleiben stehen. Wenig
 * Höhe bei genug Breite (ein 8-Zoll-Tablet quer, 960 × 600 dp): nebeneinander — links wer,
 * womit, die Summe und die Knöpfe, rechts Kacheln und Tastenfeld. So passt die ganze Lade,
 * ohne dass etwas rollt.
 */
@Composable
private fun CashDialogFrame(
    title: String,
    counting: Boolean,
    onDismiss: () -> Unit,
    controls: @Composable ColumnScope.(sideBySide: Boolean) -> Unit,
    counter: @Composable (sideBySide: Boolean) -> Unit,
    summary: @Composable ColumnScope.(sideBySide: Boolean) -> Unit,
    buttons: @Composable ColumnScope.(sideBySide: Boolean) -> Unit,
    details: (@Composable ColumnScope.(sideBySide: Boolean) -> Unit)? = null,
    summaryBesideButtons: Boolean = false,
) {
    val window = windowSizeDp()
    val sideBySide = counting && window.height < CompactWindowHeight && window.width >= SideBySideMinWidth
    val titleText: @Composable () -> Unit = {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = AlertDialogDefaults.titleContentColor)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            // Mit Zählung schmale Ränder: Auf einem 8-Zoll-Tablet hoch zählt jede Zeile Höhe.
            modifier = Modifier
                .padding(if (counting) Spacing.md else Spacing.xl)
                .widthIn(max = when {
                    sideBySide -> SideBySideMaxWidth
                    counting -> CountDialogMaxWidth
                    else -> PlainDialogMaxWidth
                }),
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation
        ) {
            if (sideBySide) {
                // Rollen können beide Seiten nur noch, wenn das Fenster noch niedriger ist als ein 8-Zoll-Tablet quer.
                Row(modifier = Modifier.padding(Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                    Column(modifier = Modifier.width(ControlsWidth).verticalScroll(rememberScrollState())) {
                        titleText()
                        Spacer(Modifier.height(Spacing.md))
                        controls(true)
                        Spacer(Modifier.height(Spacing.lg))
                        summary(true)
                        // Die Notiz nach der Summe: erst zählen, dann sehen, ob es etwas zu erklären gibt.
                        details?.let {
                            Spacer(Modifier.height(Spacing.md))
                            it(true)
                        }
                        Spacer(Modifier.height(Spacing.md))
                        buttons(true)
                    }
                    Box(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) { counter(true) }
                }
            } else {
                Column(modifier = Modifier.padding(if (counting) Spacing.lg else Spacing.xl)) {
                    titleText()
                    Spacer(Modifier.height(Spacing.md))
                    Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        controls(false)
                        Spacer(Modifier.height(Spacing.lg))
                        counter(false)
                        details?.let {
                            Spacer(Modifier.height(Spacing.lg))
                            it(false)
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))
                    if (summaryBesideButtons) {
                        // Eine Summe passt neben die Knöpfe — das spart eine Zeile Höhe.
                        Row(verticalAlignment = Alignment.Bottom) {
                            Column(modifier = Modifier.weight(1f)) { summary(false) }
                            Column { buttons(false) }
                        }
                    } else {
                        summary(false)
                        Spacer(Modifier.height(Spacing.md))
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            Column { buttons(false) }
                        }
                    }
                }
            }
        }
    }
}
