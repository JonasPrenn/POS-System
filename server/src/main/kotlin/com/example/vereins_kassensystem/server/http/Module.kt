package com.example.vereins_kassensystem.server.http

import com.example.vereins_kassensystem.server.ServerConfig
import com.example.vereins_kassensystem.server.devices.Tokens
import com.example.vereins_kassensystem.server.tenancy.TenantDirectory
import com.example.vereins_kassensystem.server.web.portalRoutes
import com.example.vereins_kassensystem.server.web.webRoutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.example.vereins_kassensystem.sync.WireJson
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.routing.routing
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.seconds

/** Das Token der Verwaltung; ein Wert, weil es genau einen Administrator gibt. */
object AdminPrincipal

/** Der Dienst — ohne Netz und Datenbank aufgesetzt, damit die Tests ihn genauso starten. */
fun Application.module(config: ServerConfig, directory: TenantDirectory, pollMailbox: Boolean = true) {
    if (config.trustProxy) install(XForwardedHeaders)
    install(ContentNegotiation) { json(WireJson) }
    install(CallLogging) { level = Level.INFO }
    installErrorHandling()

    install(Authentication) {
        bearer("device") {
            realm = "VereinsDeckel"
            authenticate { credential -> directory.authenticate(credential.token) }
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
        // Anmelden und Einrichten der Verwaltung: Argon2id ist teuer, und Raten soll es auch sein.
        register(RateLimitName("login")) {
            rateLimiter(limit = 10, refillPeriod = 60.seconds)
            requestKey { call -> call.request.origin.remoteAddress }
        }
    }

    routing {
        apiRoutes(directory)
        webRoutes(directory)
        portalRoutes(directory)
        // Der Posteingang jedes Vereins: alle zehn Minuten, solange der Abruf eingeschaltet ist — ein Fehler steht in den Einstellungen, nicht im Log allein.
        if (pollMailbox) launch(Dispatchers.IO) {
            while (isActive) {
                delay(10 * 60 * 1000L)
                for (tenant in directory.all()) runCatching { if (tenant.web.settings.load().imap.enabled) tenant.web.intake.poll() }
            }
        }
        // Online-Aufladungen, deren Benachrichtigung nicht ankam: alle fünf Minuten beim Anbieter nachfragen.
        if (pollMailbox) launch(Dispatchers.IO) {
            while (isActive) {
                delay(5 * 60 * 1000L)
                for (tenant in directory.all()) runCatching { tenant.web.portal.sweep() }
            }
        }
    }
}
