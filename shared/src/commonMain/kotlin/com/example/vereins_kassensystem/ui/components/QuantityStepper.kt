package com.example.vereins_kassensystem.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.vereins_kassensystem.ui.theme.MoneySmall
import com.example.vereins_kassensystem.ui.theme.TouchTarget

/**
 * − n + for a cart line.
 *
 * The old cart offered only "remove the whole line", so correcting a mis-tap on the
 * third Weißbier meant deleting all three and re-adding two. At quantity 1 the minus
 * turns into a delete, which is the same gesture people expect from a shopping cart.
 *
 * Both buttons fire a haptic: in a loud room that tick is the only confirmation the
 * press landed.
 */
@Composable
fun QuantityStepper(
    quantity: Int,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val haptics = LocalHapticFeedback.current

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onDecrease()
                },
                enabled = enabled,
                modifier = Modifier.size(TouchTarget.min)
            ) {
                Icon(
                    imageVector = if (quantity <= 1) Icons.Default.Delete else Icons.Default.Remove,
                    contentDescription = if (quantity <= 1) "Position entfernen" else "Menge verringern",
                    modifier = Modifier.size(18.dp),
                    tint = if (quantity <= 1) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box(
                modifier = Modifier.widthIn(min = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = quantity.toString(),
                    style = MoneySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onIncrease()
                },
                enabled = enabled,
                modifier = Modifier.size(TouchTarget.min)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Menge erhöhen",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
