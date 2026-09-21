package com.example.vereins_kassensystem.server

import java.net.URI
import java.net.URLDecoder
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Alles, was der Dienst von außen braucht, aus Umgebungsvariablen — so, wie die
 * Compose-Datei in Kapitel 7 der Spezifikation es vorsieht.
 *
 * `DATABASE_URL` im üblichen `postgres://user:passwort@host:5432/datenbank`-Format;
 * `DATABASE_PASSWORD` darf das Passwort aus der URL ersetzen, damit es nicht in einer
 * Adresse steht, die in Logs landet.
 */
class ServerConfig(
    val jdbcUrl: String,
    val dbUser: String,
    val dbPassword: String,
    /** Berechtigt zum Erzeugen von Kopplungscodes und zum Sperren von Geräten. */
    val pairingAdminToken: String,
    /** Ablage der Belegfotos. */
    val mediaDir: Path,
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val pairingCodeTtl: Duration = 10.minutes,
) {
    data class DatabaseUrl(val jdbcUrl: String, val user: String, val password: String)

    companion object {

        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServerConfig {
            val database = parseDatabaseUrl(
                env["DATABASE_URL"]
                    ?: error("DATABASE_URL fehlt, z. B. postgres://vereinsdeckel:geheim@db:5432/vereinsdeckel")
            )
            val adminToken = env["PAIRING_ADMIN_TOKEN"]?.takeIf { it.length >= 16 }
                ?: error("PAIRING_ADMIN_TOKEN fehlt oder ist kürzer als 16 Zeichen")
            return ServerConfig(
                jdbcUrl = database.jdbcUrl,
                dbUser = database.user,
                dbPassword = env["DATABASE_PASSWORD"] ?: database.password,
                pairingAdminToken = adminToken,
                mediaDir = Path.of(env["MEDIA_DIR"] ?: "media"),
                host = env["HOST"] ?: "0.0.0.0",
                port = env["PORT"]?.toIntOrNull() ?: 8080,
                pairingCodeTtl = env["PAIRING_CODE_MINUTES"]?.toIntOrNull()?.minutes ?: 10.minutes,
            )
        }

        /** `postgres://user:pass@host:port/db?params` → JDBC-Adresse plus Zugangsdaten. */
        fun parseDatabaseUrl(url: String): DatabaseUrl {
            val uri = URI(url)
            require(uri.scheme == "postgres" || uri.scheme == "postgresql") {
                "DATABASE_URL muss mit postgres:// beginnen"
            }
            val userInfo = uri.rawUserInfo ?: ""
            val user = URLDecoder.decode(userInfo.substringBefore(':'), Charsets.UTF_8)
            val password = URLDecoder.decode(userInfo.substringAfter(':', ""), Charsets.UTF_8)
            val port = if (uri.port == -1) 5432 else uri.port
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            return DatabaseUrl("jdbc:postgresql://${uri.host}:$port${uri.rawPath}$query", user, password)
        }
    }
}
