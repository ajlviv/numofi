package com.financetracker.ui.transaction

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.R
import com.financetracker.data.TransactionDao
import com.financetracker.model.Bank
import com.financetracker.model.BankNames
import com.financetracker.model.BankRef
import com.financetracker.model.CardRef
import com.financetracker.model.Transaction
import com.financetracker.model.TransactionEntity
import com.financetracker.model.TransactionType
import com.financetracker.repository.AuthRepository
import com.financetracker.repository.BankRepository
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
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * The four choices the type chips offer.
 *
 * Single choice rather than the multi-select the bank and card filters use, and that is
 * deliberate. A bank or card is a property a transaction can have or not have, and a user
 * comparing two of them wants both. A type is a partition of the ledger: a row is money in,
 * money out, or money moved between accounts, and a list of money-in and money-moved rows is
 * not a category of anything. [ALL] is therefore an explicit choice rather than the absence
 * of one, so the chip row always has something selected and the two states cannot look alike.
 */
enum class TransactionTypeFilter(@StringRes val labelRes: Int, val type: TransactionType?) {
    ALL(R.string.common_all, null),
    INCOME(R.string.income, TransactionType.INCOME),
    EXPENSE(R.string.common_expense, TransactionType.EXPENSE),
    TRANSFERS(R.string.common_transfers, TransactionType.TRANSFER)
}

/**
 * The filter state behind the transaction screen.
 *
 * Every filter is a set, and an empty set means "no constraint", which is what lets the DAO
 * serve every combination from one query. The date range is two independently optional
 * bounds, so "everything since March" and "everything up to March" are both expressible
 * without inventing a bound the user did not ask for.
 *
 * The search term is deliberately not part of this. It is typed faster than a query should
 * run, so it lives in its own flow and is debounced separately; keeping a second copy here
 * would only create a way for the field and the list to disagree about what was typed.
 */
