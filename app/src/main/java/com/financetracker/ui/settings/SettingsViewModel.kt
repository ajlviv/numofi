package com.financetracker.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.data.bank.BankCredentialStore
import com.financetracker.data.bank.BankSyncService
import com.financetracker.data.bank.BankProvider
import com.financetracker.data.bank.BankRateLimitException
import com.financetracker.data.bank.BankProviderRegistry
import com.financetracker.data.settings.SettingsRepository
import com.financetracker.data.settings.ThemeMode
import com.financetracker.model.Bank
import com.financetracker.repository.AddBankResult
import com.financetracker.repository.AuthRepository
import com.financetracker.repository.BankRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val credentialStore: BankCredentialStore,
    private val authRepository: AuthRepository,
    private val bankSyncService: BankSyncService,
    private val bankRepository: BankRepository,
    registry: BankProviderRegistry
) : ViewModel() {

    val availableBanks: List<BankProvider> = registry.all

    val themeMode: StateFlow<ThemeMode> = settingsRepository.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    val language: StateFlow<String> = settingsRepository.language
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.SYSTEM_DEFAULT)

    val selectedBankId: StateFlow<String?> = settingsRepository.selectedBankId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _tokenInput = MutableStateFlow("")
    val tokenInput: StateFlow<String> = _tokenInput.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncProgress = MutableStateFlow<BankSyncService.SyncProgress?>(null)
    val syncProgress: StateFlow<BankSyncService.SyncProgress?> = _syncProgress.asStateFlow()

    private val _syncDays = MutableStateFlow(DEFAULT_SYNC_DAYS)
    val syncDays: StateFlow<Int> = _syncDays.asStateFlow()

    /** When the selected bank was last synced, or null if it never has been. */
    val lastSyncedAt: StateFlow<Long?> = selectedBankId
        .flatMapLatest { bankId ->
            bankId?.let { settingsRepository.lastSyncedAt(it) } ?: flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Bumped whenever the stored credential changes. The credential lives in
     * [com.financetracker.data.secure.CredentialStore] rather than DataStore, so without
     * this signal nothing would re-read it and the sync controls would stay hidden until
     * the process restarted.
     */
    private val credentialRevision = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val isTokenConfigured: StateFlow<Boolean> = combine(
        selectedBankId,
        credentialRevision
    ) { bankId, _ -> bankId }
        .flatMapLatest { bankId ->
            flow {
                emit(bankId != null && credentialStore.personalToken(bankId) != null)
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun onTokenChange(value: String) {
        _tokenInput.value = value.trim()
        _statusMessage.value = null
    }

    fun selectBank(bankId: String?) {
        viewModelScope.launch {
            settingsRepository.setSelectedBank(bankId)
            _tokenInput.value = ""
            _statusMessage.value = null
        }
    }

    fun saveToken() {
        val bankId = selectedBankId.value
        val token = _tokenInput.value
        if (bankId == null || token.isBlank()) {
            _statusMessage.value = "Select a bank and enter a token first"
            return
        }
        viewModelScope.launch {
            credentialStore.savePersonalToken(bankId, token)
            // Reveal the sync controls immediately rather than on next launch.
            credentialRevision.value++
            _tokenInput.value = ""
            _statusMessage.value = "Token saved"
        }
    }

    fun clearToken() {
        val bankId = selectedBankId.value ?: return
        viewModelScope.launch {
            credentialStore.clear(bankId)
            credentialRevision.value++
            _statusMessage.value = "Token removed"
        }
    }

    /** Calls the provider to confirm the stored credential actually works. */
    fun verifyConnection() {
        val bankId = selectedBankId.value
        val provider = availableBanks.firstOrNull { it.id == bankId }
        val auth = bankId?.let { credentialStore.authFor(it) }
        if (provider == null || auth == null) {
            _statusMessage.value = "Save a token first"
            return
        }

        viewModelScope.launch {
            _isSyncing.value = true
            _statusMessage.value = null
            runCatching { provider.getAccounts(auth) }
                .onSuccess { accounts ->
                    _statusMessage.value = "Connected: ${accounts.size} account(s)"
                }
                .onFailure { error ->
                    _statusMessage.value = error.message ?: "Connection failed"
                }
            _isSyncing.value = false
        }
    }

    /**
     * Imports statements into local storage for the signed-in account.
     *
     * The range is the gap since the last successful sync, not a fixed number of
     * days, so a routine sync is a single request and never waits. The day chips only
     * set how far back the very first sync reaches.
     */
    fun syncNow() {
        val bankId = selectedBankId.value
        val provider = availableBanks.firstOrNull { it.id == bankId }
        val auth = bankId?.let { credentialStore.authFor(it) }
        if (provider == null || auth == null) {
            _statusMessage.value = "Save a token first"
            return
        }

        viewModelScope.launch {
            _isSyncing.value = true
            _statusMessage.value = null
            _syncProgress.value = null

            val uid = authRepository.currentUid.first()
            if (uid == null) {
                _statusMessage.value = "Sign in to sync"
                _isSyncing.value = false
                return@launch
            }

            val to = System.currentTimeMillis()
            val from = syncFrom(bankId, to)

            runCatching {
                bankSyncService.sync(provider, auth, uid, from, to) { progress ->
                    _syncProgress.value = progress
                }
            }
                .onSuccess { result ->
                    settingsRepository.setLastSyncedAt(bankId, to)
                    _statusMessage.value =
                        "Imported ${result.imported} transaction(s) from " +
                            "${result.accounts} account(s)" +
                            if (result.skippedDuplicates > 0) {
                                " (${result.skippedDuplicates} already present)"
                            } else {
                                ""
                            }
                }
                .onFailure { error ->
                    _statusMessage.value = describeSyncFailure(error)
                }

            _syncProgress.value = null
            _isSyncing.value = false
        }
    }

    /**
     * Resumes from the last sync instead of re-reading a full range. A day of overlap
     * absorbs any transactions the bank posted with a slight delay.
     */
    private suspend fun syncFrom(bankId: String, now: Long): Long {
        val lastSync = settingsRepository.lastSyncedAt(bankId).first() ?: return now - syncDays.value * MILLIS_PER_DAY
        return minOf(lastSync - SYNC_OVERLAP_MILLIS, now - syncDays.value * MILLIS_PER_DAY)
    }

    /**
     * Rate limits are expected on a paced import, so the message says what landed and
     * when it is safe to retry rather than showing a bare error.
     */
    private fun describeSyncFailure(error: Throwable): String = when (error) {
        is BankRateLimitException -> {
            val partial = _syncProgress.value?.imported ?: 0
            val importedText =
                if (partial > 0) "Imported $partial transaction(s) before the limit. " else ""
            val waitSeconds = error.retryAfterMillis / 1000
            "$importedText${error.message ?: "Rate limited."} " +
                "Retry in about ${maxOf(waitSeconds, 1)}s."
        }
        else -> error.message ?: "Sync failed"
    }

    fun setSyncDays(days: Int) {
        _syncDays.value = days
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    // The user's bank list, which has nothing to do with the sync providers above: those are
    // the institutions this build can talk to, these are the names a statement can be filed
    // under. A custom bank has no connection and needs none.

    /** Every bank, live or archived, in the order the user arranged. */
    val banks: StateFlow<List<Bank>> = bankRepository.banks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _bankNameInput = MutableStateFlow("")
    val bankNameInput: StateFlow<String> = _bankNameInput.asStateFlow()

    /**
     * Kept apart from [statusMessage] because that one is rendered under the sync section,
     * which is a screen away from here — an "already called that" about a bank would appear
     * next to a token field and read as though the token were the problem.
     */
    private val _bankMessage = MutableStateFlow<String?>(null)
    val bankMessage: StateFlow<String?> = _bankMessage.asStateFlow()

    fun onBankNameChange(value: String) {
        _bankNameInput.value = value
        _bankMessage.value = null
    }

    fun addBank() {
        viewModelScope.launch {
            _bankMessage.value = when (val result = bankRepository.add(_bankNameInput.value)) {
                is AddBankResult.Added -> {
                    _bankNameInput.value = ""
                    "Added ${result.bank.displayName}."
                }
                AddBankResult.BlankName -> "Type a name for the bank first."
                AddBankResult.NameTaken -> "You already have a bank with that name."
                // Retrying would change nothing, so the message has to say the bank could
                // not be added rather than inviting another attempt at the name.
                AddBankResult.CodeUnavailable -> "Could not create a bank right now. Try again."
            }
        }
    }

    fun renameBank(code: String, name: String) {
        viewModelScope.launch {
            _bankMessage.value = if (bankRepository.rename(code, name)) {
                "Renamed."
            } else {
                "That name is blank or already in use."
            }
        }
    }

    fun moveBankUp(code: String) {
        viewModelScope.launch { bankRepository.moveUp(code) }
    }

    fun moveBankDown(code: String) {
        viewModelScope.launch { bankRepository.moveDown(code) }
    }

    fun setBankArchived(code: String, archived: Boolean) {
        viewModelScope.launch {
            bankRepository.setArchived(code, archived)
            _bankMessage.value = if (archived) {
                "Archived. Its transactions are kept, and it stays in the filter."
            } else {
                "Restored."
            }
        }
    }

    fun setLanguage(tag: String) {
        viewModelScope.launch { settingsRepository.setLanguage(tag) }
    }

    /**
     * Clears the local session. The signed-in screen observes the session and routes
     * back to login on its own, so no manual navigation is needed here.
     */
    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }

    private companion object {
        const val DEFAULT_SYNC_DAYS = 30
        const val MILLIS_PER_DAY = 86_400_000L
        const val SYNC_OVERLAP_MILLIS = MILLIS_PER_DAY
    }
}
