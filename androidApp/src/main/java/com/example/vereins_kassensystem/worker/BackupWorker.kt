package com.example.vereins_kassensystem.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.vereins_kassensystem.KassenApplication
import kotlinx.coroutines.flow.first

/**
 * Die tägliche Sicherung, von WorkManager angestoßen (siehe AndroidBackupScheduler).
 * Holt sich Repository und Einstellungen über den AppGraph der Application, statt
 * eigene Instanzen zu bauen — sonst gäbe es zwei Datenbankverbindungen auf dieselbe
 * Datei.
 */
class BackupWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as KassenApplication).graph
        if (!graph.settingsRepository.autoBackupEnabled.first()) return Result.success()
        return if (graph.backupRepository.createBackup()) Result.success() else Result.retry()
    }
}
