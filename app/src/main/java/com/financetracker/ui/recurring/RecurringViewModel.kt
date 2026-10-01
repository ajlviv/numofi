package com.financetracker.ui.recurring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.R
import com.financetracker.data.rates.ExchangeRateRepository
import com.financetracker.data.settings.DEFAULT_BASE_CURRENCY
import com.financetracker.model.Bank
import com.financetracker.model.ExchangeRates
import com.financetracker.model.RepeatFrequency
import com.financetracker.model.RecurringPayment
import com.financetracker.model.TransactionType
import com.financetracker.model.UpcomingTotals
import com.financetracker.model.upcoming as upcomingTotals
import com.financetracker.repository.AuthRepository
import com.financetracker.repository.BankRepository
import com.financetracker.repository.RecurringPaymentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * What the entry form hands back, before it becomes a stored schedule.
 *
 * Dates rather than millis: the form deals in days and the conversion belongs at the edge, in
 * [toPayment], where the zone is explicit.
 */
data class RecurringForm(
    val title: String,
    val amount: Double,
    val type: TransactionType,
    val category: String,
    val currencyCode: String,
    val bankCode: String?,
    val note: String?,
    val frequency: RepeatFrequency,
    val intervalCount: Int,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    /**
     * Whether the schedule is archived, carried through so that editing one cannot quietly
     * put it back in the list. The form has no control over the flag, so dropping it here would
     * make every save an un-archive.
     */
    val archived: Boolean = false
) {
    fun toPayment(id: Long?, zone: ZoneId) = RecurringPayment(
        id = id ?: 0,
        title = title.trim(),
        amount = amount,
        type = type,
        category = category,
        currencyCode = currencyCode,
        bankCode = bankCode,
        note = note,
        frequency = frequency,
        intervalCount = intervalCount,
        startDate = startDate.atStartOfDay(zone).toInstant().toEpochMilli(),
        endDate = endDate?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
        archived = archived
    )
}

/**
 * Wiring for the scheduled-payments screen.
 *
 * Thin by the project's convention: the occurrence walk is [com.financetracker.model.Recurrence]
 * and the totals are [upcomingTotals], so nothing here computes a date or an amount. What it
 * owns is the account scoping and the fixed projection window.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RecurringViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val repository: RecurringPaymentRepository,
    bankRepository: BankRepository,
    rates: ExchangeRateRepository
) : ViewModel() {

    val banks: StateFlow<List<Bank>> = bankRepository.activeBanks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val schedules: StateFlow<List<RecurringPayment>> = authRepository.currentUid
        .filterNotNull()
        .flatMapLatest { uid -> repository.observe(uid) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val baseCurrency: StateFlow<String> = rates.baseCurrency
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_BASE_CURRENCY)

    private val exchangeRates: StateFlow<ExchangeRates> = rates.rates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EMPTY_RATES)

    /**
     * The next [WINDOW_DAYS] days of commitments, converted into the base currency.
     *
     * Computed here rather than stored: a projected date is a function of the schedule and the
     * day it is asked about, so a stored copy would be wrong by the second day.
     */
    val upcoming: StateFlow<UpcomingTotals> =
        combine(schedules, exchangeRates, baseCurrency) { list, cache, base ->
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            upcomingTotals(list, cache, base, today, today.plusDays(WINDOW_DAYS), zone)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyUpcoming())

    /** Fixed localized copy, resolved at the display site. */
    private val _message = MutableStateFlow<Int?>(null)
    val message: StateFlow<Int?> = _message.asStateFlow()

    /** Emits once per successful write, so the form can close itself. */
    private val _saved = Channel<Unit>(Channel.BUFFERED)
    val saved: Flow<Unit> = _saved.receiveAsFlow()

    fun save(id: Long?, form: RecurringForm) {
        viewModelScope.launch {
            val uid = authRepository.currentUid.firstOrNull() ?: return@launch
            val payment = form.toPayment(id, ZoneId.systemDefault())
            runCatching {
                if (id == null) repository.add(uid, payment) else repository.update(uid, payment)
            }
                .onSuccess { _saved.trySend(Unit) }
                .onFailure { _message.value = R.string.recurring_save_failed }
        }
    }

    fun setArchived(schedule: RecurringPayment, archived: Boolean) {
        viewModelScope.launch { repository.setArchived(schedule.id, archived) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun clearMessage() {
        _message.value = null
    }

    private companion object {
        const val WINDOW_DAYS = 30L
        val EMPTY_RATES = ExchangeRates(emptyMap(), null, 0L)

        fun emptyUpcoming(): UpcomingTotals =
            UpcomingTotals(0.0, 0.0, emptyList(), EMPTY_RATES, emptyList())
    }
}
