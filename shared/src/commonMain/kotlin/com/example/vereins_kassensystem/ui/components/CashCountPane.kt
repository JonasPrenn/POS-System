package com.example.vereins_kassensystem.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.vereins_kassensystem.data.CashCount
import com.example.vereins_kassensystem.ui.format.Money
import com.example.vereins_kassensystem.ui.theme.MoneyMedium
import com.example.vereins_kassensystem.ui.theme.MoneySmall
import com.example.vereins_kassensystem.ui.theme.Pill
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.TouchTarget

/** Ab dieser Breite steht das Tastenfeld neben den Kacheln statt darunter. */
private val KeypadBesideWidth = 720.dp

/** So schmal darf eine Kachel werden, bevor eine Spalte wegfällt: „100 €“, „× 12“ und „1.200,00 €“ passen gerade. */
private val TileMinWidth = 115.dp

/** Mehr Stück eines Werts liegen in keiner Lade. */
private const val MaxDigits = 4

/**
 * Die Lade zählen, nach Stückelung (Konzept 4.5): je Schein und Münze die Anzahl, die Summe
 * rechnet die Kasse. So zählt man eine Lade von oben nach unten durch, Stapel für Stapel, ohne
 * im Kopf zu addieren.
 *
 * Bedienung, seit Oktober 2026 deutlicher: Über dem Tastenfeld steht, was gerade gezählt wird
 * („Wie viele 50-€-Scheine?“), mit der Anzahl groß wie in einem Eingabefeld und dem Betrag
 * daneben. Die gewählte Kachel ist in Tinte gerahmt und leicht hinterlegt; eine Kachel antippen
 * wählt sie. „Weiter zu 20 €“ springt zum nächsten Wert und sagt, zu welchem. Leere Kacheln
 * zeigen nichts statt eines Strichs — ein „–“ las sich wie ein Minus-Knopf.
 *
 * Breite, nicht Ausrichtung: Ist Platz, steht das Tastenfeld neben den Kacheln, sonst darunter.
 * Ist das Fenster niedrig, verlangt der Dialog es daneben ([keypadBeside]) — übereinander passt
 * dann beides nicht. Die Kacheln nehmen so viele Spalten, wie ihr Platz hergibt, zwei bis vier.
 */
@Composable
fun CashCountPane(
    count: CashCount,
    onCountChange: (CashCount) -> Unit,
    modifier: Modifier = Modifier,
    keypadBeside: Boolean? = null,
) {
    var active by remember { mutableStateOf(CashCount.DENOMINATIONS.first()) }
    val values = CashCount.DENOMINATIONS
    val next = values.getOrNull(values.indexOf(active) + 1)

    // Getippt wird in die Anzahl des gewählten Werts; die Anzahl ist, was dasteht.
    fun edit(change: (String) -> String) {
        val typed = count.count(active).takeIf { it > 0 }?.toString().orEmpty()
        val nextText = change(typed).take(MaxDigits)
        onCountChange(count.with(active, nextText.toIntOrNull() ?: 0))
    }

    val keypad: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            CountDisplay(value = active, pieces = count.count(active))
            NumericKeypad(
                onDigit = { c -> edit { it + c } },
                onBackspace = { edit { it.dropLast(1) } },
                onNext = { active = next ?: active },
                // Zwei Zeilen mit Absicht, der Betrag nie getrennt: „Weiter“ und darunter „20 €“ — auch mit großer Schrift.
                nextLabel = next?.let { "Weiter\n" + Money.denomination(it).replace(" ", "\u00A0") } ?: "Fertig"
            )
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        if (keypadBeside ?: (maxWidth >= KeypadBesideWidth)) {
            // Die ganze Lade neben dem Tastenfeld, ohne zu rollen: Eine Kachel, die beim „Weiter“
            // unten aus dem Bild läuft, zählt niemand mit.
            val columns = columnsFor((maxWidth - Spacing.xl) * 3 / 5)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                Box(modifier = Modifier.weight(3f)) { Tiles(count, active, columns, onSelect = { active = it }) }
                Box(modifier = Modifier.weight(2f)) { keypad() }
            }
        } else {
            val columns = columnsFor(maxWidth)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                Tiles(count, active, columns, onSelect = { active = it })
                keypad()
            }
        }
    }
}

