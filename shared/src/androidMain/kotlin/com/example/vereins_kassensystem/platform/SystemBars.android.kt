package com.example.vereins_kassensystem.platform

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Die Leisten zeichnet enableEdgeToEdge() transparent; übrig bleibt, dem System zu
 * sagen, wie es seine Symbole färben soll. statusBarColor zu setzen wäre auf diesem
 * Ziel-SDK ohne Wirkung.
 */
@Composable
actual fun SystemBarsEffect(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}
