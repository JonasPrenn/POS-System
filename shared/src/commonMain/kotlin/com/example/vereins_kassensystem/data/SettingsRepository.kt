package com.example.vereins_kassensystem.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.example.vereins_kassensystem.MinimumVersion
import com.example.vereins_kassensystem.platform.SettingsStore
import com.example.vereins_kassensystem.ui.theme.ClubIdentity
import com.example.vereins_kassensystem.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Die Einstellungen der App: nach außen Ströme, darunter Schlüssel und Werte.
 *
 * Vorher hing die Klasse an Context, DataStore und EncryptedSharedPreferences. Jetzt
 * liest sie aus [SettingsStore], den die Plattform stellt — unter Android weiterhin
 * DataStore und verschlüsselte Preferences, unter iOS NSUserDefaults und Schlüsselbund.
 *
 * Der Store kennt keine Beobachtung, deshalb hält das Repository alle Werte als einen
 * Schnappschuss in einem StateFlow: einmal beim ersten Zugriff gelesen, bei jedem
 * Schreiben fortgeschrieben. Ein Bildschirm sieht damit nie halb geladene Werte, und
 * die Ströme nach außen verhalten sich wie vorher.
 */
class SettingsRepository(private val store: SettingsStore) {

    private data class Snapshot(
        val themeMode: ThemeMode,
        val clubName: String,
        val clubAccent: Color?,
        val backupDestination: String?,
        val autoBackupEnabled: Boolean,
        val sumUpAffiliateKey: String,
        val apiBaseUrl: String?,
        val lastBackupAt: Long?,
        val hiddenProducts: Set<String>,
        val minAppVersion: String
    )

    private val snapshot = MutableStateFlow<Snapshot?>(null)
    private val loading = Mutex()

    private suspend fun current(): Snapshot {
        snapshot.value?.let { return it }
        return loading.withLock {
            snapshot.value ?: readAll().also { snapshot.value = it }
        }
    }

    private suspend fun readAll() = Snapshot(
        themeMode = ThemeMode.fromName(store.getString(KEY_THEME_MODE)),
        clubName = store.getString(KEY_CLUB_NAME).orEmpty(),
        clubAccent = store.getString(KEY_CLUB_ACCENT)?.toIntOrNull()?.let { Color(it) },
        backupDestination = store.getString(KEY_BACKUP_DESTINATION),
        autoBackupEnabled = store.getString(KEY_AUTO_BACKUP)?.toBoolean() ?: false,
        sumUpAffiliateKey = store.getSecret(KEY_SUMUP_AFFILIATE_KEY).orEmpty(),
        apiBaseUrl = store.getString(KEY_API_BASE_URL),
        lastBackupAt = store.getString(KEY_LAST_BACKUP_AT)?.toLongOrNull(),
        hiddenProducts = store.getString(KEY_HIDDEN_PRODUCTS)?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty(),
        minAppVersion = store.getString(KEY_MIN_APP_VERSION) ?: MinimumVersion.NONE
    )

    private val settings: Flow<Snapshot> = snapshot.onStart { current() }.filterNotNull()

    /**
     * Light/dark preference. A tablet mounted behind the bar wants to be pinned to dark
     * for the evening whatever the system is doing, so this is a real setting rather
     * than a straight follow of [ThemeMode.SYSTEM].
     */
    val themeMode: Flow<ThemeMode> = settings.map { it.themeMode }.distinctUntilChanged()

    /**
     * The club's own name and colour. Stored per install because the app is built for any
     * Verein — see [ClubIdentity] for why the accent stays out of the semantic roles.
     */
    val clubIdentity: Flow<ClubIdentity> = settings
        .map { ClubIdentity(name = it.clubName, accent = it.clubAccent ?: ClubIdentity().accent) }
        .distinctUntilChanged()

    /**
     * Der plattformeigene Verweis auf den Sicherungsordner — unter Android eine Baum-URI,
     * unter iOS ein Lesezeichen als Base64. Nur die Plattform kann ihn deuten.
     */
    val backupDestination: Flow<String?> = settings.map { it.backupDestination }.distinctUntilChanged()
    val autoBackupEnabled: Flow<Boolean> = settings.map { it.autoBackupEnabled }.distinctUntilChanged()
    val sumUpAffiliateKey: Flow<String> = settings.map { it.sumUpAffiliateKey }.distinctUntilChanged()

    /** Adresse des Servers für den Mehrgerätebetrieb; null, solange keiner eingerichtet ist. */
    val apiBaseUrl: Flow<String?> = settings.map { it.apiBaseUrl }.distinctUntilChanged()

    /** Zeitpunkt der letzten erfolgreichen Sicherung; gerätelokal, weil die Sicherung es ist. */
    val lastBackupAt: Flow<Long?> = settings.map { it.lastBackupAt }.distinctUntilChanged()

    private suspend fun write(persist: suspend () -> Unit, change: (Snapshot) -> Snapshot) {
        current()
        persist()
        snapshot.update { it?.let(change) }
    }

    suspend fun setThemeMode(mode: ThemeMode) =
        write({ store.putString(KEY_THEME_MODE, mode.name) }) { it.copy(themeMode = mode) }

    suspend fun setClubName(name: String) =
        write({ store.putString(KEY_CLUB_NAME, name) }) { it.copy(clubName = name) }

