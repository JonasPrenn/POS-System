package com.example.vereins_kassensystem.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.sql.Connection

/**
 * Verbindungspool, Migrationen und die zwei Transaktionsarten, die der Dienst kennt.
 *
 * **Alle Schreibzugriffe auf Sync-Tabellen laufen über [write]**, und [write] nimmt eine
 * Sperre. Der Grund liegt in `sync_seq`: Ein Client merkt sich die höchste Nummer, die er
 * gesehen hat. Dürften zwei Transaktionen gleichzeitig Nummern ziehen, könnte die mit der
 * höheren Nummer zuerst festgeschrieben werden — der Client sähe 101, speicherte 101 als
 * Lesezeiger und bekäme 100 nie zu Gesicht. Mit der Sperre werden Nummern in genau der
 * Reihenfolge sichtbar, in der sie vergeben wurden. Für einen Verein mit zwei Theken kostet
 * das nichts; die Web-Verwaltung muss sich später an dieselbe Regel halten.
 *
 * [read] liest in einem Schnappschuss (REPEATABLE READ), damit ein Abgleich, der elf
 * Tabellen nacheinander liest, nicht zwischen zwei Tabellen von einem Commit überholt wird.
 */
class Database(
    jdbcUrl: String,
    user: String,
    password: String,
    poolSize: Int = 8,
) : AutoCloseable {

    private val pool = HikariDataSource(
        HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username = user
            this.password = password
            maximumPoolSize = poolSize
            isAutoCommit = false
            poolName = "vereinsdeckel"
        }
    )

    private val flyway: Flyway
        get() = Flyway.configure()
            .dataSource(pool)
            .locations("classpath:db/migration")
            .load()

    /** Spielt ausstehende Migrationen ein; liefert, wie viele es waren. */
    fun migrate(): Int = flyway.migrate().migrationsExecuted

    /** Die Schemaversion, wie Flyway sie kennt — für /v1/health. */
    fun schemaVersion(): String? = flyway.info().current()?.version?.version

    /** Gewöhnliche Transaktion, für Tabellen ohne Sync-Zähler (Geräte, Kopplungscodes). */
    fun <T> transaction(block: (Connection) -> T): T = pool.connection.use { c ->
        try {
            block(c).also { c.commit() }
        } catch (e: Throwable) {
            c.rollback()
            throw e
        }
    }

    /** Schreibende Transaktion auf Sync-Tabellen — serialisiert über eine Advisory-Sperre. */
    fun <T> write(block: (Connection) -> T): T = transaction { c ->
        c.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { st ->
            st.setLong(1, SYNC_LOCK)
            st.execute()
        }
        block(c)
    }

    /** Lesende Transaktion mit Schnappschuss über alle Tabellen. */
    fun <T> read(block: (Connection) -> T): T = pool.connection.use { c ->
        c.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
        c.isReadOnly = true
        try {
            block(c).also { c.commit() }
        } catch (e: Throwable) {
            c.rollback()
            throw e
        } finally {
            c.isReadOnly = false
            c.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
        }
    }

    override fun close() = pool.close()

    companion object {
        /** Beliebige feste Zahl; nur wichtig, dass alle Schreiber dieselbe nehmen. */
        const val SYNC_LOCK = 0x5644_5343L
    }
}
