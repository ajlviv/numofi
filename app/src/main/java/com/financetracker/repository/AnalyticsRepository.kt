package com.financetracker.repository

import com.financetracker.data.TransactionDao
import com.financetracker.model.TransactionEntity
import com.financetracker.model.TransactionType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnalyticsRepository @Inject constructor(
    private val dao: TransactionDao
) {

    suspend fun recordTransaction(
        title: String,
        amount: Double,
        type: TransactionType,
        category: String,
        userId: String
    ): Long {
        return dao.insert(
            TransactionEntity(
                userId = userId,
                title = title,
                amount = amount,
                type = type,
                category = category
            )
        )
    }
}
