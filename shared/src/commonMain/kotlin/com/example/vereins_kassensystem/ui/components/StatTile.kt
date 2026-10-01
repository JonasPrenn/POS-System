package com.example.vereins_kassensystem.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.vereins_kassensystem.ui.theme.MoneyMedium
import com.example.vereins_kassensystem.ui.theme.Spacing

/**
 * A single figure with its label — revenue, transaction count, tips.
 *
 * Replaces `DashboardSummaryCard` and `SummaryCard`, which were two copies of this idea
 * that had drifted apart on radius, elevation and label colour.
 *
 * Prefer [MoneyStatTile] for amounts so the figure gets tabular figures.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface
) {
    StatTileFrame(modifier, contentColor, icon, label) {
        Text(
            text = value,
            style = MoneyMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** [StatTile] for an amount. */
@Composable
fun MoneyStatTile(
    label: String,
    amount: Double,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface
) {
    StatTileFrame(modifier, contentColor, icon, label) {
        MoneyText(
            amount = amount,
            style = MoneyMedium,
            // Null ist kein Geld: Ein leerer Tag steht neutral, nicht grün.
            color = if (amount == 0.0) MaterialTheme.colorScheme.onSurface else contentColor
        )
    }
}

@Composable
private fun StatTileFrame(
    modifier: Modifier,
    contentColor: Color,
    icon: ImageVector,
    label: String,
    value: @Composable () -> Unit
) {
    // Kassen-Standard: Die Kennzahl steht auf einer weißen Fläche. Ihre Bedeutung trägt die
    // Zahl selbst (grün Geld, Messing Deckel, Blau Karte); der Rahmen bleibt neutral.
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = contentColor,
        border = hairline()
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null, // the label beside it already names it
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            value()
        }
    }
}
