package com.financetracker.ui.statement

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.data.statement.BankDetector
import com.financetracker.data.statement.StatementFileReader
import com.financetracker.data.statement.StatementImportFailure
import com.financetracker.data.statement.StatementImportService
import com.financetracker.data.statement.StatementParseOutcome
import com.financetracker.data.statement.StatementParser
import com.financetracker.data.statement.StatementRow
import com.financetracker.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface StatementImportUiState {
    data object Idle : StatementImportUiState
    data object Working : StatementImportUiState

    /** Parsed but not yet saved, so the user can check the mapping before committing. */
    data class Preview(
        val rows: List<StatementRow>,
        val skippedRows: Int,
        val unitNote: String?,
        /** Pre-filled from the file's own headers; null until the user picks one. */
        val bankCode: String?,
        /** True when nothing recognised the file, so a bank has to be chosen by hand. */
        val bankUnresolved: Boolean
    ) : StatementImportUiState

    data class Done(
        val imported: Int,
        val duplicatesSkipped: Int,
        /** Lines bank sync had already stored, so these were not added again. */
        val alreadySynced: Int
    ) : StatementImportUiState
    data class Error(val message: String) : StatementImportUiState
}

@HiltViewModel
class StatementImportViewModel @Inject constructor(
    private val fileReader: StatementFileReader,
    private val importService: StatementImportService,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow<StatementImportUiState>(StatementImportUiState.Idle)
    val state: StateFlow<StatementImportUiState> = _state.asStateFlow()

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
                    StatementImportUiState.Error(outcome.failure.toMessage(outcome.detail))

                is StatementParseOutcome.Parsed -> {
                    val result = outcome.result
                    StatementImportUiState.Preview(
                        rows = result.rows.map { it.copy(bankCode = detectedBank) },
                        skippedRows = result.skippedRows,
                        unitNote = if (result.columns.amountsAreMinorUnits) {
                            "Amounts were read as kopecks and converted to hryvnias."
                        } else {
                            null
                        },
                        bankCode = detectedBank,
                        bankUnresolved = detectedBank == null
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

    fun confirm() {
        val preview = _state.value as? StatementImportUiState.Preview ?: return
        // Refuse rather than filing the file under an unnamed bank: the whole point of the
        // column is being able to filter by bank later, and an unrecognised file has to be
        // attributed deliberately.
        if (preview.bankCode == null) {
            _state.value = StatementImportUiState.Error("Choose which bank this statement is from.")
            return
        }
        viewModelScope.launch {
            _state.value = StatementImportUiState.Working
            val uid = authRepository.currentUid.first()
            if (uid == null) {
                _state.value = StatementImportUiState.Error("Sign in to import")
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                importService.import(uid, preview.rows)
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

private fun StatementImportFailure.toMessage(detail: String? = null): String {
    val base = when (this) {
        StatementImportFailure.UNREADABLE_FILE -> "Could not read that file."
        StatementImportFailure.UNSUPPORTED_FORMAT ->
            "That file format is not supported. Export the statement as XLSX, CSV or PDF."
        StatementImportFailure.EMPTY_FILE -> "That statement has no transactions."
        StatementImportFailure.NO_HEADER_ROW -> "Could not find the column headers in that file."
        StatementImportFailure.NO_DATE_COLUMN -> "Could not find a date column in that file."
        StatementImportFailure.NO_AMOUNT_COLUMN -> "Could not find an amount column in that file."
        StatementImportFailure.TOO_LARGE -> "That file is too large to import."
    }
    return if (detail.isNullOrBlank()) base else "$base\n$detail"
}

/** Preview rows render with the same short format the transaction list uses. */
fun formatPreviewDate(timestamp: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(timestamp))
