package com.financetracker.ui.statement

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.R
import com.financetracker.data.statement.BankDetector
import com.financetracker.data.statement.StatementFileReader
import com.financetracker.data.statement.StatementImportFailure
import com.financetracker.data.statement.StatementImportService
import com.financetracker.data.statement.StatementParseOutcome
import com.financetracker.data.statement.StatementParser
import com.financetracker.data.statement.StatementRow
import com.financetracker.model.Bank
import com.financetracker.repository.AuthRepository
import com.financetracker.repository.BankRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface StatementImportUiState {
    data object Idle : StatementImportUiState
    data object Working : StatementImportUiState

    /** Parsed but not yet saved, so the user can check the mapping before committing. */
    data class Preview(
        val rows: List<StatementRow>,
        val skippedRows: Int,
        /** Resource id of the minor-units note, resolved by the screen; null when not needed. */
        @StringRes val unitNoteRes: Int?,
        /** Pre-filled from the file's own headers; null until the user picks one. */
        val bankCode: String?,
        /** True when nothing recognised the file, so a bank has to be chosen by hand. */
        val bankUnresolved: Boolean,
        /**
         * Per-row category choices, by row index in [rows].
         *
         * Absent means "use the suggestion": the preview shows
         * [com.financetracker.util.CategorySuggestion] for the row and only stores what the
         * user actually changes. Nothing is written until [confirm], so correcting the choice
         * freely never touches the ledger.
         */
        val categoryOverrides: Map<Int, String> = emptyMap(),
        /**
         * The title → category history the suggestions are computed from, read once when the
         * file was parsed.
         *
         * Carried in the state rather than re-read per recomposition, and folded with the
         * same [com.financetracker.util.CategorySuggestion.foldHistory] the service uses at
         * confirm, so the value under each row's dropdown is the value that gets stored.
         */
        val history: Map<String, String> = emptyMap()
    ) : StatementImportUiState

    data class Done(
        val imported: Int,
        val duplicatesSkipped: Int,
        /** Lines bank sync had already stored, so these were not added again. */
        val alreadySynced: Int
    ) : StatementImportUiState

    /**
     * Fixed copy as a resource id; [detail] carries the raw failure detail (exception text)
     * which is appended verbatim after a newline and never translated.
     */
    data class Error(
        @StringRes val messageRes: Int,
        val detail: String? = null
    ) : StatementImportUiState
}

