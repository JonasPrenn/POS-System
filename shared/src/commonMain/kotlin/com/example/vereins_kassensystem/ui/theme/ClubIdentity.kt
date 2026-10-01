package com.example.vereins_kassensystem.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * A club's own colour and name.
 *
 * VereinsDeckel is built for any Verein, so the app cannot ship one club's colours. Ink
 * is the product's colour, the same in every clubhouse; this is the club's, and it is
 * deliberately kept away from anything that carries meaning.
 *
 * ## Why the accent is not `primary`
 *
 * Most German clubs are red/white, blue/white or black/yellow. Material's `primary` is
 * the pay button — so a red club would confirm payments in the same colour the app uses
 * to report a failed one, and a gold club would collide with both Brass (the Deckel) and
 * amber (low stock). The five semantic hues have to stay fixed for the interface to keep
 * telling the truth, so the accent tints chrome only:
 *
 *  - allowed: navigation indicator, club header, member avatars, empty-state marks
 *  - never:   pay button, warnings, errors, balance colours, category coding
 */
@Immutable
data class ClubIdentity(
    val name: String = "",
    val accent: Color = Ink
)

/**
 * Ready-made colours covering what most Vereine actually wear, so a club is one tap from
 * looking like itself and never has to open a colour picker to get a usable result.
 */
val ClubAccentPresets: List<Pair<String, Color>> = listOf(
    "Standard (Tinte)" to Ink,
    "Vereinsgrün" to Color(0xFF2E7D32),
    "Rot" to Color(0xFFC62828),
    "Blau" to Color(0xFF1565C0),
    "Gold" to Color(0xFFF9A825),
    "Schwarz" to Color(0xFF2B2B2B),
    "Bordeaux" to Color(0xFF8E1538),
    "Türkis" to Color(0xFF00838F),
    "Violett" to Color(0xFF6A1B9A),
    "Orange" to Color(0xFFEF6C00),
    // Die Vorgabe bis 1.3.0. Bleibt in der Liste, damit ein Verein, der sie gewählt hat,
    // seine Farbe weiter als Vorschlag sieht und nicht als „eigene“.
    "Tannengrün" to Color(0xFF146B4C)
)

val LocalClubIdentity = staticCompositionLocalOf { ClubIdentity() }

/** The club's colour, and a foreground guaranteed to be readable on it. */
object ClubTheme {
    /**
     * Die Vereinsfarbe, wie sie auf dem aktuellen Grund steht. Wäre sie dort praktisch unsichtbar
     * — Tinte oder Schwarz im dunklen Thema —, nimmt sie die Schriftfarbe des Grunds. So bleibt die
     * Auswahl in der Navigation in beiden Themen sichtbar. Dieselbe Regel steht in der Verwaltung
     * (`VereinSettings.accentSheet`).
     */
    val accent: Color
        @Composable @ReadOnlyComposable get() = visibleAccent(
            LocalClubIdentity.current.accent,
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.onBackground
        )

    val onAccent: Color
        @Composable @ReadOnlyComposable get() = contrastingOn(accent)

    val name: String
        @Composable @ReadOnlyComposable get() = LocalClubIdentity.current.name
}

/**
 * Die Vereinsfarbe, oder [fallback], wenn sie auf [ground] unter 1,5:1 läge — also kaum vom Grund
 * zu unterscheiden. Bewusst nicht 3:1: Gold auf Hell ist schwach, aber erkennbar Gold, und die Farbe
 * gehört dem Verein.
 */
fun visibleAccent(accent: Color, ground: Color, fallback: Color): Color =
    if (contrastRatio(accent, ground) >= 1.5f) accent else fallback

private val OnLight = Color(0xFF141414)
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