    suspend fun setClubAccent(color: Color) =
        // Stored as a plain ARGB int; Color is a value class over a Long with the colour
        // space packed in, which is not something to persist.
        write({ store.putString(KEY_CLUB_ACCENT, color.toArgb().toString()) }) { it.copy(clubAccent = color) }

    suspend fun setBackupDestination(ref: String?) =
        write({ if (ref == null) store.remove(KEY_BACKUP_DESTINATION) else store.putString(KEY_BACKUP_DESTINATION, ref) }) {
            it.copy(backupDestination = ref)
        }

    suspend fun setAutoBackupEnabled(enabled: Boolean) =
        write({ store.putString(KEY_AUTO_BACKUP, enabled.toString()) }) { it.copy(autoBackupEnabled = enabled) }

    suspend fun saveSumUpAffiliateKey(key: String) =
        write({ store.putSecret(KEY_SUMUP_AFFILIATE_KEY, key) }) { it.copy(sumUpAffiliateKey = key) }

    suspend fun setApiBaseUrl(url: String?) =
        write({ if (url == null) store.remove(KEY_API_BASE_URL) else store.putString(KEY_API_BASE_URL, url) }) {
            it.copy(apiBaseUrl = url)
        }

    /**
     * Das Gerätetoken für den Server. Ein Zugangsgeheimnis: liegt im Schlüsselbund, nie in
     * einer Sicherung und nie in einem Sync-Payload. Ohne Strom nach außen, weil es niemand
     * anzeigen soll — der Abgleich fragt danach, wenn er es braucht.
     */
    suspend fun deviceToken(): String? = store.getSecret(KEY_DEVICE_TOKEN)?.takeIf { it.isNotBlank() }

    /** Der Schlüsselbund kennt kein Löschen; ein leerer Wert gilt als keiner. */
    suspend fun setDeviceToken(token: String?) = store.putSecret(KEY_DEVICE_TOKEN, token.orEmpty())

    /**
     * Im Verkauf ausgeblendete Produkte — nur auf diesem Gerät (Entscheidung vom 30. September
     * 2026): Die Küche blendet die Getränke aus, die Theke das Essen. Deshalb eine Einstellung
     * des Geräts und keine Spalte am Produkt; nichts davon geht über den Draht, und die
     * Sicherung (die Datenbank) enthält es nicht.
     */
    val hiddenProducts: Flow<Set<String>> = settings.map { it.hiddenProducts }.distinctUntilChanged()

    suspend fun setProductHidden(productId: String, hidden: Boolean) {
        val next = current().hiddenProducts.let { if (hidden) it + productId else it - productId }
        write({ store.putString(KEY_HIDDEN_PRODUCTS, next.joinToString(",")) }) { it.copy(hiddenProducts = next) }
    }

    /**
     * Die älteste App-Version, mit der der Verein noch kassieren lässt — gesetzt vom Hauptadmin des
     * Servers, gekommen mit dem Abgleich. Darunter zeigt die App nur noch, dass sie aktualisiert
     * werden muss ([MinimumVersion]); gemerkt wird sie, damit die Sperre auch ohne Netz gilt.
     */
    val minAppVersion: Flow<String> = settings.map { it.minAppVersion }.distinctUntilChanged()

    suspend fun setMinAppVersion(version: String) =
        write({ store.putString(KEY_MIN_APP_VERSION, version) }) { it.copy(minAppVersion = version) }

    suspend fun setLastBackupAt(at: Long) =
        write({ store.putString(KEY_LAST_BACKUP_AT, at.toString()) }) { it.copy(lastBackupAt = at) }

    /**
     * Was die Verwaltung für alle Tablets setzt (Tabelle `device_settings`): Vereinsname, Vereinsfarbe,
     * SumUp-Schlüssel, ob täglich gesichert wird. Kommt mit dem Abgleich, landet wo es immer lag —
     * der Schlüssel im Schlüsselbund. Einen Schlüssel, den diese App-Version nicht kennt, übergeht sie.
     */
    suspend fun applyFromServer(key: String, value: String) {
        when (key) {
            "club_name" -> setClubName(value)
            "club_accent" -> parseHex(value)?.let { setClubAccent(it) }
            "sumup_affiliate_key" -> saveSumUpAffiliateKey(value)
            "tablet_auto_backup" -> setAutoBackupEnabled(value == "1")
            KEY_MIN_APP_VERSION -> setMinAppVersion(value.trim())
        }
    }

    private fun parseHex(hex: String): Color? = hex.trim().removePrefix("#")
        .takeIf { it.length == 6 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
        ?.let { Color(0xFF000000L or it.toLong(16)) }

    private companion object {
        // Die Namen stammen aus der DataStore-Fassung und bleiben, damit ein bestehendes
        // Android-Gerät seine Einstellungen nach dem Update behält.
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_CLUB_NAME = "club_name"
        const val KEY_CLUB_ACCENT = "club_accent"
        const val KEY_BACKUP_DESTINATION = "backup_uri"
        const val KEY_AUTO_BACKUP = "auto_backup_enabled"
        const val KEY_SUMUP_AFFILIATE_KEY = "sumup_affiliate_key"
        const val KEY_API_BASE_URL = "api_base_url"
        const val KEY_LAST_BACKUP_AT = "last_backup_at"
        const val KEY_DEVICE_TOKEN = "sync_device_token"
        const val KEY_HIDDEN_PRODUCTS = "hidden_products"
        /** Gleich benannt wie die Zeile in `device_settings` (VereinSettings auf dem Server). */
        const val KEY_MIN_APP_VERSION = "min_app_version"
    }
}
