package com.example.vereins_kassensystem.server.http

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Die Nachrichten der Schnittstelle (Kapitel 4 und 5). Im Code camelCase, auf dem Draht
 * snake_case — die Umbenennung macht `JsonNamingStrategy.SnakeCase` in [module].
 * Zeilen bleiben [JsonObject]: ihre Schlüssel sind die Spaltennamen aus dem Register.
 */

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
    val clientChangeId: String,
    val entity: String,
    val op: String,
    val baseUpdatedAt: String? = null,
    val row: JsonObject,
)

@Serializable
data class PushResult(
    val clientChangeId: String,
    val status: String,
    val seq: Long? = null,
    val current: JsonObject? = null,
)

@Serializable
data class PushResponse(val results: List<PushResult>, val nextSince: Long)

@Serializable
data class BalanceResponse(val memberId: String, val balance: String, val serverTime: String)

@Serializable
data class ReceiptUploadResponse(val photoKey: String)

@Serializable
data class PairingCodeResponse(val code: String, val expiresAt: String)

@Serializable
data class DeviceDto(
    val id: String,
    val label: String,
    val platform: String,
    val createdAt: String,
    val lastSeenAt: String?,
    val lastAckSeq: Long,
    val revoked: Boolean,
)

@Serializable
data class ErrorResponse(val error: String, val message: String)
