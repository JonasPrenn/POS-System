package com.example.vereins_kassensystem.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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

private val LightColorScheme = lightColorScheme(
    primary = Pine40,
    onPrimary = White,
    primaryContainer = Pine90,
    onPrimaryContainer = Pine10,
    inversePrimary = Pine80,

    secondary = Brass40,
    onSecondary = White,
    secondaryContainer = Brass90,
    onSecondaryContainer = Brass10,

    tertiary = Harbor40,
    onTertiary = White,
    tertiaryContainer = Harbor90,
    onTertiaryContainer = Harbor10,

    error = Signal40,
    onError = White,
    errorContainer = Signal90,
    onErrorContainer = Signal10,

    background = NeutralLight98,
    onBackground = NeutralInk,
    surface = NeutralLight98,
    onSurface = NeutralInk,
    surfaceVariant = NeutralVariant80,
    onSurfaceVariant = NeutralVariant30,
    surfaceTint = Pine40,

    surfaceContainerLowest = White,
    surfaceContainerLow = NeutralLight97,
    surfaceContainer = NeutralLight96,
    surfaceContainerHigh = NeutralLight94,
    surfaceContainerHighest = NeutralLight92,
    surfaceBright = NeutralLight98,
    surfaceDim = NeutralLight92,

    outline = NeutralVariant50,
    outlineVariant = NeutralVariant80,

    inverseSurface = InverseSurfaceLight,
    inverseOnSurface = InverseOnSurfaceLight,
    scrim = androidx.compose.ui.graphics.Color.Black
)

private val DarkColorScheme = darkColorScheme(
    primary = Pine80,
    onPrimary = PineOnDark,
    primaryContainer = Pine30,
    onPrimaryContainer = Pine90,
    inversePrimary = Pine40,

    secondary = Brass80,
    onSecondary = Brass20,
    secondaryContainer = Brass30,
    onSecondaryContainer = Brass90,

    tertiary = Harbor80,
    onTertiary = Harbor20,
    tertiaryContainer = Harbor30,
    onTertiaryContainer = Harbor90,

    error = Signal80,
    onError = Signal20,
    errorContainer = Signal30,
    onErrorContainer = Signal90,

    background = NeutralDark06,
    onBackground = NeutralInkDark,
    surface = NeutralDark06,
    onSurface = NeutralInkDark,
    surfaceVariant = NeutralVariant30,
    onSurfaceVariant = NeutralVariant80,
    surfaceTint = Pine80,

    surfaceContainerLowest = NeutralDark04,
    surfaceContainerLow = NeutralDark10,
    surfaceContainer = NeutralDark12,
    surfaceContainerHigh = NeutralDark17,
    surfaceContainerHighest = NeutralDark22,
    surfaceBright = NeutralDark22,
    surfaceDim = NeutralDark06,

    outline = NeutralVariant60,
    outlineVariant = NeutralVariant30,

    inverseSurface = NeutralInkDark,
    inverseOnSurface = InverseSurfaceLight,
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

    // The bars themselves are drawn transparent by enableEdgeToEdge(); all that is left
    // is telling the system which way to tint its icons. Setting statusBarColor here
    // would be a no-op at this target SDK.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

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
