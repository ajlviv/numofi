package com.financetracker.repository

import com.financetracker.data.TransactionDao
import com.financetracker.model.TransactionEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionRepository @Inject constructor(
    private val dao: TransactionDao
) {

    fun getTransactionsForUser(userId: String): Flow<List<TransactionEntity>> =
        dao.getAllForUser(userId)

    fun getTransactionsByTypeAndUser(userId: String, type: String): Flow<List<TransactionEntity>> =
        dao.getByType(userId, type)

    suspend fun addTransaction(transaction: TransactionEntity): Long = dao.insert(transaction)

    suspend fun updateTransaction(transaction: TransactionEntity): Int = dao.update(transaction)

    suspend fun deleteTransaction(id: Long): Int = dao.delete(id)

    suspend fun deleteAllForUser(userId: String): Int = dao.deleteAllForUser(userId)
}
