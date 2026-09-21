package com.example.vereins_kassensystem.server

import com.example.vereins_kassensystem.server.db.Database
import com.example.vereins_kassensystem.server.http.PairingCodeResponse
import com.example.vereins_kassensystem.sync.PushOperation
import com.example.vereins_kassensystem.sync.PushRequest
import com.example.vereins_kassensystem.sync.PushResponse
import com.example.vereins_kassensystem.sync.RegisterRequest
import com.example.vereins_kassensystem.sync.RegisterResponse
import com.example.vereins_kassensystem.sync.WireJson
import com.example.vereins_kassensystem.server.http.module
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * Ein eingebetteter PostgreSQL je Testlauf, eine frische Datenbank je Test. Das Schema
 * lebt von Triggern und einer Sicht — die lassen sich nur gegen echtes PostgreSQL prüfen.
 */
object TestPostgres {

    private val instance: EmbeddedPostgres by lazy {
        EmbeddedPostgres.builder().start().also { pg ->
            Runtime.getRuntime().addShutdownHook(Thread { pg.close() })
        }
    }

    private val counter = AtomicInteger()

    fun freshDatabase(): Database {
        val name = "vd_test_${counter.incrementAndGet()}"
        instance.postgresDatabase.connection.use { c ->
            c.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        return Database("jdbc:postgresql://localhost:${instance.port}/$name", "postgres", "postgres", poolSize = 4)
            .also { it.migrate() }
    }
}

const val ADMIN_TOKEN = "test-admin-token-1234"

class TestContext(val db: Database, val client: HttpClient)

/** Startet den Dienst wie in Main.kt, nur ohne Netz, und räumt danach auf. */
fun serverTest(block: suspend ApplicationTestBuilder.(TestContext) -> Unit) = testApplication {
    val db = TestPostgres.freshDatabase()
    val config = ServerConfig(
        jdbcUrl = "", dbUser = "", dbPassword = "",
        pairingAdminToken = ADMIN_TOKEN,
        mediaDir = Files.createTempDirectory("vd-media"),
        pairingCodeTtl = 10.minutes,
    )
    application { module(config, db) }
    val client = createClient {
        install(ContentNegotiation) { json(WireJson) }
    }
    try {
        block(TestContext(db, client))
    } finally {
        db.close()
    }
}

/** Kopplung wie in 5.2: Administrator erzeugt den Code, das Gerät tauscht ihn ein. */
suspend fun TestContext.pairDevice(label: String = "Theke", platform: String = "android"): RegisterResponse {
    val code = client.post("/v1/admin/pairing-codes") { bearerAuth(ADMIN_TOKEN) }.body<PairingCodeResponse>()
    val response = client.post("/v1/devices/register") {
        contentType(ContentType.Application.Json)
        setBody(RegisterRequest(code.code, label, platform))
    }
    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsTextSafe())
    return response.body()
}

suspend fun TestContext.push(token: String, vararg operations: PushOperation): PushResponse {
    val response = pushRaw(token, *operations)
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsTextSafe())
    return response.body()
}

suspend fun TestContext.pushRaw(token: String, vararg operations: PushOperation): HttpResponse =
    client.post("/v1/sync/push") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(PushRequest(operations = operations.toList()))
    }

fun insertOp(entity: String, row: JsonObject, changeId: String = newId()) =
    PushOperation(clientChangeId = changeId, entity = entity, op = "insert", row = row)

fun updateOp(entity: String, row: JsonObject, base: String? = null, changeId: String = newId()) =
    PushOperation(clientChangeId = changeId, entity = entity, op = "update", baseUpdatedAt = base, row = row)

fun deleteOp(entity: String, row: JsonObject, base: String? = null, changeId: String = newId()) =
    PushOperation(clientChangeId = changeId, entity = entity, op = "delete", baseUpdatedAt = base, row = row)

fun newId(): String = UUID.randomUUID().toString()

suspend fun HttpResponse.bodyAsTextSafe(): String = runCatching { bodyAsText() }.getOrDefault("")
