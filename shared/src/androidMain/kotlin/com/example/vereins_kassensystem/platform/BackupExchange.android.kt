package com.example.vereins_kassensystem.platform

import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun createBackupExchange(destination: suspend () -> String?): BackupExchange =
    AndroidBackupExchange(destination)

/**
 * Sicherungen in einem Dokumentbaum, den der Nutzer über das Storage Access Framework
 * gewählt hat. Die Berechtigung darauf hält [rememberBackupDestinationPicker] dauerhaft
 * fest; hier wird der Baum nur noch geöffnet.
 */
private class AndroidBackupExchange(
    private val destination: suspend () -> String?
) : BackupExchange {

    private val context get() = AndroidContextHolder.application

    private suspend fun folder(): DocumentFile? {
        val ref = destination() ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, Uri.parse(ref)) }
            .getOrNull()
            ?.takeIf { it.canWrite() }
    }

    override suspend fun hasDestination(): Boolean = withContext(Dispatchers.IO) { folder() != null }

    override suspend fun destinationLabel(): String? = withContext(Dispatchers.IO) {
        val ref = destination() ?: return@withContext null
        // Die Baum-ID lautet etwa "primary:VereinsDeckel/Sicherungen"; der Teil nach dem
        // Doppelpunkt ist das, was der Nutzer im Dateimanager sieht.
        val id = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(ref)) }.getOrNull()
            ?: return@withContext ref
        id.substringAfter(':', id)
    }

    override suspend fun writeBackup(bytes: ByteArray, fileName: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val file = folder()?.createFile("application/vnd.sqlite3", fileName) ?: return@runCatching false
            context.contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) } != null
        }.getOrDefault(false)
    }

    override suspend fun listBackups(): List<BackupFile> = withContext(Dispatchers.IO) {
        folder()?.listFiles().orEmpty().mapNotNull { file ->
            file.name?.let { BackupFile(it, file.lastModified()) }
        }
    }

    override suspend fun deleteBackup(name: String): Boolean = withContext(Dispatchers.IO) {
        folder()?.findFile(name)?.delete() ?: false
    }
}
