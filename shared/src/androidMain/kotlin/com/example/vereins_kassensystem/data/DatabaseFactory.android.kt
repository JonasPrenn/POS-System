package com.example.vereins_kassensystem.data

import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.vereins_kassensystem.platform.AndroidContextHolder

// Kein actual fuer AppDatabaseConstructor an dieser Stelle: Room erzeugt das
// `actual object` je Ziel selbst, und eine handgeschriebene Fassung daneben lehnt der
// Prozessor ab ("The @ConstructedBy definition must be an 'expect' declaration").

actual fun databaseBuilder(): RoomDatabase.Builder<AppDatabase> {
    val context = AndroidContextHolder.application
    return Room.databaseBuilder(
        context = context,
        name = context.getDatabasePath(AppDatabase.FILE_NAME).absolutePath
    )
}
