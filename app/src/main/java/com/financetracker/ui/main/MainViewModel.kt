package com.financetracker.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.R
import com.financetracker.data.rates.ExchangeRateRepository
import com.financetracker.data.settings.DEFAULT_BASE_CURRENCY
import com.financetracker.data.settings.SettingsRepository
import com.financetracker.model.BondPosition
import com.financetracker.model.CountedTransactions
import com.financetracker.model.ExclusionRules
import com.financetracker.model.ExchangeRates
import com.financetracker.model.Transaction
import com.financetracker.model.TransactionKind
import com.financetracker.model.UpcomingTotals
import com.financetracker.model.upcoming
import com.financetracker.repository.AuthRepository
import com.financetracker.repository.BankRepository
import com.financetracker.repository.BondRepository
import com.financetracker.repository.RecurringPaymentRepository
import com.financetracker.repository.TransactionRepository
import java.time.LocalDate
import java.time.ZoneId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val transactionRepository: TransactionRepository,
    private val bondRepository: BondRepository,
    private val recurringPaymentRepository: RecurringPaymentRepository,
    private val rates: ExchangeRateRepository,
    private val settingsRepository: SettingsRepository,
    bankRepository: BankRepository
) : ViewModel() {

    /**
     * Code to display name, for the detail card.
     *
     * A row's bank is stored as a code, and the name lives in the user's bank list, so
     * something has to join them. The map is tiny and only changes when a bank is renamed.
     */
    val bankNames: StateFlow<Map<String, String>> = bankRepository.names
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Every read is scoped to the signed-in uid, so switching Google accounts swaps
     * the whole dataset. Emits an empty list when signed out.
     */
    val transactions: StateFlow<List<Transaction>> = authRepository.currentUid
        .filterNotNull()
        .flatMapLatest { uid -> transactionRepository.getTransactionsForUser(uid) }
        .map { entities -> entities.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The rows a total may be built from, and how many were held back.
     *
     * Kept beside [transactions] rather than applied to it. An excluded row is still a real
     * transaction: the user imported it, it is in their bank, and they have to be able to see
     * it, search it and delete it. Filtering this flow instead would drop those rows from the
     * dashboard's recent list as well as from the headline, which is a different feature and a
     * worse one — the ledger would quietly disagree with the bank.
     *
     * So the rule is applied here, once, at the point a total is produced, and the omission
     * count travels with the rows so the card can name what it left out.
     */
    val counted: StateFlow<CountedTransactions> = combine(
        transactions,
        settingsRepository.exclusionRules
    ) { rows, rules -> rules.select(rows) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CountedTransactions.ALL)

    /**
     * The same rules [counted] was derived from.
     *
     * Exposed rather than rebuilt at the call site so that the rows a total was built from and
     * the holdings it was about to add cannot come from two different rule sets. A bond
     * purchase is a cash row and a position, and a rule that takes the row away must take the
     * holding too — a total holding the cash of a purchase out while adding its nominal back is
     * a total that grows by everything paid.
     */
    val exclusionRules: StateFlow<ExclusionRules> = settingsRepository.exclusionRules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExclusionRules())

    /**
     * Holdings, folded from trades. Not scoped to a user, because the bonds an app knows
     * about are the same for everyone who signs in on the device — they are public terms,
     * not anyone's data, and the trades that produce a position are already only ever
     * written by the person making them.
     */
    val positions: StateFlow<List<BondPosition>> = bondRepository.observePositions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())



    /**
     * The next [DASHBOARD_UPCOMING_DAYS] days of commitments, converted into the base currency.
     */
    val upcomingPlans: StateFlow<UpcomingTotals> = authRepository.currentUid.filterNotNull()
        .flatMapLatest { uid ->
            combine(
                recurringPaymentRepository.observe(uid),
                exchangeRates,
                baseCurrency
            ) { list, ratesCache, base ->
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                upcoming(list, ratesCache, base, today, today.plusDays(DASHBOARD_UPCOMING_DAYS), zone)
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            UpcomingTotals(0.0, 0.0, emptyList(), ExchangeRates(emptyMap(), null, 0L), emptyList())
        )

    /**
     * Snackbar copy, as a resource id so the UI can resolve it in its own scope and locale.
     * Deliberately not the exception's message: that text is developer English and may leak
     * internals, so every failure surfaces the same fixed localized sentence instead.
     */
    private val _message = MutableStateFlow<Int?>(null)
    val message: StateFlow<Int?> = _message.asStateFlow()

    /** Always set; there is no state in which the dashboard has no total to show. */
    val baseCurrency: StateFlow<String> = rates.baseCurrency
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_BASE_CURRENCY)

    val exchangeRates: StateFlow<ExchangeRates> = rates.rates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExchangeRates(emptyMap(), null, 0L))

    /**
     * One fetch on the way in, at most a day apart.
     *
     * Deliberately not a timer and not WorkManager: NBU publishes one rate a day, so the only
     * question is whether this screen has been opened since the last one. Failure is silent and
     * leaves the cache alone — a dashboard that cannot reach the network still has to render
     * the balances it already knows.
     */
    fun refreshRatesIfStale() {
        viewModelScope.launch { rates.refreshIfStale() }
    }

    fun refreshRates() {
        viewModelScope.launch { rates.refresh() }
    }

    fun deleteTransaction(id: Long) {
        viewModelScope.launch {
            runCatching { transactionRepository.deleteTransaction(id) }
                .onFailure { _message.value = R.string.main_delete_failed }
        }
    }

    /**
     * Retypes a row the user has corrected.
     *
     * The escape hatch for a type this app inferred rather than read. A bank does not say
     * whether one movement was income, spending, or a move between the user's own accounts,
     * so a pairing that guesses wrong is only acceptable because it can be undone here.
     */
    fun setTransactionType(id: Long, kind: TransactionKind) {
        viewModelScope.launch {
            runCatching { transactionRepository.setType(id, kind) }
                .onFailure { _message.value = R.string.detail_type_change_failed }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private companion object {
        const val DASHBOARD_UPCOMING_DAYS = 14L
    }
}
