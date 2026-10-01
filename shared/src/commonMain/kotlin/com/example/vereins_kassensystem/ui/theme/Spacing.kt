package com.example.vereins_kassensystem.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The six-step spacing scale. All padding and gaps come from here.
 *
 * Plain object rather than a CompositionLocal: spacing never varies by theme, and a
 * local would only add ceremony at every call site.
 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Platz unter einer Liste, damit der letzte Eintrag nicht unter den schwebenden Knöpfen liegt. */
    val fabClearance = 160.dp
}

/**
 * Minimum touch targets.
 *
 * [min] is the Android accessibility floor. [sales] is the larger floor used on the
 * selling path — product tiles, the pay button, keypad keys — because that path is
 * operated one-handed, at speed, sometimes with wet fingers.
 */
object TouchTarget {
    val min = 48.dp
    val sales = 56.dp
}

/**
 * Linienstärken. [hairline] umrandet jede weiße Fläche — der Kassen-Standard trennt mit Linien,
 * nicht mit Schatten. [selected] markiert, was gewählt ist (Zählfeld, Zahlungsart).
 */
object Stroke {
    val hairline = 1.dp
    val selected = 2.dp
}
