package com.example.vereins_kassensystem.server

import java.net.URI
import java.net.URLDecoder
import java.nio.file.Path
import java.time.ZoneId
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
    /** „Heute" und die Monatsgrenzen der Verwaltung: die Zeit der Verbindung, nicht die des Servers. */
    val zone: ZoneId = ZoneId.of("Europe/Vienna"),
    /**
     * Steht ein Proxy davor (Caddy in `deploy/compose.yaml`), kommt die Adresse des Anrufers aus
     * `X-Forwarded-For`. Nur setzen, wenn der Dienst ausschließlich über den Proxy erreichbar ist —
     * sonst kann jeder den Kopf selbst schreiben.
     */
    val trustProxy: Boolean = false,
    /** Nur zum Ausprobieren ohne HTTPS: Sitzungscookies ohne `Secure`. Im Betrieb nie setzen. */
    val insecureCookies: Boolean = false,
    /** Das Verzeichnis, über das Dienst und Updater reden (web/Updates.kt); null ohne Updater. */
    val updatesDir: Path? = null,
    /** Der Commit, aus dem gebaut wurde — gesetzt beim Bauen des Images. */
    val version: String = "unbekannt",
    val versionDate: String = "",
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
                zone = env["VEREIN_ZONE"]?.let(ZoneId::of) ?: ZoneId.of("Europe/Vienna"),
                trustProxy = env["TRUST_PROXY"] == "true",
                insecureCookies = env["WEB_INSECURE_COOKIES"] == "true",
                updatesDir = env["UPDATES_DIR"]?.takeIf { it.isNotBlank() }?.let(Path::of),
                version = env["VEREINSDECKEL_VERSION"]?.takeIf { it.isNotBlank() } ?: "unbekannt",
                versionDate = env["VEREINSDECKEL_VERSION_DATE"].orEmpty(),
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
