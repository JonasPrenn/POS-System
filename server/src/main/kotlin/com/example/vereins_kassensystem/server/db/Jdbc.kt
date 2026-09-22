package com.example.vereins_kassensystem.server.db

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * Schmale JDBC-Helfer. Kein ORM: Das Schema lebt von Triggern, einer Sicht und einer
 * Sequenz, und die SQL-Sätze sollen so dastehen, wie sie in der Spezifikation stehen.
 */

fun PreparedStatement.bind(params: Array<out Any?>) {
    params.forEachIndexed { i, p ->
        // Types.OTHER lässt PostgreSQL den Typ aus der Spalte ableiten — nötig, weil ein
        // null für eine UUID-Spalte sonst als text ankäme.
        if (p == null) setNull(i + 1, Types.OTHER) else setObject(i + 1, p)
    }
}

fun <T> Connection.query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { st ->
        st.bind(params)
        st.executeQuery().use { rs ->
            buildList { while (rs.next()) add(map(rs)) }
        }
    }

fun <T> Connection.queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? =
    query(sql, *params, map = map).firstOrNull()

fun Connection.execute(sql: String, vararg params: Any?): Int =
    prepareStatement(sql).use { st ->
        st.bind(params)
        st.executeUpdate()
    }
