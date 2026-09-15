package com.example.vereins_kassensystem.data

import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.vereins_kassensystem.platform.documentsDirectory

// Kein actual fuer AppDatabaseConstructor an dieser Stelle: Room erzeugt das
// `actual object` je Ziel selbst — siehe DatabaseFactory.android.kt.

/**
 * Die Datei liegt im Documents-Ordner des App-Containers, siehe
 * [documentsDirectory] für den Grund.
 */
actual fun databaseBuilder(): RoomDatabase.Builder<AppDatabase> =
    Room.databaseBuilder<AppDatabase>(name = documentsDirectory() + "/" + AppDatabase.FILE_NAME)
