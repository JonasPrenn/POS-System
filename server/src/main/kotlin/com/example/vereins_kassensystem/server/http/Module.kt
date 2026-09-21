package com.example.vereins_kassensystem.server.http

import com.example.vereins_kassensystem.server.ServerConfig
import com.example.vereins_kassensystem.server.db.Database
import com.example.vereins_kassensystem.server.devices.DeviceStore
import com.example.vereins_kassensystem.server.devices.Tokens
import com.example.vereins_kassensystem.server.media.ReceiptStore
import com.example.vereins_kassensystem.server.sync.SyncStore
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.seconds

/** Das Token der Verwaltung; ein Wert, weil es genau einen Administrator gibt. */
object AdminPrincipal

/**
 * Das JSON der Schnittstelle: snake_case, unbekannte Felder in Anfragen werden
 * übergangen (ältere Server sollen neuere Apps nicht abweisen), nulls in Antworten
 * ausgelassen. Die App muss dieselbe Konfiguration benutzen.
 */
val apiJson: Json = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** Der Dienst — ohne Netz und Datenbank aufgesetzt, damit die Tests ihn genauso starten. */
fun Application.module(config: ServerConfig, db: Database) {
    val devices = DeviceStore(db, config.pairingCodeTtl)
    val sync = SyncStore(db)
    val receipts = ReceiptStore(config.mediaDir)

    install(ContentNegotiation) { json(apiJson) }
    install(CallLogging) { level = Level.INFO }
    installErrorHandling()

    install(Authentication) {
        bearer("device") {
            realm = "VereinsDeckel"
            authenticate { credential -> devices.authenticate(credential.token) }
        }
        bearer("admin") {
            realm = "VereinsDeckel Verwaltung"
            authenticate { credential ->
                if (Tokens.constantTimeEquals(credential.token, config.pairingAdminToken)) AdminPrincipal else null
            }
        }
    }

    install(RateLimit) {
        register(RateLimitName("register")) {
            rateLimiter(limit = 10, refillPeriod = 60.seconds)
            requestKey { call -> call.request.origin.remoteAddress }
        }
    }

    routing {
        apiRoutes(db, devices, sync, receipts)
    }
}
