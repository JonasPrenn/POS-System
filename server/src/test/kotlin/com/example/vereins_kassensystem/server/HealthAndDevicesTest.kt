package com.example.vereins_kassensystem.server

import com.example.vereins_kassensystem.server.http.DeviceDto
import com.example.vereins_kassensystem.server.http.HealthResponse
import com.example.vereins_kassensystem.server.http.PairingCodeResponse
import com.example.vereins_kassensystem.server.http.RegisterRequest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthAndDevicesTest {

    @Test
    fun `health reports the schema without a token`() = serverTest { ctx ->
        val response = ctx.client.get("/v1/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<HealthResponse>()
        assertEquals("ok", body.status)
        assertEquals("1", body.schemaVersion)
        assertTrue(body.serverTime.endsWith("Z"), body.serverTime)
    }

    @Test
    fun `pairing codes need the admin token`() = serverTest { ctx ->
        assertEquals(HttpStatusCode.Unauthorized, ctx.client.post("/v1/admin/pairing-codes").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            ctx.client.post("/v1/admin/pairing-codes") { bearerAuth("falsch") }.status
        )
        val created = ctx.client.post("/v1/admin/pairing-codes") { bearerAuth(ADMIN_TOKEN) }
        assertEquals(HttpStatusCode.Created, created.status)
        val code = created.body<PairingCodeResponse>().code
        assertTrue(Regex("[A-Z2-9]{4}-[A-Z2-9]{4}").matches(code), code)
    }

    @Test
    fun `a pairing code registers one device and is then used up`() = serverTest { ctx ->
        val code = ctx.client.post("/v1/admin/pairing-codes") { bearerAuth(ADMIN_TOKEN) }.body<PairingCodeResponse>().code

        // Tolerant gegenüber Kleinschreibung und fehlendem Bindestrich.
        val typed = code.lowercase().replace("-", " ")
        val first = ctx.client.post("/v1/devices/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(typed, "iPad Garten", "ios"))
        }
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsTextSafe())

        val second = ctx.client.post("/v1/devices/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(code, "Theke", "android"))
        }
        assertEquals(HttpStatusCode.Conflict, second.status)

        val unknown = ctx.client.post("/v1/devices/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("AAAA-AAAA", "Theke", "android"))
        }
        assertEquals(HttpStatusCode.Conflict, unknown.status)
    }

    @Test
    fun `the device token opens the sync endpoints until the device is revoked`() = serverTest { ctx ->
        val device = ctx.pairDevice("Theke links")

        assertEquals(HttpStatusCode.Unauthorized, ctx.client.get("/v1/sync/changes").status)
        assertEquals(HttpStatusCode.Unauthorized, ctx.client.get("/v1/sync/changes") { bearerAuth("vd_dev_unsinn") }.status)
        assertEquals(HttpStatusCode.OK, ctx.client.get("/v1/sync/changes") { bearerAuth(device.token) }.status)

        val listed = ctx.client.get("/v1/admin/devices") { bearerAuth(ADMIN_TOKEN) }.body<List<DeviceDto>>()
        assertEquals(listOf("Theke links"), listed.map { it.label })
        assertEquals(device.deviceId, listed.single().id)
        assertTrue(listed.single().lastSeenAt != null, "last_seen_at nach dem ersten Abgleich")

        assertEquals(
            HttpStatusCode.NoContent,
            ctx.client.post("/v1/admin/devices/${device.deviceId}/revoke") { bearerAuth(ADMIN_TOKEN) }.status
        )
        assertEquals(HttpStatusCode.Unauthorized, ctx.client.get("/v1/sync/changes") { bearerAuth(device.token) }.status)
    }
}
