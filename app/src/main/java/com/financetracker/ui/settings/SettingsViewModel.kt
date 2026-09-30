package com.financetracker.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.financetracker.data.backup.BackupReason
import com.financetracker.data.backup.BackupSnapshotCodec
import com.financetracker.data.backup.BackupStatus
import com.financetracker.data.backup.BackupStore
import com.financetracker.data.backup.BackupUploader
import com.financetracker.data.backup.BackupRestorer
import com.financetracker.data.backup.RestoreResult
import com.financetracker.data.backup.UnreadableBackupException
import com.financetracker.data.backup.UnsupportedFormatException
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
import androidx.annotation.StringRes
import com.financetracker.R
import javax.inject.Inject

/**
 * A user-visible settings message.
 *
 * The ViewModel cannot call `stringResource`, so fixed copy is carried as a resource id
 * plus its positional format arguments and resolved at the display site in the screen.
 * Provider and exception text is carried as [Raw] instead, because that is never translated.
 */
sealed interface SettingsMessage {
    /** Localized copy; [args] fill the positional placeholders in order. */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : SettingsMessage

    /** Provider or exception text shown verbatim; never translated. */
    data class Raw(val text: String) : SettingsMessage
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val credentialStore: BankCredentialStore,
    private val authRepository: AuthRepository,
    private val bankSyncService: BankSyncService,
    private val bankRepository: BankRepository,
    private val uploader: BackupUploader,
    private val backupStore: BackupStore,
    private val restorer: BackupRestorer,
    private val codec: BackupSnapshotCodec,
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

