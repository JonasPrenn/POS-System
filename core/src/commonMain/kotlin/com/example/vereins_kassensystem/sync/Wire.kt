package com.example.vereins_kassensystem.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject

/**
 * Das Drahtformat des Abgleichs (Spezifikation, Kapitel 4 und 5) — genau einmal, für App
 * und Server. Im Code camelCase, auf dem Draht snake_case; die Umbenennung macht
 * [WireJson]. Zeilen bleiben [JsonObject]: ihre Schlüssel sind die Spaltennamen des
 * Serverschemas, und was darin steht, regeln auf dem Server `sync/Entities.kt` und in der
 * App `data/sync/RowCodec.kt`.
 */
val WireJson: Json = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    // Ein älterer Server soll eine neuere App nicht abweisen und umgekehrt.
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Serializable
data class HealthResponse(val status: String, val serverTime: String, val schemaVersion: String?, val database: String)

@Serializable
data class RegisterRequest(val pairingCode: String, val label: String, val platform: String)

@Serializable
data class RegisterResponse(val deviceId: String, val token: String, val initialSince: Long = 0)

@Serializable
data class ChangesResponse(
    val changes: List<ChangeDto>,
    val nextSince: Long,
    val hasMore: Boolean,
    val serverTime: String,
)

@Serializable
data class ChangeDto(val entity: String, val seq: Long, val deleted: Boolean, val row: JsonObject)

@Serializable
data class PushRequest(val deviceId: String? = null, val operations: List<PushOperation>)

@Serializable
data class PushOperation(
    /** Idempotenzschlüssel: Ein Wiederholungsversuch mit demselben Wert bucht nichts doppelt. */
    val clientChangeId: String,
    val entity: String,
    /** `insert`, `update` oder `delete`. */
    val op: String,
    /** Der `updated_at` der Serverzeile, auf der die Änderung aufsetzt — für „letzter Schreibvorgang gewinnt". */
    val baseUpdatedAt: String? = null,
    val row: JsonObject,
)

@Serializable
data class PushResult(
    val clientChangeId: String,
    /** `applied` oder `ignored_stale`. */
    val status: String,
    val seq: Long? = null,
    /** Bei `ignored_stale` der Stand des Servers, den die App übernimmt. */
    val current: JsonObject? = null,
)

@Serializable
data class PushResponse(val results: List<PushResult>, val nextSince: Long)

@Serializable
data class BalanceResponse(val memberId: String, val balance: String, val serverTime: String)

@Serializable
data class ReceiptUploadResponse(val photoKey: String)

@Serializable
data class ErrorResponse(val error: String, val message: String)

object PushStatus {
    const val APPLIED = "applied"
    const val IGNORED_STALE = "ignored_stale"
}
