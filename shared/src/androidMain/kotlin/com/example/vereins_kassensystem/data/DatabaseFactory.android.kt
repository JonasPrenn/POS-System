package com.example.vereins_kassensystem.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import com.example.vereins_kassensystem.platform.AndroidContextHolder

/**
 * Der Room-Prozessor fuellt diese Implementierung selbst aus; der Rumpf bleibt leer.
 */
@Suppress("KotlinNoActualForExpect", "EXPECT_ACTUAL_MISMATCH")
actual object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase>

actual fun databaseBuilder(): RoomDatabase.Builder<AppDatabase> {
    val context = AndroidContextHolder.application
    return Room.databaseBuilder(
        context = context,
        name = context.getDatabasePath(AppDatabase.FILE_NAME).absolutePath
    )
}