private fun columnsFor(width: androidx.compose.ui.unit.Dp): Int = (width / TileMinWidth).toInt().coerceIn(2, 4)

/**
 * Was gerade gezählt wird: die Frage, die Anzahl wie in einem Feld, und was sie wert ist. Die
 * Anzeige verbindet Kachel und Tastenfeld — ohne sie stand das Tastenfeld allein da, und niemand
 * wusste, wohin es schreibt.
 */
@Composable
private fun CountDisplay(value: Int, pieces: Int) {
    Column {
        Text(
            text = "Wie viele ${pieceName(value)}?",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2
        )
        Spacer(Modifier.height(Spacing.xs))
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = selectedOutline(),
            modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.sales)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (pieces == 0) "0" else pieces.toString(),
                    style = MoneyMedium,
                    color = if (pieces == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text("Stück", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        Row {
            Text("= ", style = MoneySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MoneyText(amount = pieces * value / 100.0, style = MoneySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** „50-€-Scheine“, „2-€-Münzen“, „20-Cent-Münzen“ — wie man an der Lade fragt. */
private fun pieceName(value: Int): String {
    val amount = if (value >= 100) "${value / 100}-€" else "$value-Cent"
    return if (value >= CashCount.SMALLEST_NOTE) "$amount-Scheine" else "$amount-Münzen"
}

/** Scheine und Münzen getrennt, wie sie in der Lade liegen. */
@Composable
private fun Tiles(count: CashCount, active: Int, columns: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        val (notes, coins) = CashCount.DENOMINATIONS.partition { it >= CashCount.SMALLEST_NOTE }
        TileGroup("Scheine", notes, count, active, columns, onSelect)
        Spacer(Modifier.height(Spacing.xs))
        TileGroup("Münzen", coins, count, active, columns, onSelect)
    }
}

@Composable
private fun TileGroup(title: String, values: List<Int>, count: CashCount, active: Int, columns: Int, onSelect: (Int) -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    values.chunked(columns).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            row.forEach { value ->
                DenominationTile(
                    value = value,
                    pieces = count.count(value),
                    active = value == active,
                    onClick = { onSelect(value) },
                    modifier = Modifier.weight(1f)
                )
            }
            // Eine halbe Reihe bleibt links bündig, statt die Kacheln zu dehnen.
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/**
 * Ein Wert der Lade: oben der Wert, darunter die gezählte Anzahl als Tinten-Marke. Noch nicht
 * gezählt: nur der Wert. Gewählt: Tintenrahmen und leicht hinterlegt, wie das Feld über dem
 * Tastenfeld.
 */
@Composable
private fun DenominationTile(value: Int, pieces: Int, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        selected = active,
        onClick = onClick,
        modifier = modifier.heightIn(min = TouchTarget.sales),
        shape = MaterialTheme.shapes.medium,
        color = if (active) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = if (active) selectedOutline() else hairline(MaterialTheme.colorScheme.outline)
    ) {
        // Der Wert allein in der ersten Zeile, damit „100 €“ auch mit großer Schrift nie umbricht;
        // darunter die Anzahl. Den Betrag zeigt das Feld über dem Tastenfeld — in der Kachel wurde er
        // bei „23×“ abgeschnitten.
        Column(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                Money.denomination(value),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                softWrap = false
            )
            if (pieces > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = Pill,
                        color = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface
                    ) {
                        Text(
                            text = "$pieces×",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = Spacing.sm)
                        )
                    }
                }
            }
        }
    }
}
