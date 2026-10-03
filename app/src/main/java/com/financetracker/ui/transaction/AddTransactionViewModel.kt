package com.financetracker.ui.transaction

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.model.Bank
import com.financetracker.model.BankRef
import com.financetracker.model.Bond
import com.financetracker.model.BondTradeSide
import com.financetracker.model.Transaction
import com.financetracker.model.TransactionEntity
import com.financetracker.model.TransactionInstant
import com.financetracker.model.TransactionType
import com.financetracker.repository.AuthRepository
import com.financetracker.repository.BankRepository
import com.financetracker.repository.BondRepository
import com.financetracker.repository.RecordTradeResult
import com.financetracker.repository.TransactionRepository
import androidx.annotation.StringRes
import com.financetracker.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

/**
 * Wiring for the add screen, which handles two unrelated things.
 *
 * The plain transaction path and the bond path share nothing but a screen, and the bond one
 * has to go through [BondRepository] so the trade and its cash row land together. Doing that
 * from the composable would mean reaching for a repository in a composable, so the split is
 * here instead.
 */
/**
 * A user-visible message from the add screen.
 *
 * The ViewModel cannot call `stringResource`, so fixed copy is carried as a resource id
 * plus its positional format arguments and resolved at the display site in the screen.
 * Repository and exception text is carried as [Raw] instead, because that is never translated.
 */
sealed interface AddMessage {
    /** Localized copy; [args] fill the positional placeholders in order. */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : AddMessage

}

@HiltViewModel
class AddTransactionViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val transactionRepository: TransactionRepository,
    private val bondRepository: BondRepository,
    bankRepository: BankRepository
) : ViewModel() {

    /** The banks a new row can be filed under. Archived ones are for history, not for new. */
    val banks: StateFlow<List<Bank>> = bankRepository.activeBanks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _bondSearch = MutableStateFlow<Bond?>(null)

    /**
     * The instrument a typed ISIN belongs to, once one has been found.
     *
     * Null is ambiguous between "nothing typed" and "not one I know", which is why the form
     * also keeps the raw text: it asks for the terms itself when this is null, rather than
     * refusing a purchase of something the app has never seen.
     */
    val bondSearch: StateFlow<Bond?> = _bondSearch.asStateFlow()

    private val _message = MutableStateFlow<AddMessage?>(null)
    val message: StateFlow<AddMessage?> = _message.asStateFlow()

    /**
     * Fires once per save that worked, and only to whoever is listening at the time.
     *
     * A `StateFlow<Boolean>` was wrong here, and made the form openable exactly once. This
     * ViewModel is scoped to the Activity, not to the form: MainScreen shows the form from an
     * `if` rather than from a navigation entry, so hiding the composable stops it being
     * composed without clearing its ViewModel. A flag set on a successful save therefore
     * stayed set, and the next time the form composed it read `true` on its very first frame
     * and navigated straight back out — a form that could be opened once.
     *
     * A channel has no replay, so an event can only be missed, never re-read.
     */
    private val _saved = Channel<Unit>(Channel.BUFFERED)
    val saved: Flow<Unit> = _saved.receiveAsFlow()

    /**
     * Throws away a save that finished after the form was already dismissed.
     *
     * Buffered rather than dropped, which is right for a rotation: the form comes back and
     * still navigates away for a save that really did happen. But a save that lands after the
     * user has backed out is stale, and leaving it buffered would close the next opening of
     * the form on sight — the same bug, in a much narrower window.
     *
     * Called on the way out rather than on the way in, because dismissal is what makes a
     * pending save stale. Discarding on open would also throw away a real save the user was
     * mid-way through when the screen rotated.
     */
    fun onFormDismissed() {
        _saved.tryReceive()
    }

    fun clearMessage() = _message.update { null }

    /** Looks up an ISIN as the user types it, ignoring blanks and unknown instruments. */
    fun findBond(rawIsin: String) {
        if (rawIsin.isBlank()) {
            _bondSearch.value = null
            return
        }
        viewModelScope.launch {
            _bondSearch.value = bondRepository.findBond(rawIsin)
        }
    }

    /**
     * True from the moment a save starts until it lands, one way or the other.
     *
     * Exists so the form can refuse a second tap. Without it, a double tap starts two writes
     * for what the user meant as one, and the second one files a duplicate row — in a ledger,
     * a duplicate is not a cosmetic problem, it is a wrong balance. It also keeps two
     * completions from queuing behind each other on the saved channel, where the second would
     * close the next opening of the form.
     */
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /**
     * Runs [save] unless one is already running.
     *
     * The check and the flag have to be set together, without a suspension point between
     * them, or two taps arriving in the same frame would both pass the check.
     */
    private fun saveIfIdle(save: suspend () -> Unit) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                save()
            } finally {
                _saving.value = false
            }
        }
    }

    fun saveTransaction(
        title: String,
        amount: Double,
        type: TransactionType,
        category: String,
        date: LocalDate,
        note: String?,
        bank: BankRef?,
        currencyCode: String
    ) = saveIfIdle {
        // Read here rather than passed in, so a row cannot be written against an account
        // that is not the one signed in. Every read is scoped the same way, so a row
        // saved under a stale uid would simply never be seen again.
        val uid = authRepository.currentUid.firstOrNull() ?: return@saveIfIdle
        // One reading of the clock and the zone, so the day "now" falls on is compared
        // against the chosen date in the same zone the row is finally stamped in.
        val zone = ZoneId.systemDefault()
        val enteredAt = LocalDateTime.now(zone)
        transactionRepository.addTransaction(
            TransactionEntity.fromDomain(
                transaction = Transaction(
                    title = title,
                    amount = amount,
                    type = type,
                    category = category,
                    // A row dated today carries the time it was entered, so it sorts above an
                    // import of the same day stamped at the bank's own hour. Any other day
                    // keeps the start of that day, so it groups under the right heading
                    // regardless of when it was typed. See [TransactionInstant].
                    timestamp = TransactionInstant.forEnteredDate(date, enteredAt, zone),
                    note = note,
                    currencyCode = currencyCode
                ),
                userId = uid,
                // Passed as a ref rather than a code so the row's search haystack is built
                // here, once, and cannot be written without the bank name in it.
                bank = bank
            )
        )
        _saved.trySend(Unit)
    }

    /**
     * Records a bond trade and the cash that moved with it.
     *
     * Reports failure back rather than navigating away regardless, because the two cases the
     * user can hit — an oversell and a field out of range — both mean the form is still open
     * in front of them holding what they typed.
     */
    fun saveBondTrade(
        bond: Bond,
        side: BondTradeSide,
        quantity: Int,
        price: Double,
        accruedInterest: Double,
        commission: Double,
        date: LocalDate,
        bank: BankRef?,
        settlementCurrency: String,
        settlementAmount: Double?
    ) = saveIfIdle {
        val uid = authRepository.currentUid.firstOrNull() ?: return@saveIfIdle
        when (
            val result = bondRepository.recordTrade(
                userId = uid,
                bond = bond,
                side = side,
                quantity = quantity,
                price = price,
                accruedInterest = accruedInterest,
                commission = commission,
                tradeDate = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                bank = bank,
                settlementCurrency = settlementCurrency,
                settlementAmount = settlementAmount
            )
        ) {
            is RecordTradeResult.Recorded -> _saved.trySend(Unit)
            is RecordTradeResult.Oversell ->
                _message.value = AddMessage.Res(R.string.add_oversell, listOf(result.held))
            is RecordTradeResult.Invalid ->
                _message.value = AddMessage.Res(bondProblemMessage(result.problem), emptyList())
        }
    }
}
