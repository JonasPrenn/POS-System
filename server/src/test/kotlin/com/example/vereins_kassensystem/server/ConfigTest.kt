package com.example.vereins_kassensystem.server

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.minutes

class ConfigTest {

    @Test
    fun `a postgres url becomes a jdbc url plus credentials`() {
        val parsed = ServerConfig.parseDatabaseUrl("postgres://vereinsdeckel:p%40ss@db:5433/deckel?sslmode=require")
        assertEquals("jdbc:postgresql://db:5433/deckel?sslmode=require", parsed.jdbcUrl)
        assertEquals("vereinsdeckel", parsed.user)
        assertEquals("p@ss", parsed.password, "Prozentkodierung wird aufgelöst")
    }

    @Test
    fun `the port defaults to 5432 and the password may be empty`() {
        val parsed = ServerConfig.parseDatabaseUrl("postgresql://vereinsdeckel@db/deckel")
        assertEquals("jdbc:postgresql://db:5432/deckel", parsed.jdbcUrl)
        assertEquals("", parsed.password)
    }

    @Test
    fun `the environment is read with overrides and defaults`() {
        val config = ServerConfig.fromEnvironment(
            mapOf(
                "DATABASE_URL" to "postgres://vereinsdeckel:aus-der-url@db/deckel",
                "DATABASE_PASSWORD" to "aus-der-umgebung",
                "PAIRING_ADMIN_TOKEN" to "0123456789abcdef",
                "PORT" to "9090",
            )
        )
        assertEquals("aus-der-umgebung", config.dbPassword, "DATABASE_PASSWORD schlägt das Passwort in der URL")
        assertEquals(9090, config.port)
        assertEquals("0.0.0.0", config.host)
        assertEquals(Path.of("media"), config.mediaDir)
        assertEquals(10.minutes, config.pairingCodeTtl)
    }

    @Test
    fun `missing or weak settings fail at startup and not later`() {
        assertFailsWith<IllegalStateException> { ServerConfig.fromEnvironment(emptyMap()) }
        assertFailsWith<IllegalStateException> {
            ServerConfig.fromEnvironment(mapOf("DATABASE_URL" to "postgres://a:b@db/c", "PAIRING_ADMIN_TOKEN" to "kurz"))
        }
        assertFailsWith<IllegalArgumentException> { ServerConfig.parseDatabaseUrl("mysql://a:b@db/c") }
    }
}
