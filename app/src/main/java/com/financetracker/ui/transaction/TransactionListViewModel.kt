package com.financetracker.ui.transaction

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.data.TransactionDao
import com.financetracker.model.BankCode
import com.financetracker.model.CardRef
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
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

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
    val types: Set<TransactionType> = emptySet(),
    val from: LocalDate? = null,
    val to: LocalDate? = null
) {
    /** Unknown codes are dropped, so a stale value cannot conjure a bank of its own. */
    val normalizedBanks: Set<String> get() = bankCodes.mapNotNull(BankCode::normalize).toSet()

    val isActive: Boolean
        get() = bankCodes.isNotEmpty() || cardLabels.isNotEmpty() ||
            types.isNotEmpty() || from != null || to != null

    fun toggledBank(code: String) = copy(bankCodes = bankCodes.toggle(code))

    fun toggledCard(label: String) = copy(cardLabels = cardLabels.toggle(label))

    fun toggledType(type: TransactionType) = copy(types = types.toggle(type))

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

/** The cards a bank selection can offer, taken from the cards the user has actually used. */
private fun List<CardRef>.offeredCards(banks: Set<String>): List<String> =
    filter { banks.isEmpty() || BankCode.normalize(it.bankCode) in banks }
        .map(CardRef::cardLabel)
        .distinct()
        .sorted()

/** The periods offered beside the date picker, as inclusive day ranges ending today. */
enum class DatePreset(val label: String) {
    THIS_MONTH("This month"),
    LAST_MONTH("Last month"),
    LAST_30_DAYS("Last 30 days"),
    LAST_90_DAYS("Last 90 days"),
    YEAR_TO_DATE("Year to date");

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
            filter.normalizedBanks.sorted(),
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
     * The cards offered in the card dropdown, narrowed to the selected banks. A card belongs
     * to the bank that issued it, so this follows [TransactionFilter.bankCodes] rather than
     * listing every card the user has ever had.
     */
    val cardLabels: StateFlow<List<String>> = combine(
        _filter.map { it.normalizedBanks }.distinctUntilChanged(),
        _cardRefs
    ) { banks, refs -> refs.offeredCards(banks) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onSearchChange(value: String) {
        _search.value = value
    }

    fun onBankToggled(code: String) {
        val next = _filter.value.toggledBank(code)
        // Pruned in the same update as the bank itself, so the two can never disagree.
        val offered = _cardRefs.value.offeredCards(next.normalizedBanks).toSet()
        _filter.value = next.prunedTo(offered)
    }

    fun onCardToggled(label: String) {
        _filter.value = _filter.value.toggledCard(label)
    }

    fun onTypeToggled(type: TransactionType) {
        _filter.value = _filter.value.toggledType(type)
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
