package com.example.vereins_kassensystem.platform

import com.example.vereins_kassensystem.data.AppDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

actual fun databaseFilePath(): String = documentsDirectory() + "/" + AppDatabase.FILE_NAME

actual fun fileExists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

actual fun readFile(path: String): ByteArray? = NSData.dataWithContentsOfFile(path)?.toByteArray()

actual fun writeFile(path: String, bytes: ByteArray): Boolean =
    bytes.toNSData().writeToFile(path, atomically = true)

@OptIn(ExperimentalForeignApi::class)
actual fun deleteFile(path: String): Boolean =
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)

@OptIn(ExperimentalForeignApi::class)
actual fun moveFile(from: String, to: String): Boolean =
    NSFileManager.defaultManager.moveItemAtPath(from, toPath = to, error = null)
