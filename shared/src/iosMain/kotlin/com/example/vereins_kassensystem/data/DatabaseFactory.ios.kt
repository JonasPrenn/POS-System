package com.example.vereins_kassensystem.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

@Suppress("KotlinNoActualForExpect", "EXPECT_ACTUAL_MISMATCH")
actual object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase>

/**
 * Die Datei liegt im Documents-Ordner des App-Containers.
 *
 * Nicht in Caches oder tmp: iOS raeumt beides ohne Vorwarnung leer, wenn der Speicher
 * knapp wird. Eine Kassendatenbank, die beim naechsten Start weg sein kann, waere ein
 * denkbar schlechter Ort fuer Deckelstaende.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun databaseBuilder(): RoomDatabase.Builder<AppDatabase> {
    val documents: NSURL = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null
    ) ?: error("Documents-Ordner nicht auffindbar")

    val path = requireNotNull(documents.path) { "Documents-Ordner ohne Pfad" } +
        "/" + AppDatabase.FILE_NAME
    return Room.databaseBuilder<AppDatabase>(name = path)
}
