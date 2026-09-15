package com.example.vereins_kassensystem.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Calendar
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import com.example.vereins_kassensystem.platform.nowMillis

class BackupRepository(private val context: Context) {

    private val dbName = "vereins_kassensystem_db"
    private val prefsName = "secure_settings.xml"

    suspend fun createBackup(outputUri: Uri? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val timestamp = nowMillis()
            val backupFileName = "VereinsDeckel_Backup_$timestamp.zip"
            
            val tempFile = File(context.cacheDir, backupFileName)
            
            ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
                // 1. Backup Database
                val dbFile = context.getDatabasePath(dbName)
                if (dbFile.exists()) {
                    addToZip(zos, dbFile, dbName)
                    // Also backup -wal and -shm files if they exist
                    val walFile = File("${dbFile.path}-wal")
                    if (walFile.exists()) addToZip(zos, walFile, "$dbName-wal")
                    val shmFile = File("${dbFile.path}-shm")
                    if (shmFile.exists()) addToZip(zos, shmFile, "$dbName-shm")
                }

                // 2. Backup Shared Preferences
                val prefsFile = File(context.filesDir.parent, "shared_prefs/$prefsName")
                if (prefsFile.exists()) {
                    addToZip(zos, prefsFile, "prefs_$prefsName")
                }
            }

            if (outputUri != null) {
                // For manual or auto-export to a chosen directory
                val folder = DocumentFile.fromTreeUri(context, outputUri)
                val file = folder?.createFile("application/zip", backupFileName)
                file?.uri?.let { uri ->
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        FileInputStream(tempFile).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                }
            } else {
                // Handle as a simple file return or specific internal storage if needed
            }
            
            tempFile.delete()
            true
        } catch (e: Exception) {
            Log.e("BackupRepository", "Error creating backup", e)
            false
        }
    }

    private fun addToZip(zos: ZipOutputStream, file: File, entryName: String) {
        val entry = ZipEntry(entryName)
        zos.putNextEntry(entry)
        FileInputStream(file).use { it.copyTo(zos) }
        zos.closeEntry()
    }

    suspend fun restoreBackup(inputUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(inputUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        when (entry.name) {
                            dbName -> {
                                val dbFile = context.getDatabasePath(dbName)
                                FileOutputStream(dbFile).use { zis.copyTo(it) }
                            }
                            "$dbName-wal" -> {
                                val walFile = File("${context.getDatabasePath(dbName).path}-wal")
                                FileOutputStream(walFile).use { zis.copyTo(it) }
                            }
                            "$dbName-shm" -> {
                                val shmFile = File("${context.getDatabasePath(dbName).path}-shm")
                                FileOutputStream(shmFile).use { zis.copyTo(it) }
                            }
                            "prefs_$prefsName" -> {
                                val prefsFile = File(context.filesDir.parent, "shared_prefs/$prefsName")
                                FileOutputStream(prefsFile).use { zis.copyTo(it) }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e("BackupRepository", "Error restoring backup", e)
            false
        }
    }

    suspend fun cleanupOldBackups(directoryUri: Uri) = withContext(Dispatchers.IO) {
        try {
            val folder = DocumentFile.fromTreeUri(context, directoryUri)
            val files = folder?.listFiles() ?: return@withContext
            
            val oneWeekAgo = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -7)
            }.timeInMillis

            files.forEach { file ->
                if ((file.name?.startsWith("VereinsDeckel_Backup_") == true) && (file.lastModified() < oneWeekAgo)) {
                    file.delete()
                    Log.d("BackupRepository", "Deleted old backup: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e("BackupRepository", "Error cleaning up backups", e)
        }
    }
}
