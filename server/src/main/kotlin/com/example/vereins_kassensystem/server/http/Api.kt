package com.example.vereins_kassensystem.server.http

import kotlinx.serialization.Serializable

/**
 * Nachrichten, die nur die Verwaltung des Servers spricht. Alles, was auch die App kennt —
 * Kopplung, Ziehen, Schieben, Saldo, Belege, Fehler — steht in `:core` unter
 * `sync/Wire.kt`, damit beide Seiten dasselbe Format benutzen statt zweier Abschriften.
 */

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
