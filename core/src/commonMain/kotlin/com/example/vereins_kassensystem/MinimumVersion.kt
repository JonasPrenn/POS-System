package com.example.vereins_kassensystem

/**
 * Die Mindestversion der App: gesetzt vom Hauptadmin in der Systemverwaltung des Servers,
 * an die Tablets als `device_settings.min_app_version`. Darunter sperrt sich die App; der
 * Abgleich läuft weiter, damit nichts liegen bleibt.
 *
 * Verglichen wird wie [AppVersion.CODE] — major·100000 + minor·1000 + patch·10, die Freigabe
 * +9. Eine Mindestversion 1.3.0 lässt damit die Beta von 1.3.0 zu, nicht aber 1.2.x.
 */
object MinimumVersion {

    /** Keine Sperre: so steht es, bis der Hauptadmin etwas anderes setzt. */
    const val NONE = "0.0.0"

    /** Der Code einer Mindestversion der Form x.y.z, oder null, wenn es keine ist. */
    fun codeOf(version: String): Int? {
        val parts = Regex("""(\d{1,3})\.(\d{1,2})\.(\d{1,2})""").matchEntire(version.trim())?.groupValues ?: return null
        return parts[1].toInt() * 100_000 + parts[2].toInt() * 1_000 + parts[3].toInt() * 10
    }

    /** Sperrt [version] eine App mit [code]? Was keine Version ist, sperrt nichts — lieber kassieren als stillstehen. */
    fun blocks(version: String, code: Int = AppVersion.CODE): Boolean = codeOf(version)?.let { code < it } ?: false
}