@HiltViewModel
class StatementImportViewModel @Inject constructor(
    private val fileReader: StatementFileReader,
    private val importService: StatementImportService,
    private val authRepository: AuthRepository,
    private val settingsRepository: com.financetracker.data.settings.SettingsRepository,
    private val transactionDao: com.financetracker.data.TransactionDao,
    bankRepository: BankRepository
) : ViewModel() {

    private val _state = MutableStateFlow<StatementImportUiState>(StatementImportUiState.Idle)
    val state: StateFlow<StatementImportUiState> = _state.asStateFlow()

    /**
     * Banks offered in the picker. Only the active ones, because importing is not a
     * good moment to be looking at something the user has put away.
     */
    val banks: StateFlow<List<Bank>> = bankRepository.activeBanks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The user's category list, for the per-row suggestion dropdowns in the preview.
     *
     * Empty until DataStore emits; the preview falls back to the defaults through
     * [CategorySuggestion], so a row always has something to suggest.
     */
    val categories: StateFlow<List<String>> = settingsRepository.categories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onFileSelected(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            _state.value = StatementImportUiState.Working
            // The detected bank has to come out of the read, because only the reader has the
            // header table, while the parse needs to be dispatched off the main thread. It is
            // carried across in a local rather than pulled back out of the state.
            var detectedBank: String? = null
            val outcome = withContext(Dispatchers.IO) {
                when (val read = fileReader.read(uri)) {
                    is StatementFileReader.Result.Failed ->
                        StatementParseOutcome.Failed(read.failure)

                    is StatementFileReader.Result.Ok -> {
                        detectedBank = BankDetector.detect(read.table)
                        StatementParser.parse(read.table)
                    }
                }
            }

            _state.value = when (outcome) {
                is StatementParseOutcome.Failed ->
                    StatementImportUiState.Error(
                        outcome.failure.toMessageRes(),
                        outcome.detail
                    )

                is StatementParseOutcome.Parsed -> {
                    val result = outcome.result
                    // The same fold the service runs at confirm, so the value the preview
                    // offers for a title is the value that will be stored for it.
                    val uid = authRepository.currentUid.first()
                    val history = if (uid == null) {
                        emptyMap()
                    } else {
                        com.financetracker.util.CategorySuggestion.foldHistory(
                            transactionDao.getTitleCategoryPairs(uid)
                        )
                    }
                    StatementImportUiState.Preview(
                        rows = result.rows.map { it.copy(bankCode = detectedBank) },
                        skippedRows = result.skippedRows,
                        unitNoteRes = if (result.columns.amountsAreMinorUnits) {
                            R.string.import_minor_units_note
                        } else {
                            null
                        },
                        bankCode = detectedBank,
                        bankUnresolved = detectedBank == null,
                        history = history
                    )
                }
            }
        }
    }

    /**
     * Records which bank the user picked. It changes only the preview, so the choice can be
     * corrected freely; nothing is written until [confirm].
     */
    fun onBankSelected(code: String?) {
        val preview = _state.value as? StatementImportUiState.Preview ?: return
        _state.value = preview.copy(
            bankCode = code,
            bankUnresolved = false,
            rows = preview.rows.map { it.copy(bankCode = code) }
        )
    }

    /**
     * Records the category the user picked for one preview row.
     *
     * One-off values (typed, not in the settings list) are kept as-is: they ride along into
     * the stored row without joining the list, the same contract the form dropdowns keep.
     */
    fun onCategorySelected(rowIndex: Int, category: String) {
        val preview = _state.value as? StatementImportUiState.Preview ?: return
        _state.value = preview.copy(
            categoryOverrides = preview.categoryOverrides + (rowIndex to category)
        )
    }

    fun confirm() {
        val preview = _state.value as? StatementImportUiState.Preview ?: return
        // Refuse rather than filing the file under an unnamed bank: the whole point of the
        // column is being able to filter by bank later, and an unrecognised file has to be
        // attributed deliberately.
        if (preview.bankCode == null) {
            _state.value = StatementImportUiState.Error(R.string.import_error_choose_bank)
            return
        }
        viewModelScope.launch {
            _state.value = StatementImportUiState.Working
            val uid = authRepository.currentUid.first()
            if (uid == null) {
                _state.value = StatementImportUiState.Error(R.string.import_sign_in)
                return@launch
            }
            val categories = settingsRepository.categories.first()
            val result = withContext(Dispatchers.IO) {
                importService.import(uid, preview.rows, categories, preview.categoryOverrides)
            }
            _state.value = StatementImportUiState.Done(
                result.imported,
                result.duplicatesSkipped,
                result.alreadySynced
            )
        }
    }

    fun dismiss() {
        _state.value = StatementImportUiState.Idle
    }
}

/** The resource for a parse failure; the optional failure detail rides along in [StatementImportUiState.Error.detail]. */
private fun StatementImportFailure.toMessageRes(): Int = when (this) {
    StatementImportFailure.UNREADABLE_FILE -> R.string.import_error_unreadable
    StatementImportFailure.UNSUPPORTED_FORMAT -> R.string.import_error_unsupported
    StatementImportFailure.EMPTY_FILE -> R.string.import_error_empty
    StatementImportFailure.NO_HEADER_ROW -> R.string.import_error_no_header
    StatementImportFailure.NO_DATE_COLUMN -> R.string.import_error_no_date
    StatementImportFailure.NO_AMOUNT_COLUMN -> R.string.import_error_no_amount
    StatementImportFailure.TOO_LARGE -> R.string.import_error_too_large
}

/** Preview rows render with the same short format the transaction list uses. */
fun formatPreviewDate(timestamp: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(timestamp))
