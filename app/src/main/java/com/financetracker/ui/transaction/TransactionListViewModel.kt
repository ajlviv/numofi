package com.financetracker.ui.transaction

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.data.TransactionDao
import com.financetracker.model.BankCode
import com.financetracker.model.Transaction
import com.financetracker.model.TransactionEntity
import com.financetracker.model.TransactionType
import com.financetracker.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The filter state behind the transaction screen.
 *
 * The chip selections behind the transaction screen: bank, card and direction.
 *
 * The search term is deliberately not part of this. It is typed faster than a query should
 * run, so it lives in its own flow and is debounced separately; keeping a second copy here
 * would only create a way for the field and the list to disagree about what was typed.
 * Normalised on the way in, so an unknown bank code becomes null, which the DAO reads as
 * "no constraint" rather than as a filter matching nothing.
 */
data class TransactionFilter(
    val bankCode: String? = null,
    val cardLabel: String? = null,
    val type: TransactionType? = null
) {
    val normalizedBank: String? get() = BankCode.normalize(bankCode)
}

/**
 * The bank and card line under a transaction, or null when there is nothing to say.
 *
 * A hand-entered row has neither a bank nor a card, and a bank with an unknown card should
 * not invite the reader to invent one, so both are left out rather than padded.
 */
fun Transaction.provenance(): String? {
    val bank = bankCode?.let(BankCode::label)
    return when {
        bank != null && cardLabel != null -> "$bank • $cardLabel"
        bank != null -> bank
        else -> null
    }
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class TransactionListViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val transactionDao: TransactionDao
) : ViewModel() {

    private val _filter = MutableStateFlow(TransactionFilter())
    val filter: StateFlow<TransactionFilter> = _filter.asStateFlow()

    /**
     * Held apart from [filter] so the field shows what was typed immediately while the query
     * it drives lags by [SEARCH_DEBOUNCE_MILLIS]. This is the only copy of the term.
     */
    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search.asStateFlow()

    private val debouncedSearch: StateFlow<String?> = _search
        .map { it.trim().lowercase().takeIf(String::isNotEmpty) }
        .distinctUntilChanged()
        .debounce(SEARCH_DEBOUNCE_MILLIS)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val transactions: StateFlow<List<Transaction>> = authRepository.currentUid
        .filterNotNull()
        .flatMapLatest { uid ->
            combine(
                debouncedSearch,
                _filter.map { it.normalizedBank },
                _filter.map { it.cardLabel },
                _filter.map { it.type }
            ) { search, bank, card, type ->
                transactionDao.getFiltered(uid, bank, card, type, search)
            }.flatMapLatest { it }
        }
        .map { rows -> rows.map(TransactionEntity::toDomain) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The cards offered as filter chips, narrowed to the selected bank. A card belongs to
     * the bank that issued it, so this follows [TransactionFilter.bankCode] rather than
     * listing every card the user has ever had.
     */
    val cardLabels: StateFlow<List<String>> = authRepository.currentUid
        .filterNotNull()
        .flatMapLatest { uid ->
            _filter.map { it.normalizedBank }.flatMapLatest { bank ->
                transactionDao.getDistinctCardLabels(uid, bank)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onSearchChange(value: String) {
        _search.value = value
    }

    fun onBankSelected(code: String?) {
        // The card is cleared with the bank, because the offered cards change with it. A
        // selection kept across the change would stop matching anything while its chip had
        // already disappeared from the row, leaving an empty list with no visible reason.
        _filter.value = _filter.value.copy(bankCode = code, cardLabel = null)
    }

    fun onCardSelected(label: String?) {
        _filter.value = _filter.value.copy(cardLabel = label)
    }

    fun onTypeSelected(type: TransactionType?) {
        _filter.value = _filter.value.copy(type = type)
    }

    fun clear() {
        _search.value = ""
        _filter.value = TransactionFilter()
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 250L
    }
}