data class TransactionFilter(
    val bankCodes: Set<String> = emptySet(),
    val cardLabels: Set<String> = emptySet(),
    val type: TransactionTypeFilter = TransactionTypeFilter.ALL,
    val from: LocalDate? = null,
    val to: LocalDate? = null
) {
    val isActive: Boolean
        get() = bankCodes.isNotEmpty() || cardLabels.isNotEmpty() ||
            type != TransactionTypeFilter.ALL || from != null || to != null

    fun toggledBank(code: String) = copy(bankCodes = bankCodes.toggle(code))

    fun toggledCard(label: String) = copy(cardLabels = cardLabels.toggle(label))

    fun withType(type: TransactionTypeFilter) = copy(type = type)

    /**
     * What the query binds for the type chips.
     *
     * An empty list for [TransactionTypeFilter.ALL], which the DAO reads as no constraint.
     * Every other choice binds one type, so a bond purchase is listed under Transfers and
     * never under Expenses, however the user reads the word "out".
     */
    val types: List<TransactionType>
        get() = listOfNotNull(type.type)

    /**
     * Drops the cards the current bank selection cannot have.
     *
     * A card belongs to the bank that issued it, so keeping one selected after its bank was
     * deselected would leave a filter matching nothing with no visible reason. Pruning is
     * per card rather than clearing outright, so changing one bank does not discard the
     * cards that belong to the banks still selected.
     */
    fun prunedTo(offeredCards: Set<String>) = copy(cardLabels = cardLabels.intersect(offeredCards))

    fun startMillis(zone: ZoneId): Long? = from?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()

    /**
     * The exclusive end of the range is the start of the day *after* [to], not its last
     * instant. A day is not a fixed number of milliseconds across a DST boundary, and asking
     * the zone for the boundary sidesteps that entirely.
     */
    fun endMillis(zone: ZoneId): Long? = to?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

/**
 * The cards a bank selection can offer, taken from the cards the user has actually used.
 *
 * Codes are compared as stored. Nothing normalises them any more: the chips can only offer
 * a code that is in the bank list, so a value the user never chose cannot reach the query.
 */
private fun List<CardRef>.offeredCards(banks: Set<String>): List<String> =
    filter { banks.isEmpty() || it.bankCode in banks }
        .map(CardRef::cardLabel)
        .distinct()
        .sorted()

/** The periods offered beside the date picker, as inclusive day ranges ending today. */
enum class DatePreset(@StringRes val labelRes: Int) {
    THIS_MONTH(R.string.list_this_month),
    LAST_MONTH(R.string.list_last_month),
    LAST_30_DAYS(R.string.list_last_30_days),
    LAST_90_DAYS(R.string.list_last_90_days),
    YEAR_TO_DATE(R.string.list_year_to_date);

    fun range(today: LocalDate): Pair<LocalDate, LocalDate> = when (this) {
        THIS_MONTH -> today.withDayOfMonth(1) to today
        LAST_MONTH -> today.minusMonths(1).withDayOfMonth(1) to today.withDayOfMonth(1).minusDays(1)
        LAST_30_DAYS -> today.minusDays(29) to today
        LAST_90_DAYS -> today.minusDays(89) to today
        YEAR_TO_DATE -> today.withDayOfYear(1) to today
    }
}

/**
 * The filter resolved into the exact values the query binds.
 *
 * Built as one value so `distinctUntilChanged` can suppress a re-query: it compares by
 * equality, and the lists are sorted so the same selection always compares equal regardless
 * of the order the user happened to tap the chips in.
 */
private data class FilterQuery(
    val banks: List<String>,
    val cards: List<String>,
    val types: List<TransactionType>,
    val fromMillis: Long?,
    val toMillis: Long?
) {
    companion object {
        fun of(filter: TransactionFilter, zone: ZoneId) = FilterQuery(
            filter.bankCodes.sorted(),
            filter.cardLabels.sorted(),
            filter.types.sortedBy { it.name },
            filter.startMillis(zone),
            filter.endMillis(zone)
        )
    }
}

    /**
     * The bank and card line under a transaction, or null when there is nothing to say.
     *
     * A hand-entered row has neither a bank nor a card, and a bank with an unknown card should
     * not invite the reader to invent one, so both are left out rather than padded.
     *
     * [bank] is resolved by the caller, the only layer that knows the bank list. A null one
     * means the row has no bank at all, which is a different thing from a bank whose name
     * resolves to something unreadable, and only the former is left off the line.
     */
    fun Transaction.provenance(bank: BankRef?): String? {
        val name = bank?.label
        return when {
            name != null && cardLabel != null -> "$name • $cardLabel"
            name != null -> name
            else -> null
        }
    }

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class TransactionListViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val transactionDao: TransactionDao,
    bankRepository: BankRepository
) : ViewModel() {

    /**
     * Code to display name, for turning a row's stored code into something worth reading.
     *
     * Held here rather than resolved per row: the map is tiny, it changes only when a bank
     * is renamed, and resolving inside a list item would mean a database read per row.
     */
    val bankNames: StateFlow<Map<String, String>> = bankRepository.names
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The banks the filter offers: everything live, plus archived ones still in use. */
    val banks: StateFlow<List<Bank>> = bankRepository.filterBanks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The resolved bank for a row, or null when the row has no bank. */
    fun bankOf(code: String?): BankRef? = BankNames.ref(code, bankNames.value)

    /**
     * The zone every day boundary is resolved in. A transaction is stored as an instant, so
     * "the 14th" is only meaningful relative to a zone; the device's is the one the user is
     * looking at their statement in.
     */
    private val zone: ZoneId = ZoneId.systemDefault()

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

    /**
     * Kept as state rather than only as a derived flow so a bank toggle can read the cards
     * synchronously and prune in the same update. Reconciling against a list that arrives a
     * frame later would let the list flash empty in between.
     */
    private val _cardRefs = MutableStateFlow<List<CardRef>>(emptyList())

    init {
        viewModelScope.launch {
            authRepository.currentUid
                .filterNotNull()
                .flatMapLatest { uid -> transactionDao.getCardRefs(uid) }
                .collect { refs -> _cardRefs.value = refs }
        }
    }

    val transactions: StateFlow<List<Transaction>> = authRepository.currentUid
        .filterNotNull()
        .flatMapLatest { uid ->
            combine(
                debouncedSearch,
                _filter.map { FilterQuery.of(it, zone) }.distinctUntilChanged()
            ) { search, query ->
                transactionDao.getFiltered(
                    uid,
                    query.banks,
                    query.cards,
                    query.types,
                    query.fromMillis,
                    query.toMillis,
                    search
                )
            }.flatMapLatest { it }
        }
        .map { rows -> rows.map(TransactionEntity::toDomain) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Totals for exactly the rows [transactions] is showing, so the summary is a view of the
     * list rather than a parallel query that could fall out of step with the filters.
     */
    val summary: StateFlow<TransactionSummary> = transactions
        .map { TransactionSummary.of(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionSummary.EMPTY)

    /**
     * The cards offered in the card dropdown, narrowed to the selected banks. A card belongs
     * to the bank that issued it, so this follows [TransactionFilter.bankCodes] rather than
     * listing every card the user has ever had.
     */
    val cardLabels: StateFlow<List<String>> = combine(
        _filter.map { it.bankCodes }.distinctUntilChanged(),
        _cardRefs
    ) { banks, refs -> refs.offeredCards(banks) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onSearchChange(value: String) {
        _search.value = value
    }

    fun onBankToggled(code: String) {
        val next = _filter.value.toggledBank(code)
        // Pruned in the same update as the bank itself, so the two can never disagree.
        val offered = _cardRefs.value.offeredCards(next.bankCodes).toSet()
        _filter.value = next.prunedTo(offered)
    }

    fun onCardToggled(label: String) {
        _filter.value = _filter.value.toggledCard(label)
    }

    fun onTypeSelected(type: TransactionTypeFilter) {
        _filter.value = _filter.value.withType(type)
    }

    fun onDateRangeChanged(from: LocalDate?, to: LocalDate?) {
        _filter.value = _filter.value.copy(from = from, to = to)
    }

    fun onDatesCleared() {
        _filter.value = _filter.value.copy(from = null, to = null)
    }

    fun clear() {
        _search.value = ""
        _filter.value = TransactionFilter()
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 250L
    }
}
