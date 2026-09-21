package com.example.vereins_kassensystem.server

import com.example.vereins_kassensystem.server.http.module
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Einmal über echtes HTTP statt der Ktor-Testhülle: Netty, Port, JSON auf dem Draht.
 * Das ist der Weg, den Main.kt nimmt.
 */
class NettyTest {

    @Test
    fun `the service answers over a real socket`() {
        val db = TestPostgres.freshDatabase()
        val config = ServerConfig(
            jdbcUrl = "", dbUser = "", dbPassword = "",
            pairingAdminToken = ADMIN_TOKEN,
            mediaDir = Files.createTempDirectory("vd-media"),
        )
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { module(config, db) }
        try {
            server.start(wait = false)
            val port = runBlocking { server.engine.resolvedConnectors().single().port }

            val client = HttpClient.newHttpClient()
            val health = client.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, health.statusCode())
            assertTrue(health.body().contains("\"schema_version\":\"2\""), health.body())

            val unauthorized = client.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/sync/changes")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(401, unauthorized.statusCode())
            assertTrue(unauthorized.body().contains("\"error\":\"unauthorized\""), unauthorized.body())
        } finally {
            server.stop(100, 500)
            db.close()
        }
    }
}
