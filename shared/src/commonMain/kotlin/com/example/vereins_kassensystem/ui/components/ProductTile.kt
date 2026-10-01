package com.example.vereins_kassensystem.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.vereins_kassensystem.data.entity.Product
import com.example.vereins_kassensystem.ui.format.Money
import com.example.vereins_kassensystem.ui.theme.MoneyMedium
import com.example.vereins_kassensystem.ui.theme.Pill
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.TouchTarget
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProductTile(
    product: Product,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Servings still possible, or null when nothing is tracked. Passed in rather than
     * derived here: a product draws from its recipe, so only the screen holding the
     * cellar state can work it out.
     */
    servingsLeft: Int? = null,
    lowThreshold: Int = 10,
    /** Was ein langer Druck anbietet, etwa „Ausblenden“ — als kleines Menü an der Kachel. */
    longPress: TileAction? = null
) {
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
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

    val isLowStock = servingsLeft != null && servingsLeft <= lowThreshold
    val accent = categoryColor(product.category)

    // Die Kachel füllt ihre Zelle im Raster; das Menü des langen Drucks hängt an ihr.
    Box(propagateMinConstraints = true) {
    Surface(
        // Kleiner als früher (Wunsch vom 30. September 2026): Auf einem 8-Zoll-Tablet quer passen
        // so vier Kacheln in eine Reihe statt drei — und jede bleibt weit über Daumengröße.
        modifier = modifier
            .heightIn(min = 88.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    pulseKey++
                    onClick()
                },
                onLongClickLabel = longPress?.label,
                onLongClick = longPress?.let {
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    }
                }
            )
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
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = hairline()
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
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
                        shape = Pill,
                        color = VereinsColors.warningContainer,
                        contentColor = VereinsColors.onWarningContainer
                    ) {
                        Text(
                            text = servingsLeft.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.xs))

            Text(
                text = product.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(Spacing.xs))

            if (product.hasVariants) {
                Text(
                    text = "Varianten",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
    if (longPress != null) {
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(longPress.label, style = MaterialTheme.typography.titleMedium) },
                onClick = {
                    menuOpen = false
                    longPress.onClick()
                },
                modifier = Modifier.heightIn(min = TouchTarget.sales)
            )
        }
    }
    }
}

/** Eine Aktion für den langen Druck auf eine Kachel. */
data class TileAction(val label: String, val onClick: () -> Unit)

