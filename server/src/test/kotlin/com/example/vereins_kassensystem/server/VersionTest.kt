package com.example.vereins_kassensystem.server

import com.example.vereins_kassensystem.AppVersion
import com.example.vereins_kassensystem.sync.HealthResponse
import io.ktor.client.call.body
import io.ktor.client.request.get
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Die Version steht genau einmal (VERSION im Repo) — und überall steht dieselbe. */
class VersionTest {

    private val root: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("VERSION")) }

    @Test
    fun `the version in the repo is the one the code carries`() {
        val text = Files.readString(root.resolve("VERSION")).trim()
        val number = text.substringBefore('-')
        val stage = text.substringAfter('-', "")
        assertTrue(Regex("\\d+\\.\\d+\\.\\d+").matches(number), "VERSION ist x.y.z oder x.y.z-beta, nicht '$text'")
        assertTrue(stage == "" || stage == "beta", "als Zusatz gibt es nur -beta")
        assertEquals(number, AppVersion.NUMBER); assertEquals(stage, AppVersion.STAGE)
        assertEquals(if (stage.isEmpty()) number else "$number Beta", AppVersion.LABEL)
        assertEquals(stage.isNotEmpty(), AppVersion.isBeta)
        // Eine Beta liegt unter ihrer Freigabe, die Freigabe unter der nächsten Beta.
        assertEquals(if (stage.isEmpty()) 9 else 0, AppVersion.CODE % 10)
        val (a, b, c) = number.split('.').map { it.toInt() }
        assertEquals(a * 100_000 + b * 1_000 + c * 10 + (if (stage.isEmpty()) 9 else 0), AppVersion.CODE)
        // iOS liest sie aus einer Datei, die docs/tools/version.sh mitschreibt.
        val xcconfig = Files.readString(root.resolve("iosApp/Configuration/Version.xcconfig"))
        assertContains(xcconfig, "MARKETING_VERSION = $number\n"); assertContains(xcconfig, "CURRENT_PROJECT_VERSION = ${AppVersion.CODE}\n")
    }

    @Test
    fun `the health endpoint says which version answers`() = serverTest { ctx ->
        assertEquals(AppVersion.LABEL, ctx.client.get("/v1/health").body<HealthResponse>().version)
    }
}
