package com.example.vereins_kassensystem.ui.components

import androidx.compose.foundation.BorderStroke
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
import com.example.vereins_kassensystem.ui.theme.MoneySmall
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.TouchTarget

/** Ab dieser Breite steht das Tastenfeld neben den Kacheln statt darunter. */
private val KeypadBesideWidth = 720.dp

/** So schmal darf eine Kachel werden, bevor eine Spalte wegfällt. */
private val TileMinWidth = 150.dp

/** Mehr Stück eines Werts liegen in keiner Lade. */
private const val MaxDigits = 4

/**
 * Die Lade zählen, nach Stückelung (Konzept 4.5): je Schein und Münze die Anzahl, die Summe
 * rechnet die Kasse. Eine Kachel antippen, dann schreibt das Tastenfeld dort hinein — wie beim
 * Bargeld im Bezahldialog; „Weiter“ springt zum nächsten Wert. So zählt man eine Lade von
 * oben nach unten durch, Stapel für Stapel, ohne im Kopf zu addieren.
 *
 * Breite, nicht Ausrichtung: Ist Platz, steht das Tastenfeld neben den Kacheln, sonst darunter.
 */
@Composable
fun CashCountPane(
    count: CashCount,
    onCountChange: (CashCount) -> Unit,
    modifier: Modifier = Modifier,
) {
    var active by remember { mutableStateOf(CashCount.DENOMINATIONS.first()) }

    // Getippt wird in die Anzahl des gewählten Werts; die Anzahl ist, was dasteht.
    fun edit(change: (String) -> String) {
        val typed = count.count(active).takeIf { it > 0 }?.toString().orEmpty()
        val next = change(typed).take(MaxDigits)
        onCountChange(count.with(active, next.toIntOrNull() ?: 0))
    }

    val keypad: @Composable () -> Unit = {
        NumericKeypad(
            onDigit = { c -> edit { it + c } },
            onBackspace = { edit { it.dropLast(1) } },
            onNext = {
                val values = CashCount.DENOMINATIONS
                active = values[(values.indexOf(active) + 1).coerceAtMost(values.lastIndex)]
            }
        )
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        if (maxWidth >= KeypadBesideWidth) {
            // Vier Kacheln je Reihe: So passt die ganze Lade neben das Tastenfeld, ohne zu rollen —
            // eine Kachel, die beim „Weiter“ unten aus dem Bild läuft, zählt niemand mit.
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                Box(modifier = Modifier.weight(2f)) { Tiles(count, active, columns = 4, onSelect = { active = it }) }
                Box(modifier = Modifier.weight(1f)) { keypad() }
            }
        } else {
            val columns = (maxWidth / TileMinWidth).toInt().coerceIn(2, 4)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                Tiles(count, active, columns, onSelect = { active = it })
                keypad()
            }
        }
    }
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

/** Ein Wert der Lade: wie viele Stück, und was sie zusammen sind. Die gewählte Kachel trägt einen Rahmen. */
@Composable
private fun DenominationTile(value: Int, pieces: Int, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        selected = active,
        onClick = onClick,
        modifier = modifier.heightIn(min = TouchTarget.sales),
        shape = MaterialTheme.shapes.medium,
        // Eine Stufe heller als der Dialog, damit die Kachel als antippbar zu erkennen ist.
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Money.denomination(value), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    text = if (pieces == 0) "–" else "× $pieces",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (pieces == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
            }
            MoneyText(
                amount = pieces * value / 100.0,
                style = MoneySmall,
                color = if (pieces == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
