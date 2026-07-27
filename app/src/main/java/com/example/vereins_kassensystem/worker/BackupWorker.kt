package com.example.vereins_kassensystem.worker

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.vereins_kassensystem.data.SettingsRepository
import com.example.vereins_kassensystem.data.repository.BackupRepository
import kotlinx.coroutines.flow.first

class BackupWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val settingsRepository = SettingsRepository(applicationContext)
        val backupRepository = BackupRepository(applicationContext)
        
        val uriString = settingsRepository.backupUri.first()
        val isAutoBackupEnabled = settingsRepository.autoBackupEnabled.first()

        if (!isAutoBackupEnabled || uriString == null) {
            return Result.success()
        }

        return try {
            val uri = Uri.parse(uriString)
            val success = backupRepository.createBackup(uri)
            if (success) {
                backupRepository.cleanupOldBackups(uri)
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Result.failure()
        }
    }
}
