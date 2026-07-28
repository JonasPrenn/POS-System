package com.example.vereins_kassensystem.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.vereins_kassensystem.data.entity.Product
import com.example.vereins_kassensystem.data.entity.StockMode
import com.example.vereins_kassensystem.data.stock.Stock
import com.example.vereins_kassensystem.ui.format.Money
import com.example.vereins_kassensystem.ui.theme.MoneyMedium
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.VereinsColors
import com.example.vereins_kassensystem.ui.theme.categoryColor
import kotlinx.coroutines.delay

/**
 * A product on the sales grid.
 *
 * Three changes from the old tile that matter at the counter:
 *
 *  - the whole tile is the target, not a 36dp "+" circle in the corner;
 *  - a press fires a haptic and a brief scale pulse, because with no feedback at all in
 *    a loud room people tap again and get charged twice;
 *  - the category gets a colour dot, so the grid can be scanned by colour instead of
 *    read caption by caption.
 *
 * Height is a minimum rather than a 1:1 aspect ratio so the tile grows instead of
 * clipping when the reader has large text turned on.
 */
@Composable
fun ProductTile(
    product: Product,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    var pulseKey by remember { mutableIntStateOf(0) }
    var pulsing by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (pulsing) 0.96f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "tilePulse"
    )

    LaunchedEffect(pulseKey) {
        if (pulseKey > 0) {
            pulsing = true
            delay(120)
            pulsing = false
        }
    }

    val isLowStock = Stock.isLow(product)
    val accent = categoryColor(product.category)

    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            pulseKey++
            onClick()
        },
        modifier = modifier
            .heightIn(min = 112.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .semantics {
                contentDescription = buildString {
                    append(product.name)
                    if (product.hasVariants) append(", Varianten verfügbar")
                    else append(", ${Money.format(product.price)}")
                    if (isLowStock) append(", Bestand niedrig")
                }
            },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(8.dp),
                    shape = CircleShape,
                    color = accent,
                    content = {}
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = product.category,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isLowStock) {
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = VereinsColors.warningContainer,
                        contentColor = VereinsColors.onWarningContainer
                    ) {
                        Text(
                            text = stockBadgeText(product),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.sm))

            Text(
                text = product.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(Spacing.sm))

            if (product.hasVariants) {
                Text(
                    text = "Varianten",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent
                )
            } else {
                MoneyText(
                    amount = product.price,
                    style = MoneyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * What the low-stock badge shows: pieces for counted products, remaining servings for
 * draught ones — "3 Halbe left" is the useful number, not "1,5 litres".
 */
private fun stockBadgeText(product: Product): String = when (product.stockMode) {
    StockMode.PIECE -> product.stockQuantity.toString()
    StockMode.BULK -> Stock.servingsRemaining(product).toString()
}
