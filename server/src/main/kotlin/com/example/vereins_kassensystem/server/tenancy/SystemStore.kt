package com.example.vereins_kassensystem.server.tenancy

import com.example.vereins_kassensystem.server.db.Database
import com.example.vereins_kassensystem.server.db.execute
import com.example.vereins_kassensystem.server.db.query
import com.example.vereins_kassensystem.server.db.queryOne
import com.example.vereins_kassensystem.server.web.AccountProblem
import com.example.vereins_kassensystem.server.web.Accounts
import com.example.vereins_kassensystem.server.web.AuditLog
import java.sql.ResultSet
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

/**
 * Die Systemdatenbank: welche Vereine es gibt, welches Gerät zu welchem gehört, was für alle gilt,
 * und die Hauptadmins. Deren Benutzer und Sitzungen haben dieselbe Form wie die eines Vereins —
 * so trägt sie dieselbe Anmeldung ([Accounts]) und dasselbe Protokoll ([AuditLog]).
 */
class SystemStore(val db: Database, today: () -> LocalDate) {

    val accounts = Accounts(db, today)
    val audit = AuditLog(db)

    fun tenants(): List<TenantInfo> = db.transaction { c -> c.query("SELECT * FROM tenants ORDER BY is_default DESC, created_at, slug") { it.tenant() } }

    fun insert(info: TenantInfo) = db.transaction { c ->
        try {
            c.execute(
                "INSERT INTO tenants (id, slug, name, db_name, media_dir, is_default, active) VALUES (?, ?, ?, ?, ?, ?, ?)",
                info.id, info.slug, info.name, info.dbName, info.mediaDir, info.isDefault, info.active
            )
        } catch (e: SQLException) {
            throw if (e.sqlState == UNIQUE_VIOLATION) AccountProblem("Das Kürzel „${info.slug}“ ist schon vergeben.") else e
        }
    }

    fun rename(id: UUID, slug: String, name: String) = db.transaction { c ->
        try {
            c.execute("UPDATE tenants SET slug = ?, name = ? WHERE id = ?", slug, name, id)
        } catch (e: SQLException) {
            throw if (e.sqlState == UNIQUE_VIOLATION) AccountProblem("Das Kürzel „$slug“ ist schon vergeben.") else e
        }
    }

    fun slugTaken(slug: String, except: UUID? = null): Boolean = db.transaction { c ->
        c.queryOne("SELECT 1 FROM tenants WHERE slug = ? AND id IS DISTINCT FROM ?", slug, except) { true } ?: false
    }

    fun route(deviceId: UUID): UUID? = db.transaction { c ->
        c.queryOne("SELECT tenant_id FROM device_routes WHERE device_id = ?", deviceId) { it.getObject("tenant_id", UUID::class.java) }
    }

    fun putRoute(deviceId: UUID, tenantId: UUID) = db.transaction { c ->
        c.execute("INSERT INTO device_routes (device_id, tenant_id) VALUES (?, ?) ON CONFLICT (device_id) DO NOTHING", deviceId, tenantId)
    }

    fun setting(key: String): String? = db.transaction { c -> c.queryOne("SELECT value FROM system_settings WHERE key = ?", key) { it.getString("value") } }

    fun putSetting(key: String, value: String) = db.transaction { c ->
        c.execute("INSERT INTO system_settings (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value", key, value)
    }

    private fun ResultSet.tenant() = TenantInfo(
        id = getObject("id", UUID::class.java),
        slug = getString("slug"),
        name = getString("name"),
        dbName = getString("db_name"),
        mediaDir = getString("media_dir"),
        isDefault = getBoolean("is_default"),
        active = getBoolean("active"),
        createdAt = getTimestamp("created_at").toInstant(),
    )

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
    }
}
