package com.example.vereins_kassensystem

import com.example.vereins_kassensystem.platform.Ids
import com.example.vereins_kassensystem.sync.PushOperation
import com.example.vereins_kassensystem.sync.PushRequest
import com.example.vereins_kassensystem.sync.PushResponse
import com.example.vereins_kassensystem.sync.WireJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdsAndWireTest {

    @Test
    fun `ids are version 7 uuids in lower case`() {
        val id = Ids.new()
        assertTrue(Ids.isValid(id), id)
        assertEquals('7', id[14], "Versionsziffer")
        assertTrue(id[19] in "89ab", "Variante: ${id[19]}")
        assertEquals(id.lowercase(), id)
    }

    @Test
    fun `ids carry the given time so old rows sort before new ones`() {
        val march = Ids.at(1_772_000_000_000)
        val september = Ids.at(1_789_000_000_000)
        assertTrue(march < september, "$march sollte vor $september sortieren")
        assertEquals(Ids.at(1_789_000_000_000).substring(0, 13), september.substring(0, 13), "gleiche Zeit, gleicher Anfang")
        assertTrue(Ids.isValid(Ids.at(-5)), "negative Zeit zählt als null")
    }

    @Test
    fun `validation rejects what is not a uuid`() {
        assertTrue(Ids.isValid("00000000-0000-0000-0000-000000000000"))
        assertTrue(Ids.isValid("018F2B6C-7D1E-7A00-8000-ABCDEF012345"))
        assertFalse(Ids.isValid("bar-1"))
        assertFalse(Ids.isValid("018f2b6c7d1e7a008000abcdef012345"))
        assertFalse(Ids.isValid("018f2b6c-7d1e-7a00-8000-abcdef01234g"))
    }

    @Test
    fun `the wire format is snake case and leaves nulls out`() {
        val request = PushRequest(
            operations = listOf(
                PushOperation(
                    clientChangeId = "018f-1111", entity = "products", op = "update",
                    baseUpdatedAt = "2026-09-15T17:02:00.000Z",
                    row = buildJsonObject { put("id", "018e-cccc"); put("price", "4.40") },
                )
            )
        )
        val text = WireJson.encodeToString(PushRequest.serializer(), request)
        assertTrue("\"client_change_id\":\"018f-1111\"" in text, text)
        assertTrue("\"base_updated_at\"" in text, text)
        assertFalse("device_id" in text, "null wird ausgelassen: $text")
        // Die Schlüssel der Zeile sind Spaltennamen und bleiben, wie sie sind.
        assertTrue("\"price\":\"4.40\"" in text, text)

        val response = WireJson.decodeFromString(
            PushResponse.serializer(),
            """{"results":[{"client_change_id":"018f-1111","status":"applied","seq":7,"kommt_spaeter":true}],"next_since":7}"""
        )
        assertEquals(7L, response.results.single().seq)
        assertNull(response.results.single().current)
    }
}
