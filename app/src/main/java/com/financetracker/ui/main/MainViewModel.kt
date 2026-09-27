package com.financetracker.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.model.Transaction
import com.financetracker.model.TransactionEntity
import com.financetracker.repository.AuthRepository
import com.financetracker.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val transactionRepository: TransactionRepository
) : ViewModel() {

    /**
     * Every read is scoped to the signed-in uid, so switching Google accounts swaps
     * the whole dataset. Emits an empty list when signed out.
     */
    val transactions: StateFlow<List<Transaction>> = authRepository.currentUid
        .filterNotNull()
        .flatMapLatest { uid -> transactionRepository.getTransactionsForUser(uid) }
        .map { entities -> entities.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Attaches the transaction to the active account; ignored when signed out. */
    fun addTransaction(transaction: Transaction) {
        viewModelScope.launch {
            val uid = authRepository.currentUid.firstOrNull() ?: return@launch
            runCatching {
                transactionRepository.addTransaction(
                    TransactionEntity.fromDomain(transaction, uid)
                )
            }.onFailure { _message.value = it.message ?: "Could not save the transaction" }
        }
    }

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
