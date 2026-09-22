package com.example.vereins_kassensystem.data.repository

import androidx.room.useWriterConnection
import com.example.vereins_kassensystem.data.AppDatabase
import com.example.vereins_kassensystem.data.SettingsRepository
import com.example.vereins_kassensystem.platform.BackupExchange
import com.example.vereins_kassensystem.platform.VdDate
import com.example.vereins_kassensystem.platform.databaseFilePath
import com.example.vereins_kassensystem.platform.deleteFile
import com.example.vereins_kassensystem.platform.fileExists
import com.example.vereins_kassensystem.platform.moveFile
import com.example.vereins_kassensystem.platform.nowMillis
import com.example.vereins_kassensystem.platform.readFile
import com.example.vereins_kassensystem.platform.writeFile
import kotlinx.coroutines.flow.Flow

/**
 * Sichern und Wiederherstellen.
 *
 * Eine Sicherung ist genau eine Datei: die SQLite-Datenbank nach einem Checkpoint. Das
 * frühere Zip verpackte die Datenbank samt WAL-Dateien und einen Abzug der
 * verschlüsselten Einstellungen — der WAL ist nach dem Checkpoint leer, und die
 * Einstellungen waren mit einem Geräteschlüssel verschlüsselt, auf einem anderen Gerät
 * also ohnehin nicht lesbar. Übrig bleibt eine Datei, die sich auf beiden Plattformen
 * ohne Archivbibliothek schreiben lässt.
 *
 * Wo die Datei landet, weiß [BackupExchange]; was zuletzt gesichert wurde, merkt sich
 * [SettingsRepository], weil der Hintergrundplaner auf iOS darüber keine Auskunft gibt.
 */
class BackupRepository(
    private val database: AppDatabase,
    private val exchange: BackupExchange,
    private val settings: SettingsRepository
) {

    /** Zeitpunkt der letzten erfolgreichen Sicherung, oder null. */
    val lastBackupAt: Flow<Long?> get() = settings.lastBackupAt

    suspend fun hasDestination(): Boolean = exchange.hasDestination()

    suspend fun destinationLabel(): String? = exchange.destinationLabel()

    /**
     * Schreibt eine Sicherung an den gewählten Ort und räumt dort Sicherungen weg, die
     * älter als eine Woche sind. Liefert false, wenn kein Ort gewählt ist oder das
     * Schreiben scheitert.
     */
    suspend fun createBackup(): Boolean {
        if (!exchange.hasDestination()) return false
        val bytes = snapshotDatabase() ?: return false
        val fileName = FILE_PREFIX + VdDate.fileStamp(nowMillis()) + FILE_SUFFIX
        if (!exchange.writeBackup(bytes, fileName)) return false
        settings.setLastBackupAt(nowMillis())
        deleteOldBackups()
        return true
    }

    /**
     * Die Datenbank als Bytes, konsistent.
     *
     * Der Checkpoint schreibt alles aus dem Write-Ahead-Log in die Hauptdatei; die
     * Datei wird gelesen, solange die Schreibverbindung gehalten wird, damit zwischen
     * Checkpoint und Kopie niemand einen Verkauf dazwischen bucht.
     */
    private suspend fun snapshotDatabase(): ByteArray? = database.useWriterConnection { transactor ->
        transactor.usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") { statement ->
            while (statement.step()) {
                // Die Ergebniszeile (busy, log, checkpointed) ist hier ohne Belang.
            }
        }
        readFile(databaseFilePath())
    }

    /**
     * Legt eine Sicherung zum Einspielen bereit.
     *
     * Die laufende Datenbank wird nicht überschrieben — Room hält sie offen, und eine
     * Datei unter einer offenen Verbindung auszutauschen ist genau die Sorte Fehler, die
     * sich erst beim übernächsten Start zeigt. Stattdessen liegt die Datei daneben und
     * wird beim nächsten Start vor dem Öffnen übernommen, siehe [applyStagedRestore].
     * Die Oberfläche bittet um einen Neustart.
     */
    suspend fun stageRestore(bytes: ByteArray): Boolean {
        if (!looksLikeSqlite(bytes)) return false
        return writeFile(databaseFilePath() + RESTORE_SUFFIX, bytes)
    }

    private suspend fun deleteOldBackups() {
        val cutoff = nowMillis() - 7L * 24 * 60 * 60 * 1000
        exchange.listBackups()
            .filter { it.name.startsWith(FILE_PREFIX) && it.modifiedAt in 1 until cutoff }
            .forEach { exchange.deleteBackup(it.name) }
    }

    companion object {
        const val FILE_PREFIX = "VereinsDeckel_Backup_"
        const val FILE_SUFFIX = ".sqlite3"
        const val RESTORE_SUFFIX = ".restore"

        /** Die ersten sechzehn Bytes jeder SQLite-Datei: der Text plus ein Nullbyte. */
        private val SQLITE_MAGIC = "SQLite format 3".encodeToByteArray() + byteArrayOf(0)

        /** Alles andere wird gar nicht erst als Wiederherstellung abgelegt. */
        fun looksLikeSqlite(bytes: ByteArray): Boolean =
            bytes.size > SQLITE_MAGIC.size && bytes.copyOfRange(0, SQLITE_MAGIC.size).contentEquals(SQLITE_MAGIC)

        /**
         * Übernimmt eine bereitgelegte Sicherung. Muss laufen, bevor Room die Datenbank
         * öffnet; [com.example.vereins_kassensystem.data.buildDatabase] ruft es auf.
         */
        fun applyStagedRestore() {
            val db = databaseFilePath()
            val staged = db + RESTORE_SUFFIX
            if (!fileExists(staged)) return
            listOf(db, "$db-wal", "$db-shm", "$db-journal").forEach { if (fileExists(it)) deleteFile(it) }
            moveFile(staged, db)
        }
    }
}
