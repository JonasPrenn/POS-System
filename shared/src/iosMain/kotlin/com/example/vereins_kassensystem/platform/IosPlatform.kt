package com.example.vereins_kassensystem.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.dateWithTimeIntervalSinceNow
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

/**
 * Sicherung im Hintergrund über BGTaskScheduler.
 *
 * iOS entscheidet selbst, wann eine Verarbeitungsaufgabe läuft — meist nachts am
 * Ladegerät, und nur, wenn die App regelmäßig benutzt wird. "Täglich" ist deshalb eine
 * Bitte, keine Zusage; die Oberfläche zeigt stattdessen, wann zuletzt gesichert wurde.
 *
 * Die Kennung muss in der Info.plist unter BGTaskSchedulerPermittedIdentifiers stehen,
 * und die Registrierung muss vor dem Ende des App-Starts passieren. Deshalb liegt sie im
 * Konstruktor, und das Platform-Objekt wird in iOSApp.init gebaut, nicht erst beim
 * ersten Bildschirm.
 */
class IosBackupScheduler(
    private val identifier: String = TASK_IDENTIFIER
) : BackupScheduler {

    private var work: (suspend () -> Boolean)? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
            identifier = identifier,
            usingQueue = null
        ) { task -> handle(task) }
    }

    override fun attach(work: suspend () -> Boolean) {
        this.work = work
    }

    private fun handle(task: BGTask?) {
        if (task == null) return
        val job = scope.launch {
            val ok = runCatching { work?.invoke() ?: false }.getOrDefault(false)
            task.setTaskCompletedWithSuccess(ok)
            // Jede Runde meldet sich für die nächste an; ohne das liefe die Sicherung
            // genau einmal.
            submit()
        }
        task.expirationHandler = {
            job.cancel()
            task.setTaskCompletedWithSuccess(false)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun submit() {
        val request = BGProcessingTaskRequest(identifier = identifier).apply {
            earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(24.0 * 60 * 60)
            requiresNetworkConnectivity = false
            requiresExternalPower = false
        }
        BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error = null)
    }

    override suspend fun enableDaily() = submit()

    override suspend fun disable() {
        BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(identifier)
    }

    companion object {
        const val TASK_IDENTIFIER = "com.example.vereins_kassensystem.backup"
    }
}
