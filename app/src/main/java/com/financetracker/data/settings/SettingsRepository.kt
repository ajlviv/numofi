package com.financetracker.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Non-sensitive user preferences. Credentials live in [com.financetracker.data.bank.BankCredentialStore]
 * instead, since those must be encrypted.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LANGUAGE = stringPreferencesKey("language")
        val SELECTED_BANK = stringPreferencesKey("selected_bank")
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        prefs[Keys.THEME_MODE]?.let { stored ->
            ThemeMode.entries.firstOrNull { it.name == stored }
        } ?: ThemeMode.SYSTEM
    }

    /** BCP-47-ish tag, or [SYSTEM_DEFAULT] to follow the device language. */
    val language: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.LANGUAGE] ?: SYSTEM_DEFAULT
    }

    val selectedBankId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.SELECTED_BANK]
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setLanguage(tag: String) {
        context.dataStore.edit { it[Keys.LANGUAGE] = tag }
    }

    suspend fun setSelectedBank(bankId: String?) {
        context.dataStore.edit { prefs ->
            if (bankId == null) prefs.remove(Keys.SELECTED_BANK)
            else prefs[Keys.SELECTED_BANK] = bankId
        }
    }

    /**
     * When this bank was last synced, per bank id.
     *
     * Lets a repeat sync request only the gap since last time instead of a fixed
     * range, which is what keeps it down to a single request.
     */
    fun lastSyncedAt(bankId: String): Flow<Long?> = context.dataStore.data.map { prefs ->
        prefs[longPreferencesKey(lastSyncedKey(bankId))]?.takeIf { it > 0 }
    }

    suspend fun setLastSyncedAt(bankId: String, timestamp: Long) {
        context.dataStore.edit { it[longPreferencesKey(lastSyncedKey(bankId))] = timestamp }
    }

    suspend fun clearLastSyncedAt(bankId: String) {
        context.dataStore.edit { it.remove(longPreferencesKey(lastSyncedKey(bankId))) }
    }

    private fun lastSyncedKey(bankId: String) = "last_sync_$bankId"

    companion object {
        const val SYSTEM_DEFAULT = "system"
    }
}
