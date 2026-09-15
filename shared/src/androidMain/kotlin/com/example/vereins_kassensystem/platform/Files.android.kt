package com.example.vereins_kassensystem.platform

import com.example.vereins_kassensystem.data.AppDatabase
import java.io.File

actual fun databaseFilePath(): String =
    AndroidContextHolder.application.getDatabasePath(AppDatabase.FILE_NAME).absolutePath

actual fun fileExists(path: String): Boolean = File(path).exists()

actual fun readFile(path: String): ByteArray? = runCatching { File(path).readBytes() }.getOrNull()

actual fun writeFile(path: String, bytes: ByteArray): Boolean = runCatching {
    File(path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
    true
}.getOrDefault(false)

actual fun deleteFile(path: String): Boolean = File(path).delete()

actual fun moveFile(from: String, to: String): Boolean = File(from).renameTo(File(to))
