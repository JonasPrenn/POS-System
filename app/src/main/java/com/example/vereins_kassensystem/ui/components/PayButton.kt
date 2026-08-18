package com.example.vereins_kassensystem.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.example.vereins_kassensystem.ui.theme.MoneyMedium
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.TouchTarget

/** What the pay button is currently doing. */
enum class PayState { Idle, Processing, Done }

/**
 * The button that takes the money.
 *
 * Sized to [TouchTarget.sales] because it is pressed one-handed, at speed, at the end of
 * every single sale. It shows the amount rather than just "Bezahlen", so the last thing
 * seen before charging is what will be charged.
 *
 * While [state] is Processing the button is disabled — the previous flow could be
 * double-submitted by an impatient second tap.
 */
@Composable
fun PayButton(
    amount: Double,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    state: PayState = PayState.Idle,
    enabled: Boolean = true,
    label: String = "Bezahlen"
) {
    val haptics = LocalHapticFeedback.current

    Button(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget.sales),
        enabled = enabled && state == PayState.Idle,
        shape = MaterialTheme.shapes.medium
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "payState"
        ) { current ->
            when (current) {
                PayState.Idle -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(text = label, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(Spacing.md))
                    MoneyText(amount = amount, style = MoneyMedium)
                }

                PayState.Processing -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )

                PayState.Done -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Bezahlt", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
