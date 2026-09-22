package com.example.vereins_kassensystem

import com.example.vereins_kassensystem.data.sync.SyncEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Welche Serveradresse die App annimmt: HTTPS immer, HTTP nur dort, wo das Internet nicht hinkommt. */
class ServerAddressTest {

    @Test
    fun `https is always fine and a trailing slash goes`() {
        assertEquals("https://deckel.verein.at", SyncEngine.normalizeUrl(" https://deckel.verein.at/ "))
    }

    @Test
    fun `plain http only for the own machine and the private network`() {
        for (ok in listOf("http://localhost:8080", "http://127.0.0.1:8080", "http://10.0.2.2:8080", "http://10.23.0.197:8080", "http://192.168.1.20:8080", "http://172.16.4.2", "http://172.31.255.1:8080", "http://kassier-laptop.local:8080")) {
            assertEquals(ok, SyncEngine.normalizeUrl(ok), ok)
        }
        for (no in listOf("http://deckel.verein.at", "http://172.32.0.1:8080", "http://8.8.8.8", "http://11.0.0.1", "deckel.verein.at", "ftp://10.0.0.1", "http://10.0.0.1/pfad")) {
            assertNull(SyncEngine.normalizeUrl(no), no)
        }
    }
}