    private val _statusMessage = MutableStateFlow<SettingsMessage?>(null)
    val statusMessage: StateFlow<SettingsMessage?> = _statusMessage.asStateFlow()

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
            _statusMessage.value = SettingsMessage.Res(R.string.settings_token_required)
            return
        }
        viewModelScope.launch {
            credentialStore.savePersonalToken(bankId, token)
            // Reveal the sync controls immediately rather than on next launch.
            credentialRevision.value++
            _tokenInput.value = ""
            _statusMessage.value = SettingsMessage.Res(R.string.settings_token_saved)
        }
    }

    fun clearToken() {
        val bankId = selectedBankId.value ?: return
        viewModelScope.launch {
            credentialStore.clear(bankId)
            credentialRevision.value++
            _statusMessage.value = SettingsMessage.Res(R.string.settings_token_removed)
        }
    }

    /** Calls the provider to confirm the stored credential actually works. */
    fun verifyConnection() {
        val bankId = selectedBankId.value
        val provider = availableBanks.firstOrNull { it.id == bankId }
        val auth = bankId?.let { credentialStore.authFor(it) }
        if (provider == null || auth == null) {
            _statusMessage.value = SettingsMessage.Res(R.string.settings_save_token_first)
            return
        }

        viewModelScope.launch {
            _isSyncing.value = true
            _statusMessage.value = null
            runCatching { provider.getAccounts(auth) }
                .onSuccess { accounts ->
                    _statusMessage.value = SettingsMessage.Res(
                        R.string.settings_connected_accounts,
                        listOf(accounts.size)
                    )
                }
                .onFailure { error ->
                    _statusMessage.value = error.message?.let { SettingsMessage.Raw(it) }
                        ?: SettingsMessage.Res(R.string.settings_connection_failed)
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
            _statusMessage.value = SettingsMessage.Res(R.string.settings_save_token_first)
            return
        }

        viewModelScope.launch {
            _isSyncing.value = true
            _statusMessage.value = null
            _syncProgress.value = null

            val uid = authRepository.currentUid.first()
            if (uid == null) {
                _statusMessage.value = SettingsMessage.Res(R.string.settings_sign_in_to_sync)
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
                        if (result.skippedDuplicates > 0) {
                            SettingsMessage.Res(
                                R.string.settings_sync_done_with_duplicates,
                                listOf(result.imported, result.accounts, result.skippedDuplicates)
                            )
                        } else {
                            SettingsMessage.Res(
                                R.string.settings_sync_done,
                                listOf(result.imported, result.accounts)
                            )
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
     *
     * The provider's own text is passed as an argument (a nested [SettingsMessage], so a
     * missing message falls back to localized copy instead of leaving English in the VM).
     */
    private fun describeSyncFailure(error: Throwable): SettingsMessage = when (error) {
        is BankRateLimitException -> {
            val partial = _syncProgress.value?.imported ?: 0
            val seconds = maxOf(error.retryAfterMillis / 1000, 1)
            val providerText = error.message?.let { SettingsMessage.Raw(it) }
                ?: SettingsMessage.Res(R.string.settings_rate_limited_fallback)
            if (partial > 0) {
                SettingsMessage.Res(
                    R.string.settings_sync_rate_limited_partial,
                    listOf(partial, providerText, seconds)
                )
            } else {
                SettingsMessage.Res(
                    R.string.settings_sync_rate_limited,
                    listOf(providerText, seconds)
                )
            }
        }
        else -> error.message?.let { SettingsMessage.Raw(it) }
            ?: SettingsMessage.Res(R.string.settings_sync_failed)
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
    private val _bankMessage = MutableStateFlow<SettingsMessage?>(null)
    val bankMessage: StateFlow<SettingsMessage?> = _bankMessage.asStateFlow()

    fun onBankNameChange(value: String) {
        _bankNameInput.value = value
        _bankMessage.value = null
    }

    fun addBank() {
        viewModelScope.launch {
            _bankMessage.value = when (val result = bankRepository.add(_bankNameInput.value)) {
                is AddBankResult.Added -> {
                    _bankNameInput.value = ""
                    SettingsMessage.Res(
                        R.string.settings_bank_added,
                        listOf(result.bank.displayName)
                    )
                }
                AddBankResult.BlankName ->
                    SettingsMessage.Res(R.string.settings_bank_name_required)
                AddBankResult.NameTaken ->
                    SettingsMessage.Res(R.string.settings_bank_name_taken)
                // Retrying would change nothing, so the message has to say the bank could
                // not be added rather than inviting another attempt at the name.
                AddBankResult.CodeUnavailable ->
                    SettingsMessage.Res(R.string.settings_bank_create_failed)
            }
        }
    }

    fun renameBank(code: String, name: String) {
        viewModelScope.launch {
            _bankMessage.value = if (bankRepository.rename(code, name)) {
                SettingsMessage.Res(R.string.settings_bank_renamed)
            } else {
                SettingsMessage.Res(R.string.settings_bank_rename_invalid)
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
                SettingsMessage.Res(R.string.settings_bank_archived_msg)
            } else {
                SettingsMessage.Res(R.string.settings_bank_restored)
            }
        }
    }

    /**
     * Fires after a language pick has been written to the DataStore.
     *
     * The tag is applied by `AppLocale` in `attachBaseContext`, which has already run by the
     * time Settings can observe anything, so applying a pick means recreating the activity.
     * Emitting only after the write lands is what stops that recreation from re-reading the
     * old tag and looping.
     */
    private val _languageChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val languageChanged: SharedFlow<Unit> = _languageChanged.asSharedFlow()

    fun setLanguage(tag: String) {
        viewModelScope.launch {
            settingsRepository.setLanguage(tag)
            _languageChanged.tryEmit(Unit)
        }
    }

    /**
     * Clears the local session. The signed-in screen observes the session and routes
     * back to login on its own, so no manual navigation is needed here.
     */
    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }

    // Backup

    /**
     * Where backups stand, straight off the uploader.
     *
     * Read from the singleton rather than held here, because the uploads it describes belong
     * to the application and not to this screen: a change made on the transactions screen
     * uploads with nobody in Settings, and coming back to the page has to show that.
     */
    val backupStatus: StateFlow<BackupStatus> = uploader.status

    /**
     * Kept apart from [statusMessage] for the same reason as [bankMessage]: that one is
     * rendered under the sync section, a screen away, and "that folder could not be opened"
     * appearing next to a token field would read as though the token were the problem.
     */
    private val _backupMessage = MutableStateFlow<SettingsMessage?>(null)
    val backupMessage: StateFlow<SettingsMessage?> = _backupMessage.asStateFlow()

    /**
     * Up for the length of a restore, which is a file read and then a few hundred inserts.
     * Separate from the upload status because the upload this triggers is the one place a
     * restore can leave the status saying "backed up" while the user is still waiting.
     */
    private val _isRestoring = MutableStateFlow(false)
    val isRestoring: StateFlow<Boolean> = _isRestoring.asStateFlow()

    /**
     * Kept apart from [backupMessage] for the third time, for the same reason as the other
     * two: the upload message is rendered under the folder controls, and "that file belongs
     * to another account" appearing under them would read as though the folder were at fault.
     */
    private val _restoreMessage = MutableStateFlow<SettingsMessage?>(null)
    val restoreMessage: StateFlow<SettingsMessage?> = _restoreMessage.asStateFlow()

    /**
     * Turns backup on for the folder the user just picked, and takes the first backup.
     *
     * Also the path for changing the folder later, so the previous grant is given up first —
     * a persisted write grant into a Drive folder outlives the choice to use it, and holding
     * one the app no longer writes to is a permission nothing is using.
     */
    fun onBackupFolderPicked(tree: Uri) {
        viewModelScope.launch {
            _backupMessage.value = null
            val previous = settingsRepository.backupTreeUri.first()
            if (previous != null && previous != tree.toString()) {
                backupStore.release(Uri.parse(previous))
            }
            runCatching { backupStore.persist(tree) }
                .onSuccess {
                    settingsRepository.setBackupTreeUri(tree.toString())
                    // The first backup is taken here rather than waiting for the next write.
                    // Enabling backup and having nothing in Drive until the user happens to
                    // change something would leave it looking broken for as long as it took to
                    // notice.
                    uploader.requestUpload(BackupReason.ENABLED)
                }
                .onFailure {
                    _backupMessage.value = SettingsMessage.Res(R.string.settings_backup_no_access)
                }
        }
    }

    fun backupNow() {
        uploader.requestUpload(BackupReason.MANUAL)
    }

    /**
     * Merges a backup file the user picked into the signed-in account.
     *
     * Everything that can go wrong here is reported as a distinct message rather than a
     * generic failure, because the three failures a user can actually cause each have a
     * different next step: a file from another account needs a different sign-in, a newer
     * format needs a newer app, and an unreadable file needs a different file. Collapsing
     * them into "restore failed" would leave the user picking files at random.
     *
     * Nothing is asked of the user before this point beyond choosing a file, and nothing is
     * deleted by it, so there is no confirmation step: a restore only ever adds rows that
     * were missing.
     */
    fun onRestoreFilePicked(document: Uri) {
        viewModelScope.launch {
            _restoreMessage.value = null

            val uid = authRepository.currentUid.first()
            if (uid == null) {
                _restoreMessage.value = SettingsMessage.Res(R.string.settings_restore_sign_in_first)
                return@launch
            }

            // Read and decoded before the flag goes up only in the sense that these two calls
            // are what the flag is for: a file off Drive is a network read of unknown length,
            // and the restore itself is hundreds of inserts.
            _isRestoring.value = true
            val outcome = runCatching {
                val snapshot = codec.decode(backupStore.read(document))
                restorer.restore(snapshot, uid)
            }
            _isRestoring.value = false

            _restoreMessage.value = outcome.fold(
                onSuccess = { result ->
                    when (result) {
                        is RestoreResult.WrongAccount ->
                            SettingsMessage.Res(R.string.settings_restore_wrong_account)
                        // Says plainly that nothing was added, because "restored" for a file
                        // whose every row was already here is the opposite of what happened.
                        is RestoreResult.Done -> if (result.changed) {
                            SettingsMessage.Res(
                                R.string.settings_restore_done,
                                listOf(result.restored, result.skipped)
                            )
                        } else {
                            SettingsMessage.Res(
                                R.string.settings_restore_nothing_new,
                                listOf(result.skipped)
                            )
                        }
                    }
                },
                onFailure = { error ->
                    when (error) {
                        is UnsupportedFormatException ->
                            SettingsMessage.Res(R.string.settings_restore_too_new)
                        is UnreadableBackupException ->
                            SettingsMessage.Res(R.string.settings_restore_unreadable)
                        else -> SettingsMessage.Res(R.string.settings_restore_failed)
                    }
                }
            )
        }
    }

    /**
     * Stops backing up and gives the folder grant back.
     *
     * The file already in Drive is left alone. It belongs to the user, it may be the only copy
     * of something this device then loses, and switching a feature off is not a request to
     * destroy what it produced.
     */
    fun disableBackup() {
        viewModelScope.launch {
            settingsRepository.backupTreeUri.first()?.let { backupStore.release(Uri.parse(it)) }
            settingsRepository.setBackupTreeUri(null)
        }
    }

    private companion object {
        const val DEFAULT_SYNC_DAYS = 30
        const val MILLIS_PER_DAY = 86_400_000L
        const val SYNC_OVERLAP_MILLIS = MILLIS_PER_DAY
    }
}
