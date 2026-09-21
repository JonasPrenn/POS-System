package com.example.vereins_kassensystem.sync

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json

/**
 * Der Server hat geantwortet, aber nicht mit Erfolg. [status] entscheidet, was die App tut
 * (Spezifikation 5.3): 401 heißt neu koppeln, 409 verbrauchter Kopplungscode, 422 ist ein
 * Programmfehler, 429 und 5xx heißen später wieder versuchen.
 */
class SyncHttpException(val status: Int, val code: String?, message: String) : Exception(message)

/** Was jeder Client des Abgleichs braucht: das gemeinsame JSON und Zeitgrenzen. */
fun HttpClientConfig<*>.installSyncDefaults() {
    install(ContentNegotiation) { json(WireJson) }
    install(HttpTimeout) {
        // Knapp: Der Abgleich läuft neben dem Verkauf her und darf ihn nie aufhalten.
        connectTimeoutMillis = 5_000
        requestTimeoutMillis = 20_000
    }
}

/**
 * Was die App vom Server will — als Interface, damit der Abgleich der App gegen einen
 * nachgebauten Server im Speicher getestet werden kann, ohne Netz und ohne PostgreSQL.
 */
interface SyncApi {
    suspend fun health(): HealthResponse
    suspend fun register(pairingCode: String, label: String, platform: String): RegisterResponse
    suspend fun changes(since: Long, limit: Int = 500): ChangesResponse
    suspend fun push(operations: List<PushOperation>): PushResponse
    suspend fun balance(memberId: String): BalanceResponse

    suspend fun uploadReceipt(bytes: ByteArray, contentType: ContentType): String
    suspend fun downloadReceipt(photoKey: String): ByteArray
}

/**
 * Die Schnittstelle aus Kapitel 5, von der Seite des Geräts.
 *
 * Bekommt den [HttpClient] hereingereicht statt ihn zu bauen: die App wählt die Engine
 * ihrer Plattform, der Servertest nimmt den Client der Ktor-Testhülle — so prüft derselbe
 * Code, den das Tablet benutzt, gegen den echten Server.
 */
class SyncClient(
    private val http: HttpClient,
    baseUrl: String,
    private val token: suspend () -> String?,
) : SyncApi {
    private val base = baseUrl.trimEnd('/')

    override suspend fun health(): HealthResponse = http.get("$base/v1/health").orThrow().body()

    override suspend fun register(pairingCode: String, label: String, platform: String): RegisterResponse =
        http.post("$base/v1/devices/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(pairingCode, label, platform))
        }.orThrow().body()

    override suspend fun changes(since: Long, limit: Int): ChangesResponse =
        http.get("$base/v1/sync/changes") {
            authorize()
            parameter("since", since)
            parameter("limit", limit)
        }.orThrow().body()

    override suspend fun push(operations: List<PushOperation>): PushResponse =
        http.post("$base/v1/sync/push") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(PushRequest(operations = operations))
        }.orThrow().body()

    override suspend fun balance(memberId: String): BalanceResponse =
        http.get("$base/v1/members/$memberId/balance") { authorize() }.orThrow().body()

    /** Lädt ein Belegfoto hoch und liefert den Schlüssel für `deliveries.photo_key`. */
    override suspend fun uploadReceipt(bytes: ByteArray, contentType: ContentType): String =
        http.post("$base/v1/media/receipts") {
            authorize()
            contentType(contentType)
            setBody(bytes)
        }.orThrow().body<ReceiptUploadResponse>().photoKey

    override suspend fun downloadReceipt(photoKey: String): ByteArray =
        http.get("$base/v1/media/receipts/$photoKey") { authorize() }.orThrow().readRawBytes()

    private suspend fun HttpRequestBuilder.authorize() {
        token()?.let { bearerAuth(it) }
    }

    private suspend fun HttpResponse.orThrow(): HttpResponse {
        if (status.isSuccess()) return this
        val text = runCatching { bodyAsText() }.getOrDefault("")
        val error = runCatching { WireJson.decodeFromString(ErrorResponse.serializer(), text) }.getOrNull()
        throw SyncHttpException(status.value, error?.error, error?.message ?: "HTTP ${status.value}")
    }
}
