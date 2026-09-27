package com.financetracker.repository

import com.financetracker.data.TransactionDao
import com.financetracker.model.TransactionEntity
import com.financetracker.model.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepository @Inject constructor(
    private val dao: TransactionDao
) {

    fun searchTransactions(query: String, userId: String): Flow<List<TransactionEntity>> =
        dao.getAllForUser(userId).map { transactions ->
            transactions.filter { transaction ->
                transaction.title.contains(query, ignoreCase = true) ||
                    transaction.category.contains(query, ignoreCase = true)
            }
        }

    fun searchByType(type: TransactionType, userId: String): Flow<List<TransactionEntity>> =
        dao.getByType(userId, type.name)
}
