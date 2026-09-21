package com.example.vereins_kassensystem

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.vereins_kassensystem.data.AppDatabase
import com.example.vereins_kassensystem.platform.BackupScheduler
import com.example.vereins_kassensystem.platform.PaymentProcessor
import com.example.vereins_kassensystem.platform.Platform
import com.example.vereins_kassensystem.platform.PlatformKind
import com.example.vereins_kassensystem.platform.SettingsStore
import com.example.vereins_kassensystem.platform.UnavailablePaymentProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/** Eine Datenbank im Speicher, mit dem echten Treiber — was hier läuft, läuft auch auf dem iPad. */
fun openTestDatabase(): AppDatabase =
    Room.inMemoryDatabaseBuilder<AppDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

class MemorySettings : SettingsStore {
    private val values = mutableMapOf<String, String>()
    override suspend fun getString(key: String): String? = values[key]
    override suspend fun putString(key: String, value: String) { values[key] = value }
    override suspend fun remove(key: String) { values.remove(key) }
    override suspend fun getSecret(key: String): String? = values["secret:$key"]
    override suspend fun putSecret(key: String, value: String) { values["secret:$key"] = value }
}

object NoBackups : BackupScheduler {
    override fun attach(work: suspend () -> Boolean) = Unit
    override suspend fun enableDaily() = Unit
    override suspend fun disable() = Unit
}

/** Wie das iPad ohne SumUp-Brücke: Karte meldet sich ab, Bar und Deckel gehen. */
class TestPlatform(override val description: String = "iOS-Test") : Platform {
    override val kind = PlatformKind.IOS
    override val settings: SettingsStore = MemorySettings()
    override val payments: PaymentProcessor = UnavailablePaymentProcessor
    override val backupScheduler: BackupScheduler = NoBackups
}
