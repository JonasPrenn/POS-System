package com.example.vereins_kassensystem.server.devices

import com.example.vereins_kassensystem.platform.Ids
import com.example.vereins_kassensystem.server.db.Database
import com.example.vereins_kassensystem.server.db.execute
import com.example.vereins_kassensystem.server.db.query
import com.example.vereins_kassensystem.server.db.queryOne
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** Das angemeldete Gerät, wie die Routen es sehen. */
data class DevicePrincipal(val id: UUID, val label: String)

/** Kopplung schlägt fehl — 409, mit Klartext für den Einrichtungsdialog. */
class PairingFailed(message: String) : RuntimeException(message)

class DeviceRecord(
    val id: UUID,
    val label: String,
    val platform: String,
    val createdAt: OffsetDateTime,
    val lastSeenAt: OffsetDateTime?,
    val lastAckSeq: Long,
    val revoked: Boolean,
)

class Registration(val deviceId: UUID, val token: String)

/**
 * Geräte, Kopplungscodes und Token-Prüfung (Spezifikation 3.2 und 5.2).
 *
 * Kein Passwort in der App: Der Administrator erzeugt einen Code mit kurzer Laufzeit, das
 * Gerät tauscht ihn einmalig gegen ein dauerhaftes Token. Geht ein Gerät verloren, wird es
 * einzeln gesperrt, ohne die anderen anzufassen.
 */
class DeviceStore(private val db: Database, private val codeTtl: Duration) {

    /**
     * Einmal geprüfte Token, SHA-256 → Gerät. Argon2id kostet je Prüfung Millisekunden
     * und Speicher; ein Gerät fragt alle 60 Sekunden. Gesperrt wird trotzdem sofort, weil
     * `revoked` bei jeder Anfrage aus der Datenbank kommt.
     */
    private val verified = ConcurrentHashMap<String, UUID>()

    fun createPairingCode(now: Instant = Instant.now()): Pair<String, OffsetDateTime> {
        val code = Tokens.newPairingCode()
        val expiresAt = now.plus(codeTtl.toJavaDuration()).atOffset(ZoneOffset.UTC)
        db.transaction { c ->
            c.execute(
                "INSERT INTO pairing_codes (id, code_hash, expires_at) VALUES (?, ?, ?)",
                UUID.fromString(Ids.new()), Tokens.sha256Hex(Tokens.normalizePairingCode(code)), expiresAt
            )
        }
        return code to expiresAt
    }

    fun register(code: String, label: String, platform: String, now: Instant = Instant.now()): Registration {
        if (platform !in setOf("android", "ios")) throw PairingFailed("platform muss android oder ios sein")
        val cleanLabel = label.trim().take(80)
        if (cleanLabel.isEmpty()) throw PairingFailed("label fehlt")
        val hash = Tokens.sha256Hex(Tokens.normalizePairingCode(code))

        return db.transaction { c ->
            val found = c.queryOne(
                "SELECT id, expires_at, used_at FROM pairing_codes WHERE code_hash = ? FOR UPDATE", hash
            ) { rs ->
                Triple(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("expires_at", OffsetDateTime::class.java),
                    rs.getObject("used_at", OffsetDateTime::class.java),
                )
            } ?: throw PairingFailed("Kopplungscode unbekannt")
            val (codeId, expiresAt, usedAt) = found
            if (usedAt != null) throw PairingFailed("Kopplungscode verbraucht")
            if (!expiresAt.toInstant().isAfter(now)) throw PairingFailed("Kopplungscode abgelaufen")

            val deviceId = UUID.fromString(Ids.new())
            val token = Tokens.newDeviceToken(deviceId)
            val secret = Tokens.parseDeviceToken(token)!!.secret
            c.execute(
                "INSERT INTO devices (id, label, platform, token_hash) VALUES (?, ?, ?, ?)",
                deviceId, cleanLabel, platform, Argon2.hash(secret)
            )
            c.execute(
                "UPDATE pairing_codes SET used_at = ?, device_id = ? WHERE id = ?",
                now.atOffset(ZoneOffset.UTC), deviceId, codeId
            )
            // Fürs Protokoll der Verwaltung: Wer ein Gerät koppelt, soll später nachlesen können, wann.
            c.execute(
                "INSERT INTO audit_log (actor, action, subject, detail) VALUES (?, 'device.register', ?, ?)",
                "Gerät", cleanLabel, if (platform == "ios") "iPad" else "Android"
            )
            Registration(deviceId, token)
        }
    }

    /** Null bei ungültigem oder gesperrtem Token — die Route antwortet dann mit 401. */
    fun authenticate(token: String): DevicePrincipal? {
        val parsed = Tokens.parseDeviceToken(token) ?: return null
        val cacheKey = Tokens.sha256Hex(token)

        val device = db.transaction { c ->
            c.queryOne(
                "SELECT label, token_hash, revoked FROM devices WHERE id = ?", parsed.deviceId
            ) { rs -> Triple(rs.getString("label"), rs.getString("token_hash"), rs.getBoolean("revoked")) }
        } ?: return null
        val (label, tokenHash, revoked) = device
        if (revoked) {
            verified.remove(cacheKey)
            return null
        }

        if (verified[cacheKey] != parsed.deviceId) {
            if (!Argon2.verify(parsed.secret, tokenHash)) return null
            if (verified.size > 10_000) verified.clear()
            verified[cacheKey] = parsed.deviceId
        }
        return DevicePrincipal(parsed.deviceId, label)
    }

    /** Merkt sich, wann das Gerät zuletzt da war und bis wohin es gelesen hat. */
    fun touch(deviceId: UUID, ackSeq: Long? = null) {
        db.transaction { c ->
            c.execute(
                "UPDATE devices SET last_seen_at = now(), last_ack_seq = GREATEST(last_ack_seq, ?) WHERE id = ?",
                ackSeq ?: 0L, deviceId
            )
        }
    }

    fun list(): List<DeviceRecord> = db.transaction { c ->
        c.query("SELECT * FROM devices ORDER BY created_at") { rs ->
            DeviceRecord(
                id = rs.getObject("id", UUID::class.java),
                label = rs.getString("label"),
                platform = rs.getString("platform"),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                lastSeenAt = rs.getObject("last_seen_at", OffsetDateTime::class.java),
                lastAckSeq = rs.getLong("last_ack_seq"),
                revoked = rs.getBoolean("revoked"),
            )
        }
    }

    fun revoke(deviceId: UUID): Boolean = db.transaction { c ->
        c.execute("UPDATE devices SET revoked = true WHERE id = ?", deviceId) > 0
    }
}
