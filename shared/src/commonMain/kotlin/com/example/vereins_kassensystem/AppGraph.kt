package com.example.vereins_kassensystem

import com.example.vereins_kassensystem.data.AppDatabase
import com.example.vereins_kassensystem.data.SettingsRepository
import com.example.vereins_kassensystem.data.buildDatabase
import com.example.vereins_kassensystem.data.repository.AppRepository
import com.example.vereins_kassensystem.data.repository.BackupRepository
import com.example.vereins_kassensystem.platform.Platform
import com.example.vereins_kassensystem.platform.createBackupExchange
import kotlinx.coroutines.flow.first

/**
 * Alles, was die App einmal braucht und dann behält: Datenbank, Repositories und die
 * Verbindung zum Hintergrundplaner. Ein Objekt statt je einer Handvoll lazies in zwei
 * Hosts, damit Android und iOS dieselbe Verdrahtung benutzen und keine Plattform ihre
 * eigene Reihenfolge erfindet.
 *
 * Alles ist lazy: Die Datenbank wird erst geöffnet, wenn der erste Bildschirm sie
 * braucht — nicht schon im Konstruktor, den iOS vor dem Ende des App-Starts durchläuft.
 */
class AppGraph(
    val platform: Platform,
    /**
     * Woher die Datenbank kommt. Im Betrieb die Datei im App-Verzeichnis; ein Test reicht
     * hier eine Datenbank im Speicher herein und bekommt sonst die ganze echte Verdrahtung.
     */
    private val openDatabase: () -> AppDatabase = ::buildDatabase,
) {

    val database: AppDatabase by lazy { openDatabase() }

    val repository: AppRepository by lazy {
        AppRepository(
            database.productDao(),
            database.memberDao(),
            database.transactionDao(),
            database.categoryDao(),
            database.stockEntryDao(),
            database.stockDao(),
            database.deliveryDao()
        )
    }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(platform.settings) }

    val backupRepository: BackupRepository by lazy {
        BackupRepository(
            database = database,
            exchange = createBackupExchange { settingsRepository.backupDestination.first() },
            settings = settingsRepository
        )
    }

    init {
        // Der Planer bekommt seine Arbeit hier und nicht in den Hosts: Wer die Sicherung
        // auslöst, ist Plattformsache; was sie tut, nicht.
        platform.backupScheduler.attach { backupRepository.createBackup() }
    }
}
