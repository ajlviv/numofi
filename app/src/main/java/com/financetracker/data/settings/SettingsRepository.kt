package com.financetracker.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import com.financetracker.model.ExchangeRates
import com.financetracker.model.ExclusionRules
import com.financetracker.model.RECORDABLE_CURRENCIES
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** UAH: the currency NBU quotes against, and the one bonds are denominated in. */
const val DEFAULT_BASE_CURRENCY = "UAH"

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
        val BASE_CURRENCY = stringPreferencesKey("base_currency")
        val RATES_DATE = stringPreferencesKey("rates_date")
        val RATES_FETCHED_AT = longPreferencesKey("rates_fetched_at")
        val EXCLUSION_RULES = stringPreferencesKey("exclusion_rules")
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")

        fun rateKey(code: String) = doublePreferencesKey("rates_$code")
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

    // Base currency and its rates

    /**
     * The currency every total is converted to.
     *
     * Not nullable and not switchable: [DEFAULT_BASE_CURRENCY] is what a device that has never
     * been asked reports, so there is no state in which the dashboard has no total to show.
     * UAH rather than "the first currency seen" because it is the currency NBU quotes against
     * and the one bonds are denominated in — a base that moved as data arrived would make the
     * headline change on its own.
     *
     * Read back through [RECORDABLE_CURRENCIES], because a value this build no longer offers
     * has no rate to convert through and would divide by nothing.
     */
    val baseCurrency: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.BASE_CURRENCY]?.takeIf { it in RECORDABLE_CURRENCIES } ?: DEFAULT_BASE_CURRENCY
    }

    suspend fun setBaseCurrency(code: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BASE_CURRENCY] = code
        }
    }

    /**
     * The last rates this device fetched, empty until one fetch has succeeded.
     *
     * In DataStore rather than a table on purpose. `AppDatabase` ships no migrations and no
     * destructive fallback, so a new table means a version bump means Room refusing the user's
     * file — and losing every transaction, bank, bond and trade they have. Two rates do not
     * justify that, and a cache is the least valuable thing in the file anyway.
     *
     * Also absent from the backup snapshot, which walks Room entities. A restored device
     * fetches its own rates rather than inheriting ones whose date is whenever the backup was
     * taken, exactly as it re-reads its own theme.
     */
    val exchangeRates: Flow<ExchangeRates> = context.dataStore.data.map { prefs ->
        ExchangeRates(
            toUah = RECORDABLE_CURRENCIES
                .mapNotNull { code -> prefs[Keys.rateKey(code)]?.let { code to it } }
                .toMap(),
            date = prefs[Keys.RATES_DATE]?.takeIf { it.isNotBlank() },
            fetchedAt = prefs[Keys.RATES_FETCHED_AT] ?: 0L
        )
    }

    /**
     * Replaces the cache outright.
     *
     * Every stored rate is rewritten rather than merged into, so a currency NBU stops
     * publishing stops being quoted. Merging would leave yesterday's EUR in place looking
     * current, which is the failure this feature is most able to make without anyone noticing.
     */
    suspend fun saveExchangeRates(rates: ExchangeRates) {
        context.dataStore.edit { prefs ->
            RECORDABLE_CURRENCIES.forEach { prefs.remove(Keys.rateKey(it)) }
            rates.toUah.forEach { (code, rate) -> prefs[Keys.rateKey(code)] = rate }
            prefs[Keys.RATES_DATE] = rates.date ?: ""
            prefs[Keys.RATES_FETCHED_AT] = rates.fetchedAt
        }
    }

    // Exclusion rules

    /**
     * The user's own rules for what a total may not count. Empty until one is written, which is
     * the ordinary state of a fresh install rather than a missing preference.
     *
     * In DataStore for the reason [exchangeRates] gives: a handful of rules is not worth a
     * table, and `AppDatabase` ships no migrations, so a table means a version bump means Room
     * refusing the user's file. Also absent from the backup snapshot, which walks Room entities —
     * a rule is a preference about how the data is presented, and a restored device re-reads its
     * own rather than inheriting one written against a screen that may since have changed.
     */
    val exclusionRules: Flow<ExclusionRules> = context.dataStore.data.map { prefs ->
        readExclusionRules(prefs[Keys.EXCLUSION_RULES])
    }

    /**
     * The whole set in one write, replacing rather than merging.
     *
     * The user manages this as a list — they delete one rule and keep the rest — so the set is
     * the unit of storage and the unit of a save. Storing one key per rule would need its own
     * sweeping on delete and would leave an orphan key behind every rule the user removed: a
     * preference they can neither see nor clear.
     *
     * Blank rules are dropped on the way in rather than refused, because [ExclusionRules] is what
     * decides what a rule means and this only has to store it. A save that threw here would put
     * a second, disagreeing idea of a valid rule in the repository.
     */
    suspend fun saveExclusionRules(patterns: List<String>) {
        val rules = ExclusionRules(patterns)
        context.dataStore.edit { prefs ->
            if (rules.isEmpty) prefs.remove(Keys.EXCLUSION_RULES)
            else prefs[Keys.EXCLUSION_RULES] = encodeExclusionRules(rules.patterns)
        }
    }

    // App lock

    /**
     * Whether the app must be unlocked before it shows anything, or false when the user has
     * never asked for it.
     *
     * False is the ordinary state of a fresh install rather than a missing preference, and
     * staying false by default is what lets this ship without touching anything else: an
     * existing install behaves exactly as it did before the switch existed.
     *
     * **Per-device, not per-account.** What is being protected is this phone's database, which
     * outlives any sign-in. Clearing the flag when the user signs out would leave the lock
     * protecting nothing at the moment it is most wanted — a signed-out app still holding every
     * transaction and bond position. It is also absent from the backup snapshot, for the reason
     * [exchangeRates] gives: a restored device re-reads its own settings, and inheriting
     * "locked" onto a device with no credential set up is a lockout with no way out of it.
     */
    val appLockEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.APP_LOCK_ENABLED] ?: false
    }

    /**
     * Turns the lock on or off.
     *
     * Only ever called with the switch enabled, and the settings screen refuses that when the
     * device has nothing that could satisfy the prompt. Writing true unconditionally would
     * leave the flag describing a lock no credential on the device can open.
     */
    suspend fun setAppLockEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.APP_LOCK_ENABLED] = enabled }
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
