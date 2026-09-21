package com.example.vereins_kassensystem.server

import com.example.vereins_kassensystem.sync.ReceiptUploadResponse
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaTest {

    @Test
    fun `a receipt photo round-trips under its key`() = serverTest { ctx ->
        val device = ctx.pairDevice()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)

        val upload = ctx.client.post("/v1/media/receipts") {
            bearerAuth(device.token)
            contentType(ContentType.Image.JPEG)
            setBody(jpeg)
        }
        assertEquals(HttpStatusCode.Created, upload.status, upload.bodyAsTextSafe())
        val key = upload.body<ReceiptUploadResponse>().photoKey
        assertTrue(key.endsWith(".jpg"), key)

        val download = ctx.client.get("/v1/media/receipts/$key") { bearerAuth(device.token) }
        assertEquals(HttpStatusCode.OK, download.status)
        assertEquals(ContentType.Image.JPEG, download.contentType()?.withoutParameters())
        assertContentEquals(jpeg, download.readRawBytes())
    }

    @Test
    fun `unknown keys and wrong types are refused`() = serverTest { ctx ->
        val device = ctx.pairDevice()
        assertEquals(HttpStatusCode.NotFound, ctx.client.get("/v1/media/receipts/${newId()}.jpg") { bearerAuth(device.token) }.status)
        assertEquals(HttpStatusCode.NotFound, ctx.client.get("/v1/media/receipts/..%2F..%2Fetc%2Fpasswd") { bearerAuth(device.token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, ctx.client.get("/v1/media/receipts/${newId()}.jpg").status)

        val text = ctx.client.post("/v1/media/receipts") {
            bearerAuth(device.token)
            contentType(ContentType.Text.Plain)
            setBody("kein Bild")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, text.status)

        val empty = ctx.client.post("/v1/media/receipts") {
            bearerAuth(device.token)
            contentType(ContentType.Image.PNG)
            setBody(ByteArray(0))
        }
        assertEquals(HttpStatusCode.BadRequest, empty.status)
    }
}
