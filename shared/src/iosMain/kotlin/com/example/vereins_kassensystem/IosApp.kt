package com.example.vereins_kassensystem

import androidx.compose.ui.window.ComposeUIViewController
import com.example.vereins_kassensystem.platform.IosBackupScheduler
import com.example.vereins_kassensystem.platform.IosPlatform
import com.example.vereins_kassensystem.platform.IosSumUpPaymentProcessor
import com.example.vereins_kassensystem.platform.Platform
import com.example.vereins_kassensystem.platform.SecretStore
import com.example.vereins_kassensystem.platform.SumUpBridge
import com.example.vereins_kassensystem.platform.UnavailablePaymentProcessor
import com.example.vereins_kassensystem.ui.VereinsDeckelApp
import platform.UIKit.UIViewController

/**
 * Der Einstieg für Swift.
 *
 * `iOSApp.swift` baut genau ein Exemplar in `init`, also vor dem Ende des App-Starts —
 * das braucht der Hintergrundplaner für seine Registrierung. Swift reicht herein, was
 * nur Swift gut kann: den Schlüsselbund und, sobald das SDK eingebunden ist, die
 * SumUp-Brücke. Ohne Brücke läuft die Kasse mit [UnavailablePaymentProcessor]: Bar und
 * Deckel funktionieren, Karte meldet sich sauber ab.
 */
class IosApp(secrets: SecretStore, sumUp: SumUpBridge?) {

    val platform: Platform = IosPlatform(
        secrets = secrets,
        payments = sumUp?.let { IosSumUpPaymentProcessor(it) } ?: UnavailablePaymentProcessor,
        backupScheduler = IosBackupScheduler()
    )

    val graph: AppGraph = AppGraph(platform)

    /** Der View-Controller, den `ContentView.swift` in die Szene hängt. */
    fun viewController(): UIViewController = ComposeUIViewController { VereinsDeckelApp(graph) }
}
