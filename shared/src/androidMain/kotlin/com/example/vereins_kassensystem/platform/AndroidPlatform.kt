package com.example.vereins_kassensystem.platform

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Hält den Application-Context für die Teile des geteilten Moduls, die ihn brauchen.
 *
 * Ein globaler Context ist nichts, was man leichtfertig einführt. Hier ist er trotzdem
 * die ehrlichere Lösung: Die Alternative wäre, den Context durch Datenbank-Bau,
 * Einstellungen und Sicherung durchzureichen und damit eine Android-Eigenheit quer durch
 * Signaturen zu tragen, die auf iOS keinen Sinn ergeben. Gehalten wird ausschließlich
 * die Application, nicht eine Activity — das ist der eine Context, der kein Leck ist.
 */
object AndroidContextHolder {
    private var appRef: Application? = null

    val application: Application
        get() = appRef ?: error(
            "AndroidContextHolder wurde nicht gesetzt. KassenApplication.onCreate() " +
                "muss install(this) aufrufen, bevor irgendetwas die Datenbank anfasst."
        )

    fun install(application: Application) {
        appRef = application
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class AndroidSettingsStore(private val context: Context) : SettingsStore {

    /**
     * Zugangsdaten liegen getrennt und verschlüsselt.
     *
     * DataStore schreibt im Klartext in den App-Ordner. Für Vereinsname und Farbwahl ist
     * das in Ordnung; für den SumUp-Key und das Gerätetoken nicht — beides taucht sonst
     * in jeder Sicherung und in jedem adb-Backup auf.
     */
    private val secrets by lazy {
        // Derselbe Dateiname und derselbe Schlüsselalias wie in der alten Fassung mit
        // MasterKeys.AES256_GCM_SPEC — so bleibt der einmal eingetragene SumUp-Key nach
        // dem Update lesbar, statt dass der Kassier ihn neu abtippen muss.
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "secure_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Liest nach Namen, nicht nach Typ. Die alte Fassung hat `auto_backup_enabled` als
     * Boolean und `club_accent` als Int abgelegt; ein `stringPreferencesKey` darauf würde
     * beim Lesen mit ClassCastException scheitern. Der Wert wird deshalb aus der Map
     * geholt und als Text zurückgegeben — "true" und "-16777216" versteht das Repository.
     * Beim nächsten Schreiben ersetzt der String-Eintrag den alten, weil DataStore
     * Schlüssel nur am Namen vergleicht.
     */
    override suspend fun getString(key: String): String? =
        context.dataStore.data.first().asMap().entries
            .firstOrNull { it.key.name == key }
            ?.value?.toString()

    override suspend fun putString(key: String, value: String) {
        context.dataStore.edit { it[stringPreferencesKey(key)] = value }
    }

    override suspend fun remove(key: String) {
        context.dataStore.edit { it.remove(stringPreferencesKey(key)) }
    }

    override suspend fun getSecret(key: String): String? = withContext(Dispatchers.IO) {
        secrets.getString(key, null)
    }

    override suspend fun putSecret(key: String, value: String) = withContext(Dispatchers.IO) {
        secrets.edit().putString(key, value).apply()
    }
}

class AndroidPlatform(
    private val application: Application,
    override val payments: PaymentProcessor,
    override val backupScheduler: BackupScheduler
) : Platform {

    override val description: String
        get() = "Android ${Build.VERSION.RELEASE} (${Build.MODEL})"

    override val kind: PlatformKind = PlatformKind.ANDROID

    override val settings: SettingsStore = AndroidSettingsStore(application)
}
