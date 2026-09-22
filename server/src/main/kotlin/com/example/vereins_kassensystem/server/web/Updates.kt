package com.example.vereins_kassensystem.server.web

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Updates aus dem Git-Repo (Betrieb). Der Dienst kann sich nicht selbst neu bauen — das tut
 * der Updater-Container daneben (`server/deploy/updater`), der git und Docker sieht. Beide
 * reden über ein gemeinsames Verzeichnis, sonst über nichts: `settings.json` sagt, was der
 * Administrator will, `request` bittet um Suchen oder Installieren jetzt, `status.json`
 * ist, was der Updater weiß. Kein Netz zwischen den beiden, keine Zugangsdaten im Dienst.
 * Ohne das Verzeichnis (kein Updater aufgestellt) fehlt in der Verwaltung nur der Abschnitt.
 */
class Updates(private val dir: Path?, val runningVersion: String, val runningDate: String) {

    enum class Mode(val label: String, val hint: String) {
        MANUAL("Nur auf Knopfdruck", "Gesucht und installiert wird nur, wenn hier jemand klickt."),
        CHECK("Suchen, installieren auf Knopfdruck", "Der Updater sucht regelmäßig; was er findet, steht hier — installiert wird mit einem Klick."),
        AUTO("Suchen und installieren", "Was im Repo neu ist, wird von selbst eingespielt. Der Dienst startet dabei kurz neu; die Tablets merken nur eine Pause im Abgleich."),
    }

    @Serializable
    data class Settings(val mode: String = Mode.CHECK.name, val intervalMinutes: Int = 60) {
        val modeOrDefault: Mode get() = Mode.entries.firstOrNull { it.name == mode } ?: Mode.CHECK
    }

    /** Was der Updater zuletzt geschrieben hat; Felder, die eine ältere oder neuere Fassung nicht kennt, stören nicht. */
    @Serializable
    data class Status(
        val state: String = "unknown", val message: String = "", val updatedAt: String? = null, val checkedAt: String? = null,
        val branch: String? = null, val installed: String? = null,
        val latest: String? = null, val latestDate: String? = null, val latestMessage: String? = null, val behind: String? = null,
        val log: String = "",
    ) {
        val behindCount: Int get() = behind?.toIntOrNull() ?: 0
    }

    /** Steht der Updater bereit? Nur dann gibt es das Verzeichnis. */
    val available: Boolean get() = dir != null && Files.isDirectory(dir)

    fun settings(): Settings = read("settings.json") ?: Settings()

    fun status(): Status = read("status.json") ?: Status()

    /** Ein Update steht bereit, wenn der Updater etwas gefunden hat, das nicht der laufende Stand ist. */
    fun updateAvailable(status: Status = status()): Boolean {
        val latest = status.latest?.takeIf { it.isNotBlank() } ?: return false
        if (runningVersion == UNKNOWN) return status.behindCount > 0 || status.installed?.let { !sameCommit(it, latest) } ?: true
        return !sameCommit(runningVersion, latest)
    }

    fun saveSettings(mode: Mode, intervalMinutes: Int) {
        if (intervalMinutes !in 5..(7 * 24 * 60)) throw AccountProblem("Der Abstand muss zwischen 5 Minuten und einer Woche liegen.")
        write("settings.json", JSON.encodeToString(Settings.serializer(), Settings(mode.name, intervalMinutes)))
    }

    /** Jetzt suchen oder jetzt installieren — der Updater holt sich die Bitte binnen Sekunden ab. */
    fun request(action: String) {
        require(action == "check" || action == "install") { "unbekannte Anfrage: $action" }
        write("request", action + "\n")
    }

    private inline fun <reified T> read(name: String): T? {
        val file = dir?.resolve(name) ?: return null
        if (!Files.isRegularFile(file)) return null
        return runCatching { JSON.decodeFromString<T>(Files.readString(file)) }.getOrNull()
    }

    private fun write(name: String, content: String) {
        val target = checkNotNull(dir) { "kein Updater aufgestellt" }
        if (!Files.isDirectory(target)) throw AccountProblem("Kein Updater aufgestellt — der Dienst läuft nicht über server/deploy/compose.yaml mit dem Dienst „updater“.")
        val tmp = target.resolve("$name.tmp")
        Files.writeString(tmp, content)
        Files.move(tmp, target.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val UNKNOWN = "unbekannt"
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Kurze und lange Commit-Kennungen meinen dasselbe, wenn eine die andere beginnt. */
        fun sameCommit(a: String, b: String): Boolean {
            val x = a.trim().lowercase(); val y = b.trim().lowercase()
            if (x.isEmpty() || y.isEmpty()) return false
            return x.startsWith(y) || y.startsWith(x)
        }
    }
}
