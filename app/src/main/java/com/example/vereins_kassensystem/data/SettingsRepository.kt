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
    
    val backupUri: Flow<String?> = context.dataStore.data.map { it[BACKUP_URI] }
    val autoBackupEnabled: Flow<Boolean> = context.dataStore.data.map { it[AUTO_BACKUP_ENABLED] ?: false }

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
