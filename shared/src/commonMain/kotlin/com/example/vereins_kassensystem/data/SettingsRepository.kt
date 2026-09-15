package com.example.vereins_kassensystem.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.example.vereins_kassensystem.ui.theme.ClubIdentity
import com.example.vereins_kassensystem.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    
    private val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        "secure_settings",
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val SUMUP_AFFILIATE_KEY = "sumup_affiliate_key"
    private val BACKUP_URI = stringPreferencesKey("backup_uri")
    private val AUTO_BACKUP_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("auto_backup_enabled")
    private val THEME_MODE = stringPreferencesKey("theme_mode")
    private val CLUB_NAME = stringPreferencesKey("club_name")
    private val CLUB_ACCENT = androidx.datastore.preferences.core.intPreferencesKey("club_accent")

    val backupUri: Flow<String?> = context.dataStore.data.map { it[BACKUP_URI] }
    val autoBackupEnabled: Flow<Boolean> = context.dataStore.data.map { it[AUTO_BACKUP_ENABLED] ?: false }

    /**
     * Light/dark preference. A tablet mounted behind the bar wants to be pinned to dark
     * for the evening whatever the system is doing, so this is a real setting rather
     * than a straight follow of [ThemeMode.SYSTEM].
     */
    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { ThemeMode.fromName(it[THEME_MODE]) }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[THEME_MODE] = mode.name }
    }

    /**
     * The club's own name and colour. Stored per install because the app is built for any
     * Verein — see [ClubIdentity] for why the accent stays out of the semantic roles.
     */
    val clubIdentity: Flow<ClubIdentity> = context.dataStore.data.map { prefs ->
        ClubIdentity(
            name = prefs[CLUB_NAME].orEmpty(),
            accent = prefs[CLUB_ACCENT]?.let { Color(it) } ?: ClubIdentity().accent
        )
    }

    suspend fun setClubName(name: String) {
        context.dataStore.edit { it[CLUB_NAME] = name }
    }

    suspend fun setClubAccent(color: Color) {
        // Stored as a plain ARGB int; Color is a value class over a Long with the colour
        // space packed in, which is not something to persist.
        context.dataStore.edit { it[CLUB_ACCENT] = color.toArgb() }
    }

    suspend fun saveBackupUri(uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri == null) prefs.remove(BACKUP_URI) else prefs[BACKUP_URI] = uri
        }
    }

    suspend fun setAutoBackupEnabled(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_BACKUP_ENABLED] = enabled }
    }

    private val _sumUpAffiliateKey = MutableStateFlow(encryptedPrefs.getString(SUMUP_AFFILIATE_KEY, "") ?: "")
    val sumUpAffiliateKey: Flow<String> = _sumUpAffiliateKey.asStateFlow()

    suspend fun saveSumUpAffiliateKey(key: String) {
        encryptedPrefs.edit().putString(SUMUP_AFFILIATE_KEY, key).apply()
        _sumUpAffiliateKey.value = key
    }
}
