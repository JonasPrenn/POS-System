package com.example.vereins_kassensystem.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * A club's own colour and name.
 *
 * VereinsDeckel is built for any Verein, so the app cannot ship one club's colours. Pine
 * is the product's colour, the same in every clubhouse; this is the club's, and it is
 * deliberately kept away from anything that carries meaning.
 *
 * ## Why the accent is not `primary`
 *
 * Most German clubs are red/white, blue/white or black/yellow. Material's `primary` is
 * the pay button — so a red club would confirm payments in the same colour the app uses
 * to report a failed one, and a gold club would collide with both Brass (the Deckel) and
 * Ember (low stock). The five semantic hues have to stay fixed for the interface to keep
 * telling the truth, so the accent tints chrome only:
 *
 *  - allowed: navigation indicator, club header, member avatars, empty-state marks
 *  - never:   pay button, warnings, errors, balance colours, category coding
 */
@Immutable
data class ClubIdentity(
    val name: String = "",
    val accent: Color = Pine40
)

/**
 * Ready-made colours covering what most Vereine actually wear, so a club is one tap from
 * looking like itself and never has to open a colour picker to get a usable result.
 */
val ClubAccentPresets: List<Pair<String, Color>> = listOf(
    "Vereinsgrün" to Color(0xFF2E7D32),
    "Rot" to Color(0xFFC62828),
    "Blau" to Color(0xFF1565C0),
    "Gold" to Color(0xFFF9A825),
    "Schwarz" to Color(0xFF2B2B2B),
    "Bordeaux" to Color(0xFF8E1538),
    "Türkis" to Color(0xFF00838F),
    "Violett" to Color(0xFF6A1B9A),
    "Orange" to Color(0xFFEF6C00),
    "Standard (Pine)" to Pine40
)

val LocalClubIdentity = staticCompositionLocalOf { ClubIdentity() }

/** The club's colour, and a foreground guaranteed to be readable on it. */
object ClubTheme {
    val accent: Color
        @Composable @ReadOnlyComposable get() = LocalClubIdentity.current.accent

    val onAccent: Color
        @Composable @ReadOnlyComposable get() = contrastingOn(LocalClubIdentity.current.accent)

    val name: String
        @Composable @ReadOnlyComposable get() = LocalClubIdentity.current.name
}

private val OnLight = Color(0xFF16190F)
private val OnDark = Color(0xFFFFFFFF)

/**
 * Picks white or near-black for text on [background], whichever actually reads better.
 *
 * A club can choose any colour, including a mid-tone where neither foreground is
 * obviously right. Measuring both and taking the better one means the club's choice is
 * respected without ever producing unreadable text.
 */
fun contrastingOn(background: Color): Color =
    if (contrastRatio(OnDark, background) >= contrastRatio(OnLight, background)) OnDark else OnLight

/** WCAG 2.x contrast ratio, 1.0 (identical) to 21.0 (black on white). */
fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + 0.05f) / (lo + 0.05f)
}

/**
 * Whether [accent] can carry readable text at all. Every preset passes; a custom colour
 * that fails is worth telling the user about rather than silently shipping grey-on-grey.
 */
fun isAccentReadable(accent: Color): Boolean =
    contrastRatio(contrastingOn(accent), accent) >= 4.5f
