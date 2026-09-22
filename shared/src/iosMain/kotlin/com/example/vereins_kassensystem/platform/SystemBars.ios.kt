package com.example.vereins_kassensystem.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import platform.UIKit.UIApplication
import platform.UIKit.UIStatusBarStyleDarkContent
import platform.UIKit.UIStatusBarStyleLightContent
import platform.UIKit.setStatusBarStyle

/**
 * Setzt die Statuszeile über UIApplication.
 *
 * Der Weg über preferredStatusBarStyle wäre der modernere, verlangt aber eine
 * View-Controller-Unterklasse, die Compose nicht hergibt. Der Aufruf hier ist als
 * veraltet markiert und funktioniert weiterhin, solange in der Info.plist
 * UIViewControllerBasedStatusBarAppearance auf NO steht — das setzt iosApp so.
 */
@Composable
actual fun SystemBarsEffect(darkTheme: Boolean) {
    SideEffect {
        @Suppress("DEPRECATION")
        UIApplication.sharedApplication.setStatusBarStyle(
            if (darkTheme) UIStatusBarStyleLightContent else UIStatusBarStyleDarkContent,
            animated = true
        )
    }
}
