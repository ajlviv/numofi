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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
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
        val BACKUP_TREE_URI = stringPreferencesKey("backup_tree_uri")
        val BACKUP_UPLOADED_AT = longPreferencesKey("backup_uploaded_at")
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

    // Backup

    /**
     * The Drive folder backups are written to, or null when backup is off.
     *
     * Storing the folder URI is what makes backup off, rather than a separate flag: there is
     * nothing to switch off that is not "a folder has been chosen", and two settings that
     * have to agree would eventually not.
     *
     * The grant behind this URI is held by the system, not by this file, so the two can be
     * out of step — the user can revoke access in their Drive settings. The uploader treats a
     * write that fails as a failure to report rather than as a reason to forget the folder,
     * so the user is told instead of silently losing their backups.
     */
    val backupTreeUri: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.BACKUP_TREE_URI]?.takeIf { it.isNotBlank() }
    }

    suspend fun setBackupTreeUri(uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri == null) prefs.remove(Keys.BACKUP_TREE_URI)
            else prefs[Keys.BACKUP_TREE_URI] = uri
        }
    }

    /** When a backup last wrote successfully, or null if none ever has. */
    val lastBackupAt: Flow<Long?> = context.dataStore.data.map { prefs ->
        prefs[Keys.BACKUP_UPLOADED_AT]?.takeIf { it > 0 }
    }

    /**
     * Only ever called after the file has been written.
     *
     * A failed attempt must leave the previous value alone, or the screen would report the
     * backup as current when the newest one in Drive is not.
     */
    suspend fun setBackupUploadedAt(timestamp: Long) {
        context.dataStore.edit { it[Keys.BACKUP_UPLOADED_AT] = timestamp }
    }

    companion object {
        const val SYSTEM_DEFAULT = "system"

        /**
         * The stored tag, read without suspending, for `Activity.attachBaseContext`.
         *
         * That hook runs before Hilt exists and before any coroutine scope is available, and
         * every string the activity inflates is resolved against the context it produces — so
         * the one disk read the language needs happens here, once per activity creation.
         * DataStore keeps the parsed file in memory after the first read, so only the very
         * first call of the process pays for the file.
         */
        fun storedLanguage(context: Context): String = runBlocking {
            context.dataStore.data.first()[Keys.LANGUAGE] ?: SYSTEM_DEFAULT
        }
    }
}
