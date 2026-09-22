package com.example.vereins_kassensystem.data

import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.vereins_kassensystem.platform.AndroidContextHolder
import com.example.vereins_kassensystem.platform.databaseFilePath

// Kein actual fuer AppDatabaseConstructor an dieser Stelle: Room erzeugt das
// `actual object` je Ziel selbst, und eine handgeschriebene Fassung daneben lehnt der
// Prozessor ab ("The @ConstructedBy definition must be an 'expect' declaration").

actual fun databaseBuilder(): RoomDatabase.Builder<AppDatabase> {
    return Room.databaseBuilder(
        context = AndroidContextHolder.application,
        name = databaseFilePath()
    )
}
