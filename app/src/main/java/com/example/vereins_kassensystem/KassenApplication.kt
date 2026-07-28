package com.example.vereins_kassensystem

import android.app.Application
import com.example.vereins_kassensystem.data.AppDatabase
import com.example.vereins_kassensystem.data.repository.AppRepository
import com.example.vereins_kassensystem.data.repository.BackupRepository

import com.sumup.reader.sdk.api.SumUpState
import com.example.vereins_kassensystem.data.SettingsRepository

class KassenApplication : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy {
        AppRepository(
            database.productDao(),
            database.memberDao(),
            database.transactionDao(),
            database.categoryDao(),
            database.stockEntryDao()
        )
    }
    val settingsRepository by lazy { SettingsRepository(this) }
    val backupRepository by lazy { BackupRepository(this) }

    override fun onCreate() {
        super.onCreate()
        SumUpState.init(this)
    }
}
