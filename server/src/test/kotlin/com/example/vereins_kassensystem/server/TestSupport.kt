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
import com.example.vereins_kassensystem.server.payments.Checkout
import com.example.vereins_kassensystem.server.payments.CheckoutRequest
import com.example.vereins_kassensystem.server.payments.OnlinePayments
import com.example.vereins_kassensystem.server.payments.PaymentAccount
import com.example.vereins_kassensystem.server.payments.PaymentProblem
import com.example.vereins_kassensystem.server.tenancy.Databases
import com.example.vereins_kassensystem.server.tenancy.TenantDirectory
import com.example.vereins_kassensystem.server.web.FakeMailbox
import com.example.vereins_kassensystem.server.web.OutboxMailer
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

    fun freshDatabase(): Database = fresh().second

    /** Name und Datenbank des ersten Vereins — System und weitere Vereine legt [databases] daneben an. */
    fun fresh(): Pair<String, Database> {
        val name = "vd_test_${counter.incrementAndGet()}"
        instance.postgresDatabase.connection.use { c ->
            c.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        return name to Database("jdbc:postgresql://localhost:${instance.port}/$name", "postgres", "postgres", poolSize = 4)
            .also { it.migrate() }
    }

    fun url(name: String) = "jdbc:postgresql://localhost:${instance.port}/$name"

    fun databases(firstName: String) = Databases(url(firstName), "postgres", "postgres")

    /** Wie ein Datenbankbenutzer ohne CREATEDB: Die Systemdatenbank lässt sich nicht anlegen. */
    fun withoutCreate(firstName: String) = object : Databases(url(firstName), "postgres", "postgres") {
        override fun create(name: String) = throw java.sql.SQLException("permission denied to create database", "42501")
    }
}

const val ADMIN_TOKEN = "test-admin-token-1234"

/** Die höchste Migrationsnummer im Klassenpfad — was /v1/health als Schemaversion melden muss. */
val SCHEMA_VERSION: String by lazy {
    val dir = checkNotNull(TestPostgres::class.java.getResource("/db/migration")) { "db/migration fehlt" }
    java.io.File(dir.toURI()).list()!!.mapNotNull { Regex("V(\\d+)__").find(it)?.groupValues?.get(1)?.toInt() }.max().toString()
}

/** [db] ist die Datenbank des ersten Vereins — die von vor den Vereinen. */
class TestContext(val db: Database, val client: HttpClient, val outbox: OutboxMailer, val mailbox: FakeMailbox, val directory: TenantDirectory, val payments: FakePayments)

/**
 * SumUp, nachgebaut: legt Checkouts an, kennt genau einen gültigen Schlüssel, und der Test
 * entscheidet, wann bezahlt ist ([pay]) — oder dass SumUp gerade nicht antwortet ([down]).
 */
class FakePayments : OnlinePayments {
    val created = java.util.concurrent.CopyOnWriteArrayList<CheckoutRequest>()
    val checkouts = java.util.concurrent.ConcurrentHashMap<String, Checkout>()
    var methods = listOf("apple_pay", "google_pay")
    @Volatile var down = false

    private fun check(account: PaymentAccount) {
        if (down) throw PaymentProblem("SumUp ist gerade nicht erreichbar.", retry = true)
        if (account.apiKey != KEY) throw PaymentProblem("SumUp kennt diesen API-Schlüssel nicht.")
    }

    override fun create(account: PaymentAccount, request: CheckoutRequest): Checkout {
        check(account)
        val id = "chk-${checkouts.size + 1}"
        created += request
        return Checkout(id, request.reference.toString(), Checkout.Status.PENDING, request.amount, "EUR", account.merchantCode, "https://checkout.sumup.com/pay/$id", null)
            .also { checkouts[id] = it }
    }

    override fun fetch(account: PaymentAccount, checkoutId: String): Checkout {
        check(account)
        return checkouts[checkoutId] ?: throw PaymentProblem("SumUp kennt diesen Checkout nicht.")
    }

    override fun methods(account: PaymentAccount): List<String> {
        check(account)
        return methods
    }

    /** Das Mitglied hat bezahlt — oder SumUp meldet etwas anderes, als angelegt war. */
    fun pay(checkoutId: String, amount: java.math.BigDecimal? = null, status: Checkout.Status = Checkout.Status.PAID) {
        val c = checkouts.getValue(checkoutId)
        checkouts[checkoutId] = Checkout(c.id, c.reference, status, amount ?: c.amount, c.currency, c.merchantCode, c.payUrl, "TX42")
    }

    companion object {
        const val KEY = "sup_sk_test_schluessel"
    }
}

/** Startet den Dienst wie in Main.kt, nur ohne Netz, und räumt danach auf. */
fun serverTest(insecureCookies: Boolean = false, updatesDir: java.nio.file.Path? = null, withSystem: Boolean = true, block: suspend ApplicationTestBuilder.(TestContext) -> Unit) = testApplication {
    val (name, db) = TestPostgres.fresh()
    val config = ServerConfig(
        jdbcUrl = "", dbUser = "", dbPassword = "",
        pairingAdminToken = ADMIN_TOKEN,
        mediaDir = Files.createTempDirectory("vd-media"),
        pairingCodeTtl = 10.minutes,
        // Der Testclient spricht http; ein Secure-Cookie käme bei ihm nie wieder an.
        insecureCookies = insecureCookies,
        updatesDir = updatesDir,
        version = "1234567abcde", versionDate = "2026-09-22T10:00:00+02:00",
    )
    val outbox = OutboxMailer()
    val mailbox = FakeMailbox()
    val payments = FakePayments()
    val directory = TenantDirectory.open(config, db, if (withSystem) TestPostgres.databases(name) else TestPostgres.withoutCreate(name), outbox, mailbox, payments)
    // Ohne den Zehn-Minuten-Abruf: Der Test ruft den Posteingang selbst ab.
    application { module(config, directory, pollMailbox = false) }
    val client = createClient {
        install(ContentNegotiation) { json(WireJson) }
    }
    try {
        block(TestContext(db, client, outbox, mailbox, directory, payments))
    } finally {
        directory.close()
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
