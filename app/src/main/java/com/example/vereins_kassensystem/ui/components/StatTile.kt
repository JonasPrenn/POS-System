package com.example.vereins_kassensystem.ui.components

import androidx.compose.foundation.layout.Column
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
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface
) {
    StatTileFrame(modifier, containerColor, contentColor, icon, label) {
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
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface
) {
    StatTileFrame(modifier, containerColor, contentColor, icon, label) {
        MoneyText(
            amount = amount,
            style = MoneyMedium,
            color = contentColor
        )
    }
}

@Composable
private fun StatTileFrame(
    modifier: Modifier,
    containerColor: Color,
    contentColor: Color,
    icon: ImageVector,
    label: String,
    value: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = contentColor
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Icon(
                imageVector = icon,
                contentDescription = null, // the label below already names it
                modifier = Modifier.size(20.dp),
                tint = contentColor.copy(alpha = 0.75f)
            )
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(Spacing.xs))
            value()
        }
    }
}
