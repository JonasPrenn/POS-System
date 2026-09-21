package com.example.vereins_kassensystem.server

import com.example.vereins_kassensystem.server.db.Database
import com.example.vereins_kassensystem.server.http.module
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.system.exitProcess

/**
 * Einstieg: Konfiguration aus der Umgebung, Migrationen einspielen, dann lauschen.
 *
 * Migrationen laufen beim Start und nicht als eigener Schritt, weil der Dienst bei einem
 * Verein von jemandem betrieben wird, der `docker compose up` kennt und sonst nichts
 * kennen muss.
 *
 * `vereinsdeckel-server health` fragt den laufenden Dienst ab und endet mit 0 oder 1 —
 * der Healthcheck im Container, ohne dass das Image curl mitbringen müsste.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "health") exitProcess(healthCheck())

    val log = LoggerFactory.getLogger("VereinsDeckel")
    val config = ServerConfig.fromEnvironment()
    val db = Database(config.jdbcUrl, config.dbUser, config.dbPassword)
    val migrated = db.migrate()
    log.info("Schema auf Stand {} ({} Migrationen eingespielt)", db.schemaVersion(), migrated)

    Runtime.getRuntime().addShutdownHook(Thread { db.close() })

    embeddedServer(Netty, host = config.host, port = config.port) {
        module(config, db)
    }.start(wait = true)
}

private fun healthCheck(): Int {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    return try {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/health"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build()
        val response = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build()
            .send(request, HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() == 200) 0 else 1
    } catch (e: Exception) {
        1
    }
}
