package com.example.vereins_kassensystem.platform

import platform.Foundation.NSUserDefaults
import platform.UIKit.UIDevice

/**
 * Der Schlüsselbund, von Swift aus bedient.
 *
 * Die Keychain-Schnittstelle ist C mit CoreFoundation-Typen. Von Kotlin/Native aus
 * bedeutet das `memScoped`, `CFDictionaryRef` und Ausgabezeiger — machbar, aber genau die
 * Sorte Code, die stillschweigend das Falsche tut und deren Fehler erst auffallen, wenn
 * ein Kassier sich nicht mehr bei SumUp anmelden kann. In Swift sind dieselben Aufrufe
 * ein Dutzend gut ausgetretener Zeilen.
 *
 * Die Umsetzung liegt daher in `iosApp/Keychain.swift` und wird beim Start
 * hereingereicht. Das geteilte Modul kennt nur diesen Vertrag.
 */
interface SecretStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

/**
 * Gewöhnliche Einstellungen in NSUserDefaults, Zugangsdaten im Schlüsselbund.
 *
 * Dieselbe Trennung wie unter Android und aus demselben Grund: NSUserDefaults landet in
 * der Gerätesicherung, der Schlüsselbund mit `AfterFirstUnlock` nicht in einer
 * unverschlüsselten. Der SumUp-Key und das Gerätetoken gehören in die zweite Kategorie.
 */
class IosSettingsStore(private val secrets: SecretStore) : SettingsStore {

    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun getString(key: String): String? = defaults.stringForKey(key)

    override suspend fun putString(key: String, value: String) {
        defaults.setObject(value, key)
    }

    override suspend fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }

    override suspend fun getSecret(key: String): String? = secrets.read(key)

    override suspend fun putSecret(key: String, value: String) = secrets.write(key, value)
}

class IosPlatform(
    secrets: SecretStore,
    override val payments: PaymentProcessor,
    override val backupScheduler: BackupScheduler
) : Platform {

    override val description: String
        get() = with(UIDevice.currentDevice) { "$systemName $systemVersion ($model)" }

    override val kind: PlatformKind = PlatformKind.IOS

    override val settings: SettingsStore = IosSettingsStore(secrets)
}
