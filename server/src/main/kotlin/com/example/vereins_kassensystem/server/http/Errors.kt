package com.example.vereins_kassensystem.server.http

import com.example.vereins_kassensystem.server.devices.PairingFailed
import com.example.vereins_kassensystem.server.sync.Unprocessable
import com.example.vereins_kassensystem.sync.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.JsonConvertException
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.SerializationException

/** Ein Fehler mit festem Statuscode, den eine Route bewusst wirft. */
class ApiException(val status: HttpStatusCode, val code: String, message: String) : RuntimeException(message)

/**
 * Die Fehlerbilder aus 5.3, einheitlich als `{ "error": ..., "message": ... }`.
 *
 * 422 ist ein Programmfehler der App (Schreibversuch auf Abgeleitetes, unbekanntes
 * Feld), 409 ein verbrauchter Kopplungscode, 400 kaputtes JSON. Alles Unerwartete wird
 * geloggt und ist ein 500 — die App behält dann ihre Warteschlange und versucht es später.
 */
fun Application.installErrorHandling() {
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ErrorResponse(cause.code, cause.message ?: cause.code))
        }
        exception<Unprocessable> { call, cause ->
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("unprocessable", cause.message ?: "nicht anwendbar"))
        }
        exception<PairingFailed> { call, cause ->
            call.respond(HttpStatusCode.Conflict, ErrorResponse("pairing_failed", cause.message ?: "Kopplung fehlgeschlagen"))
        }
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", describe(cause)))
        }
        exception<JsonConvertException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", describe(cause)))
        }
        exception<SerializationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", describe(cause)))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unbehandelter Fehler bei ${call.request.local.method.value} ${call.request.local.uri}", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal", "Interner Fehler"))
        }
        status(HttpStatusCode.Unauthorized) { call, status ->
            call.respond(status, ErrorResponse("unauthorized", "Token ungültig oder gesperrt"))
        }
        status(HttpStatusCode.TooManyRequests) { call, status ->
            call.respond(status, ErrorResponse("too_many_requests", "Zu viele Anfragen, bitte später erneut"))
        }
    }
}

private fun describe(cause: Throwable): String {
    val root = generateSequence(cause) { it.cause }.last()
    return root.message?.lineSequence()?.firstOrNull() ?: "Anfrage nicht lesbar"
}
