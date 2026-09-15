package com.example.vereins_kassensystem.data

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
// Dispatchers.IO ist auf Kotlin/Native eine Erweiterung, kein Member; ohne diesen
// Import sieht der Compiler dort nur die interne Fassung.
import kotlinx.coroutines.IO

/**
 * Wo die Datenbankdatei liegt, weiß nur die Plattform.
 *
 * Unter Android ist das der App-eigene Datenordner, unter iOS der Documents-Ordner im
 * Container. Alles andere am Bau — Treiber, Wanderungen, auf welchem Thread geschrieben
 * wird — steht in [build] und gilt für beide, damit die zwei Fassungen nicht unbemerkt
 * auseinanderlaufen.
 */
expect fun databaseBuilder(): RoomDatabase.Builder<AppDatabase>

/**
 * Baut die Datenbank fertig.
 *
 * [BundledSQLiteDriver] statt des vom System mitgelieferten SQLite: Android und iOS
 * bringen unterschiedliche Versionen mit, und bei einer Kasse, deren beide Geräte
 * dieselben Daten führen sollen, ist "auf dem einen Gerät verhält sich SQLite anders"
 * genau die Art Fehler, die man nie findet. Der gebündelte Treiber ist auf beiden
 * Plattformen derselbe.
 */
fun buildDatabase(): AppDatabase =
    databaseBuilder()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(*AppDatabase.MIGRATIONS)
        // Rückfallebene nur für Entwicklungsstände vor Version 7; 7 -> 8 -> 9 -> 10
        // haben echte Wege und werfen niemandem seinen Deckel weg.
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
