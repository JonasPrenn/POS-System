package com.example.vereins_kassensystem.server.tenancy

import com.example.vereins_kassensystem.server.db.Database
import java.sql.DriverManager

/**
 * Wo die Datenbanken liegen: alle auf dem PostgreSQL der ersten (`DATABASE_URL`), unter eigenem
 * Namen daneben — `vereinsdeckel_system` für das System, `vereinsdeckel_<kürzel>` je Verein.
 * Angelegt werden sie mit dem Zugang der ersten; in `deploy/compose.yaml` darf der das.
 */
open class Databases(private val jdbcUrl: String, private val user: String, private val password: String) {

    private val parts = checkNotNull(URL.matchEntire(jdbcUrl)) { "keine PostgreSQL-Adresse: $jdbcUrl" }

    /** Der Name der ersten Datenbank — aus ihm leiten sich die anderen ab. */
    val firstName: String = parts.groupValues[2]

    val systemName: String get() = "${firstName}_system"

    fun urlOf(name: String): String = parts.groupValues[1] + checkName(name) + parts.groupValues[3]

    open fun exists(name: String): Boolean = connect { c ->
        c.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?").use { st ->
            st.setString(1, checkName(name))
            st.executeQuery().use { it.next() }
        }
    }

    /** CREATE DATABASE geht nicht in einer Transaktion — deshalb eine eigene Verbindung, ohne Pool. */
    open fun create(name: String) {
        connect { c -> c.createStatement().use { it.execute("CREATE DATABASE \"${checkName(name)}\"") } }
    }

    /** Öffnet [name] und spielt die Migrationen ein; legt die Datenbank an, wenn es sie noch nicht gibt. */
    fun open(name: String, migrations: String, poolSize: Int): Database {
        if (!exists(name)) create(name)
        return Database(urlOf(name), user, password, poolSize, migrations, poolName = name).also {
            try {
                it.migrate()
            } catch (e: Exception) {
                it.close()
                throw e
            }
        }
    }

    private fun <T> connect(block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection(jdbcUrl, user, password).use { c ->
            c.autoCommit = true
            block(c)
        }

    companion object {
        private val URL = Regex("""(jdbc:postgresql://[^/]+/)([^?]+)(\?.*)?""")
        private val NAME = Regex("[a-z0-9_]{1,63}")

        /** Namen setzt dieser Code selbst zusammen; was anderes durchrutscht, wäre ein Fehler hier. */
        fun checkName(name: String): String = name.also { require(NAME.matches(it)) { "ungültiger Datenbankname: $it" } }
    }
}
