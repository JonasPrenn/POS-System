package com.example.vereins_kassensystem.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.example.vereins_kassensystem.platform.SystemBarsEffect

/**
 * How the app picks between light and dark. Persisted in settings so a tablet mounted
 * behind the bar can be pinned to dark for the evening regardless of the system.
 */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/*
 * Wie die Material-Rollen belegt sind:
 *
 *  - primary ist Tinte, nicht Grün. Der Standardknopf, die Auswahl, ein Schalter, der Fokus
 *    eines Feldes — das ist alles keine Geldbewegung. Grün steht dadurch nur noch dort, wo Geld
 *    fließt oder etwas gebucht ist, und das sagt [VereinsColors.money] ausdrücklich.
 *  - secondary ist Messing (der Deckel), tertiary Blau (Karte, Auswertung), error Rot.
 *  - Die Flächen folgen dem Kassen-Standard: Grund ist warmes Hellgrau, darauf liegen weiße
 *    Flächen mit einer Haarlinie. Tonale Pastellflächen gibt es nicht mehr.
 */
private val LightColorScheme = lightColorScheme(
    primary = Ink,
    onPrimary = White,
    // Der schwebende Knopf („Neu“) nimmt primaryContainer: Er ist die eine Hauptaktion einer
    // Verwaltungsseite und steht deshalb in Tinte wie jeder Hauptknopf.
    primaryContainer = Ink,
    onPrimaryContainer = White,
    inversePrimary = Paper,

    secondary = Brass,
    onSecondary = White,
    secondaryContainer = BrassTint,
    onSecondaryContainer = BrassDeep,

    tertiary = Blue,
    onTertiary = White,
    tertiaryContainer = BlueTint,
    onTertiaryContainer = BlueDeep,

    error = Red,
    onError = White,
    errorContainer = RedTint,
    onErrorContainer = RedDeep,

    background = Stone50,
    onBackground = Ink,
    surface = Stone50,
    onSurface = Ink,
    surfaceVariant = Stone100,
    onSurfaceVariant = Stone700,
    surfaceTint = Ink,

    // Low und Lowest sind die weißen Flächen (Kacheln, Karten, Spalten, Dialoge), Container und
    // darüber die Füllungen innerhalb einer weißen Fläche (Felder, Tasten, Zähler).
    surfaceContainerLowest = White,
    surfaceContainerLow = White,
    surfaceContainer = Stone100,
    surfaceContainerHigh = White,
    surfaceContainerHighest = Stone150,
    surfaceBright = White,
    surfaceDim = Stone100,

    outline = Stone500,
    outlineVariant = Stone200,

    inverseSurface = Ink,
    inverseOnSurface = Paper,
    scrim = androidx.compose.ui.graphics.Color.Black
)

private val DarkColorScheme = darkColorScheme(
    primary = Paper,
    onPrimary = Ink,
    primaryContainer = Paper,
    onPrimaryContainer = Ink,
    inversePrimary = Ink,

    secondary = BrassNight,
    onSecondary = BrassNightInk,
    secondaryContainer = BrassNightTint,
    onSecondaryContainer = BrassNightSoft,

    tertiary = BlueNight,
    onTertiary = BlueNightInk,
    tertiaryContainer = BlueNightTint,
    onTertiaryContainer = BlueNightSoft,

    error = RedNight,
    onError = RedNightInk,
    errorContainer = RedNightTint,
    onErrorContainer = RedNightSoft,

    background = Night950,
    onBackground = Paper,
    surface = Night950,
    onSurface = Paper,
    surfaceVariant = Night850,
    onSurfaceVariant = Night300,
    surfaceTint = Paper,

    surfaceContainerLowest = Night900,
    surfaceContainerLow = Night900,
    surfaceContainer = Night850,
    surfaceContainerHigh = Night900,
    surfaceContainerHighest = Night800,
    surfaceBright = Night800,
    surfaceDim = Night950,

    outline = Night500,
    outlineVariant = Night700,

    inverseSurface = Paper,
    inverseOnSurface = Ink,
    scrim = androidx.compose.ui.graphics.Color.Black
)

/**
 * Dynamic colour is deliberately not offered. A till is a shared appliance whose colours
 * carry meaning — green is money, ember is attention — and letting the device wallpaper
 * repaint those would break the one rule the design leans on hardest.
 */
@Composable
fun VereinsDeckelTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    clubIdentity: ClubIdentity = ClubIdentity(),
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    // Die Leisten selbst zeichnet das System transparent; hier wird ihm nur gesagt, in
    // welche Richtung es seine Symbole färben soll. Wie, weiß die Plattform.
    SystemBarsEffect(darkTheme)

    CompositionLocalProvider(
        LocalExtendedColors provides extendedColors,
        LocalClubIdentity provides clubIdentity
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = Shapes,
            content = content
        )
    }
}
