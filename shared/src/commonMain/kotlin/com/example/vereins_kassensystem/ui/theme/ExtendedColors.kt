package com.example.vereins_kassensystem.ui.theme

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Material 3 has no `warning` role, which is why the previous theme reached for
 * `errorContainer` to flag low stock. Running low on Radler is not a failure, so
 * Bernstein is added here as an extended set — that keeps red (error) reserved for
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
    val onWarningContainer: Color,
    /** Grün: Geld und Bestätigung. Seit `primary` Tinte ist, steht Geld nur noch hier. */
    val money: Color,
    val onMoney: Color,
    val moneyContainer: Color,
    val onMoneyContainer: Color,
    /** Die Haarlinie um weiße Flächen. Reine Trennung, trägt keine Bedeutung. */
    val hairline: Color
)

val LightExtendedColors = ExtendedColors(
    warning = Amber,
    onWarning = White,
    warningContainer = AmberTint,
    onWarningContainer = AmberDeep,
    money = Green,
    onMoney = White,
    moneyContainer = GreenTint,
    onMoneyContainer = GreenDeep,
    hairline = Stone200
)

val DarkExtendedColors = ExtendedColors(
    warning = AmberNight,
    onWarning = AmberNightInk,
    warningContainer = AmberNightTint,
    onWarningContainer = AmberNightSoft,
    money = GreenNight,
    onMoney = GreenNightInk,
    moneyContainer = GreenNightTint,
    onMoneyContainer = GreenNightSoft,
    hairline = Night700
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
    val money: Color
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current.money
    val onMoney: Color
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current.onMoney
    val moneyContainer: Color
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current.moneyContainer
    val onMoneyContainer: Color
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current.onMoneyContainer
    val hairline: Color
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current.hairline
}

/**
 * Knopffarben für alles, was Geld bewegt: Bezahlen, Kasse öffnen und schließen, Bargeld buchen.
 * Jeder andere Knopf bleibt in Tinte — so heißt Grün an der Theke immer „hier fließt Geld“.
 */
@Composable
fun moneyButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = VereinsColors.money,
    contentColor = VereinsColors.onMoney
)

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
    // Null ist kein Geld: Ein leerer Deckel steht neutral, nicht grün.
    balance == 0.0 -> MaterialTheme.colorScheme.onSurface
    else -> LocalExtendedColors.current.money
}
