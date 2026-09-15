package com.example.vereins_kassensystem.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.TouchTarget

/**
 * On-screen number pad for cash-given and other amounts.
 *
 * The soft keyboard is the wrong instrument here: it covers half the screen, puts digits
 * on small keys, and its decimal separator moves with the layout. These keys are sized
 * for a thumb and the separator is always a comma.
 *
 * State stays with the caller — this only reports edits, so the same pad drives the cash
 * field, a top-up and a manual amount without knowing about any of them.
 */
@Composable
fun NumericKeypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
    onDecimalSeparator: (() -> Unit)? = null
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9")
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                row.forEach { digit ->
                    KeypadKey(
                        modifier = Modifier.weight(1f),
                        onClick = { onDigit(digit.first()) }
                    ) {
                        Text(digit, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            KeypadKey(
                modifier = Modifier.weight(1f),
                enabled = onDecimalSeparator != null,
                onClick = { onDecimalSeparator?.invoke() }
            ) {
                Text(",", style = MaterialTheme.typography.headlineSmall)
            }
            KeypadKey(
                modifier = Modifier.weight(1f),
                onClick = { onDigit('0') }
            ) {
                Text("0", style = MaterialTheme.typography.headlineSmall)
            }
            KeypadKey(
                modifier = Modifier.weight(1f),
                onClick = onBackspace,
                contentDescription = "Löschen"
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = null
                )
            }
        }
    }
}

@Composable
private fun KeypadKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
    content: @Composable () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        modifier = modifier
            .heightIn(min = TouchTarget.sales)
            .then(
                if (contentDescription != null) {
                    Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
                } else Modifier
            ),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * Applies a keypad edit to an amount string, keeping it a valid partial number.
 *
 * Kept next to the pad so every screen that uses it agrees on the rules: one separator,
 * at most two decimals, no runaway leading zeros.
 */
object AmountInput {

    fun digit(current: String, c: Char): String {
        val decimals = current.substringAfter(',', "")
        if (current.contains(',') && decimals.length >= 2) return current
        if (current == "0") return c.toString()
        return current + c
    }

    fun separator(current: String): String = when {
        current.contains(',') -> current
        current.isEmpty() -> "0,"
        else -> "$current,"
    }

    fun backspace(current: String): String = current.dropLast(1)
}
