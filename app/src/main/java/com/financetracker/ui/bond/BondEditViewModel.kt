package com.financetracker.ui.bond

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.R
import com.financetracker.model.Bond
import com.financetracker.repository.BondRepository
import com.financetracker.repository.UpdateTermsResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Wiring for the terms-edit screen.
 *
 * Thin by design, like every other ViewModel here: it collects what the form typed, hands it to
 * [BondRepository.updateTerms], and turns the outcome into an event or a sentence. The refusal
 * sentence is carried as a resource id because a ViewModel cannot call `stringResource`.
 */
@HiltViewModel
class BondEditViewModel @Inject constructor(
    private val bondRepository: BondRepository
) : ViewModel() {

    /**
     * Whether the bond already has trades, and therefore a fixed denomination.
     *
     * True until [load] resolves it, so the currency field cannot be tapped through in the
     * window before the count comes back. A bond that turns out to be tradeless flips to
     * editable; every other one stays as it was drawn.
     */
    private val _currencyLocked = MutableStateFlow(true)
    val currencyLocked: StateFlow<Boolean> = _currencyLocked.asStateFlow()

    private val _message = MutableStateFlow<Int?>(null)
    val message: StateFlow<Int?> = _message.asStateFlow()

    /**
     * Fires once per save that is finished, whatever it found.
     *
     * A channel rather than a flag: this ViewModel is scoped to the Activity, not to the form,
     * and MainScreen shows the form from an `if`, so a flag left true would close the next
     * opening on sight.
     */
    private val _saved = Channel<Unit>(Channel.BUFFERED)
    val saved: Flow<Unit> = _saved.receiveAsFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    fun load(isin: String) {
        viewModelScope.launch {
            _currencyLocked.value = bondRepository.hasTrades(isin)
        }
    }

    fun clearMessage() = _message.update { null }

    /** Drops a save that landed after the form was already gone. See [AddTransactionViewModel]. */
    fun onFormDismissed() {
        _saved.tryReceive()
    }

    /**
     * Writes the corrected terms.
     *
     * Both a real change and a form saved unchanged close the screen: nothing being different is
     * not a failure, and holding the form open to say so would be a worse answer than leaving.
     * Only the currency refusal keeps the user where they are, because the field they cannot
     * change is still on screen.
     */
    fun save(bond: Bond) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                when (bondRepository.updateTerms(bond)) {
                    UpdateTermsResult.Updated, UpdateTermsResult.Unchanged -> _saved.trySend(Unit)
                    UpdateTermsResult.CurrencyLocked ->
                        _message.value = R.string.bond_edit_currency_locked_message
                }
            } finally {
                _saving.value = false
            }
        }
    }
}
