package com.financetracker.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.model.BondPosition
import com.financetracker.model.Transaction
import com.financetracker.repository.AuthRepository
import com.financetracker.repository.BankRepository
import com.financetracker.repository.BondRepository
import com.financetracker.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
     * Holdings, folded from trades. Not scoped to a user, because the bonds an app knows
     * about are the same for everyone who signs in on the device — they are public terms,
     * not anyone's data, and the trades that produce a position are already only ever
     * written by the person making them.
     */
    val positions: StateFlow<List<BondPosition>> = bondRepository.observePositions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun deleteTransaction(id: Long) {
        viewModelScope.launch {
            runCatching { transactionRepository.deleteTransaction(id) }
                .onFailure { _message.value = it.message ?: "Could not delete the transaction" }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
