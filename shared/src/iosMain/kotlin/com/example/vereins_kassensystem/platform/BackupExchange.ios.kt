package com.example.vereins_kassensystem.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

actual fun createBackupExchange(destination: suspend () -> String?): BackupExchange =
    IosBackupExchange(destination)

/**
 * Sicherungen in einem Ordner, den der Nutzer über die Dateien-App gewählt hat.
 *
 * Der Verweis ist das Lesezeichen aus [bookmarkOf]; jeder Zugriff löst es auf und
 * öffnet den Sicherheits-Scope nur für die Dauer der Operation. Geschrieben wird
 * direkt über NSFileManager — für einen Ordner in "Auf meinem iPad" oder iCloud Drive
 * reicht das; die Dateikoordination für gleichzeitige Bearbeiter ist für einen
 * Sicherungsordner, in den nur diese App schreibt, nicht nötig.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosBackupExchange(
    private val destination: suspend () -> String?
) : BackupExchange {

    private suspend fun folderUrl(): NSURL? = destination()?.let(::resolveBookmark)

    override suspend fun hasDestination(): Boolean = folderUrl() != null

    override suspend fun destinationLabel(): String? = folderUrl()?.lastPathComponent

    override suspend fun writeBackup(bytes: ByteArray, fileName: String): Boolean = withContext(Dispatchers.IO) {
        val url = folderUrl() ?: return@withContext false
        url.withSecurityScope { folder ->
            val path = folder.URLByAppendingPathComponent(fileName)?.path ?: return@withSecurityScope false
            bytes.toNSData().writeToFile(path, atomically = true)
        }
    }

    override suspend fun listBackups(): List<BackupFile> = withContext(Dispatchers.IO) {
        val url = folderUrl() ?: return@withContext emptyList()
        url.withSecurityScope { folder ->
            val dir = folder.path ?: return@withSecurityScope emptyList()
            val manager = NSFileManager.defaultManager
            val names = manager.contentsOfDirectoryAtPath(dir, error = null)
                ?.filterIsInstance<String>()
                .orEmpty()
            names.map { name ->
                val attributes = manager.attributesOfItemAtPath("$dir/$name", error = null)
                val modified = attributes?.get(NSFileModificationDate) as? NSDate
                BackupFile(name, ((modified?.timeIntervalSince1970 ?: 0.0) * 1000).toLong())
            }
        }
    }

    override suspend fun deleteBackup(name: String): Boolean = withContext(Dispatchers.IO) {
        val url = folderUrl() ?: return@withContext false
        url.withSecurityScope { folder ->
            val dir = folder.path ?: return@withSecurityScope false
            NSFileManager.defaultManager.removeItemAtPath("$dir/$name", error = null)
        }
    }
}
