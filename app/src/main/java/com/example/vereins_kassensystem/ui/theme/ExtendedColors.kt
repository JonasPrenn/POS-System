package com.example.vereins_kassensystem.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Material 3 has no `warning` role, which is why the previous theme reached for
 * `errorContainer` to flag low stock. Running low on Radler is not a failure, so
 * Ember is added here as an extended set — that keeps Signal (error) reserved for
 * things that were genuinely lost or refused.
 *
 * Reach for it via [VereinsColors], which reads like the Material roles do:
 *
 *     Surface(color = VereinsColors.warningContainer) { ... }
 */
@Immutable
data class ExtendedColors(
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color
)

val LightExtendedColors = ExtendedColors(
    warning = Ember40,
    onWarning = White,
    warningContainer = Ember90,
    onWarningContainer = Ember10
)

val DarkExtendedColors = ExtendedColors(
    warning = Ember80,
    onWarning = Ember20,
    warningContainer = Ember30,
    onWarningContainer = Ember90
)

val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

/** Companion to [MaterialTheme.colorScheme] for roles Material does not define. */
object VereinsColors {
    val warning: Color
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current.warning
    val onWarning: Color
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current.onWarning
    val warningContainer: Color
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current.warningContainer
    val onWarningContainer: Color
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current.onWarningContainer
}

/**
 * Colour for a member balance, by health rather than by raw sign.
 *
 * @param balance the member's current balance
 * @param negativeLimit how far the member's category allows them to go under, as a
 *   negative number (e.g. -20.0). Zero means no credit is extended.
 */
@Composable
@ReadOnlyComposable
fun balanceColor(balance: Double, negativeLimit: Double): Color = when {
    balance < negativeLimit -> MaterialTheme.colorScheme.error
    // Within the last quarter of the allowance, or already negative with no allowance.
    balance < 0.0 && (negativeLimit == 0.0 || balance <= negativeLimit * 0.75) ->
        LocalExtendedColors.current.warning
    balance < 0.0 -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.primary
}
