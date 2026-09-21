package com.example.vereins_kassensystem.server.http

import com.example.vereins_kassensystem.server.db.Database
import com.example.vereins_kassensystem.server.db.queryOne
import com.example.vereins_kassensystem.server.devices.DevicePrincipal
import com.example.vereins_kassensystem.server.devices.DeviceStore
import com.example.vereins_kassensystem.server.media.ReceiptStore
import com.example.vereins_kassensystem.server.sync.SyncStore
import com.example.vereins_kassensystem.server.sync.Unprocessable
import com.example.vereins_kassensystem.server.sync.Values
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Instant

/** Alle Endpunkte aus 5.1, plus die drei Verwaltungsaufrufe, die 5.2 voraussetzt. */
fun Route.apiRoutes(
    db: Database,
    devices: DeviceStore,
    sync: SyncStore,
    receipts: ReceiptStore,
) {
    route("/v1") {

        // Ohne Token: Der Einrichtungsdialog prüft damit eine eingetippte Adresse.
        get("/health") {
            val databaseOk = runCatching { db.read { c -> c.queryOne("SELECT 1") { it.getInt(1) } } }.isSuccess
            val body = HealthResponse(
                status = if (databaseOk) "ok" else "degraded",
                serverTime = Values.format(Instant.now()),
                schemaVersion = runCatching { db.schemaVersion() }.getOrNull(),
                database = if (databaseOk) "ok" else "unreachable",
            )
            call.respond(if (databaseOk) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, body)
        }

        // Kopplungscodes sind kurz; wer sie durchprobiert, wird gebremst.
        rateLimit(RateLimitName("register")) {
            post("/devices/register") {
                val request = call.receive<RegisterRequest>()
                val registration = devices.register(request.pairingCode, request.label, request.platform)
                call.respond(
                    HttpStatusCode.Created,
                    RegisterResponse(deviceId = registration.deviceId.toString(), token = registration.token)
                )
            }
        }

        authenticate("device") {
            route("/sync") {
                get("/changes") {
                    val device = device()
                    val since = call.queryParameters["since"]?.toLongOrNull() ?: 0L
                    val limit = call.queryParameters["limit"]?.toIntOrNull() ?: 500
                    if (since < 0) throw ApiException(HttpStatusCode.BadRequest, "bad_request", "since muss 0 oder größer sein")
                    if (limit !in 1..1000) throw ApiException(HttpStatusCode.BadRequest, "bad_request", "limit muss zwischen 1 und 1000 liegen")

                    val page = sync.changes(since, limit)
                    devices.touch(device.id, ackSeq = since)
                    call.respond(
                        ChangesResponse(
                            changes = page.changes.map { ChangeDto(it.entity, it.seq, it.deleted, it.row) },
                            nextSince = page.nextSince,
                            hasMore = page.hasMore,
                            serverTime = Values.format(Instant.now()),
                        )
                    )
                }

                post("/push") {
                    val device = device()
                    val request = call.receive<PushRequest>()
                    request.deviceId?.let {
                        if (it.lowercase() != device.id.toString()) {
                            throw Unprocessable("device_id '$it' passt nicht zum Token des Geräts ${device.id}")
                        }
                    }
                    val operations = request.operations.mapIndexed { index, op ->
                        SyncStore.Operation(
                            clientChangeId = Values.parseUuid(op.clientChangeId, "Operation $index: client_change_id"),
                            entity = op.entity,
                            op = op.op,
                            baseUpdatedAt = op.baseUpdatedAt?.let { Values.parseTimestamp(it, "Operation $index: base_updated_at") },
                            row = op.row,
                        )
                    }
                    val outcome = sync.push(device.id, operations)
                    devices.touch(device.id)
                    call.respond(
                        PushResponse(
                            results = outcome.results.map { PushResult(it.clientChangeId.toString(), it.status, it.seq, it.current) },
                            nextSince = outcome.nextSince,
                        )
                    )
                }
            }

            // Einzelsaldo, unmittelbar vor einer Deckelbelastung.
            get("/members/{id}/balance") {
                val id = Values.parseUuid(call.pathParameters["id"] ?: "", "id")
                val balance = sync.balance(id)
                    ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "Mitglied unbekannt")
                call.respond(BalanceResponse(id.toString(), balance.toPlainString(), Values.format(Instant.now())))
            }

            route("/media/receipts") {
                // Rohes Bild im Body, Inhaltstyp im Header — kein Multipart, die App
                // schickt genau eine Datei.
                post {
                    val extension = receipts.extensionFor(call.request.contentType())
                        ?: throw ApiException(HttpStatusCode.UnsupportedMediaType, "unsupported_media_type", "Erlaubt sind image/jpeg, image/png, image/webp und image/heic")
                    val declared = call.request.contentLength() ?: 0L
                    if (declared > receipts.maxBytes) throw ApiException(HttpStatusCode.PayloadTooLarge, "payload_too_large", "Höchstens 20 MB")
                    val bytes = call.receive<ByteArray>()
                    if (bytes.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, "bad_request", "Leerer Body")
                    if (bytes.size > receipts.maxBytes) throw ApiException(HttpStatusCode.PayloadTooLarge, "payload_too_large", "Höchstens 20 MB")
                    call.respond(HttpStatusCode.Created, ReceiptUploadResponse(receipts.store(bytes, extension)))
                }
                get("/{key}") {
                    val stored = receipts.find(call.pathParameters["key"] ?: "")
                        ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "Beleg unbekannt")
                    call.respond(LocalFileContent(stored.path.toFile(), stored.contentType))
                }
            }
        }

        // Verwaltung: Kopplungscodes erzeugen, Geräte sehen und sperren. Bis die
        // Web-Verwaltung steht, macht das der Administrator mit curl.
        authenticate("admin") {
            route("/admin") {
                post("/pairing-codes") {
                    val (code, expiresAt) = devices.createPairingCode()
                    call.respond(HttpStatusCode.Created, PairingCodeResponse(code, Values.format(expiresAt)))
                }
                get("/devices") {
                    call.respond(devices.list().map {
                        DeviceDto(
                            id = it.id.toString(),
                            label = it.label,
                            platform = it.platform,
                            createdAt = Values.format(it.createdAt),
                            lastSeenAt = it.lastSeenAt?.let(Values::format),
                            lastAckSeq = it.lastAckSeq,
                            revoked = it.revoked,
                        )
                    })
                }
                post("/devices/{id}/revoke") {
                    val id = Values.parseUuid(call.pathParameters["id"] ?: "", "id")
                    if (!devices.revoke(id)) throw ApiException(HttpStatusCode.NotFound, "not_found", "Gerät unbekannt")
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}

private fun RoutingContext.device(): DevicePrincipal =
    call.principal<DevicePrincipal>() ?: throw ApiException(HttpStatusCode.Unauthorized, "unauthorized", "Kein Gerät angemeldet")
