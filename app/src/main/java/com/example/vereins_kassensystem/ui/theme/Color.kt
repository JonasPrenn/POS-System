package com.example.vereins_kassensystem.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * VereinsDeckel palette — "Die ruhige Theke".
 *
 * Five hues, one job each:
 *  - Pine (primary)     brand, primary actions, confirmation, cash, revenue
 *  - Brass (secondary)  the Deckel: member identity, balance, top-ups, tips
 *  - Harbor (tertiary)  card payment and analytics
 *  - Ember (warning)    low stock, balance nearing its limit — see [ExtendedColors]
 *  - Signal (error)     destructive actions and payment failure only
 *
 * Every foreground/background pair is verified against WCAG: all text pairs clear AA,
 * most clear AAA. `outline` on light sits at 4.24:1, which is a non-text border and is
 * therefore judged against the 3:1 UI-component threshold.
 *
 * Every Material role is assigned explicitly in Theme.kt. Anything left unset falls
 * back to the M3 baseline palette, which is violet — that was the source of the
 * mismatched borders and dividers in the previous theme.
 */

// ---------------------------------------------------------------- Pine (primary)
val Pine10 = Color(0xFF00281A)
val Pine20 = Color(0xFF0A3F2D)
val Pine30 = Color(0xFF005237)
val Pine40 = Color(0xFF146B4C)
val Pine80 = Color(0xFF8ED8B6)
val Pine90 = Color(0xFFA5F2CB)
val PineOnDark = Color(0xFF003824)

// -------------------------------------------------------------- Brass (secondary)
val Brass10 = Color(0xFF281800)
val Brass20 = Color(0xFF422C00)
val Brass30 = Color(0xFF5E4100)
val Brass40 = Color(0xFF7A5314)
val Brass80 = Color(0xFFE9C68A)
val Brass90 = Color(0xFFFFDEA8)

// -------------------------------------------------------------- Harbor (tertiary)
val Harbor10 = Color(0xFF001D33)
val Harbor20 = Color(0xFF003354)
val Harbor30 = Color(0xFF0B4A76)
val Harbor40 = Color(0xFF2B5C87)
val Harbor80 = Color(0xFF9BCBFA)
val Harbor90 = Color(0xFFCDE5FF)

// ---------------------------------------------------------------- Ember (warning)
val Ember10 = Color(0xFF331200)
val Ember20 = Color(0xFF542100)
val Ember30 = Color(0xFF773100)
val Ember40 = Color(0xFFA03E00)
val Ember80 = Color(0xFFFFB77C)
val Ember90 = Color(0xFFFFDCC4)

// ----------------------------------------------------------------- Signal (error)
val Signal10 = Color(0xFF410E0B)
val Signal20 = Color(0xFF601410)
val Signal30 = Color(0xFF8C1D18)
val Signal40 = Color(0xFFB3261E)
val Signal80 = Color(0xFFF2B8B5)
val Signal90 = Color(0xFFF9DEDC)

// -------------------------------------------------------------------- Neutrals
// Greys carry a whisper of the pine hue so the app reads as a warm room rather
// than a spreadsheet.
val NeutralDark04 = Color(0xFF090D0A)
val NeutralDark06 = Color(0xFF0E1310)
val NeutralDark10 = Color(0xFF171C18)
val NeutralDark12 = Color(0xFF1B201C)
val NeutralDark17 = Color(0xFF252B26)
val NeutralDark22 = Color(0xFF303631)

val NeutralLight92 = Color(0xFFE1E4DF)
val NeutralLight94 = Color(0xFFE6E9E4)
val NeutralLight96 = Color(0xFFECEFEA)
val NeutralLight97 = Color(0xFFF2F5F0)
val NeutralLight98 = Color(0xFFF7F9F6)

val NeutralInk = Color(0xFF191D1A)
val NeutralInkDark = Color(0xFFE1E4DE)
val NeutralVariant30 = Color(0xFF414942)
val NeutralVariant50 = Color(0xFF717971)
val NeutralVariant60 = Color(0xFF8B938A)
val NeutralVariant80 = Color(0xFFC1C9C0)

val InverseSurfaceLight = Color(0xFF2E322E)
val InverseOnSurfaceLight = Color(0xFFEFF2EC)

val White = Color(0xFFFFFFFF)
