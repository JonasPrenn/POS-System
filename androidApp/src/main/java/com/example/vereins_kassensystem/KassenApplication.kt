package com.example.vereins_kassensystem

import android.app.Application
import com.example.vereins_kassensystem.data.AppDatabase
import com.example.vereins_kassensystem.data.repository.AppRepository
import com.example.vereins_kassensystem.data.repository.BackupRepository

import com.sumup.reader.sdk.api.SumUpState
import com.example.vereins_kassensystem.data.SettingsRepository
import com.example.vereins_kassensystem.platform.AndroidBackupScheduler
import com.example.vereins_kassensystem.platform.AndroidSettingsStore
import com.example.vereins_kassensystem.platform.createBackupExchange
import com.example.vereins_kassensystem.worker.BackupWorker
import kotlinx.coroutines.flow.first

class KassenApplication : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy {
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
    val settingsRepository by lazy { SettingsRepository(AndroidSettingsStore(this)) }
    val backupRepository by lazy {
        BackupRepository(
            database = database,
            exchange = createBackupExchange { settingsRepository.backupDestination.first() },
            settings = settingsRepository
        )
    }
    val backupScheduler by lazy { AndroidBackupScheduler(this, BackupWorker::class.java) }

    override fun onCreate() {
        super.onCreate()
        SumUpState.init(this)
    }
}
