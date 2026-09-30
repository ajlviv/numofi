package com.financetracker.repository

import com.financetracker.data.TransactionDao
import com.financetracker.data.backup.BackupReason
import com.financetracker.data.backup.BackupRequests
import com.financetracker.model.TransactionEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionRepository @Inject constructor(
    private val dao: TransactionDao,
    private val backupRequests: BackupRequests
) {

    fun getTransactionsForUser(userId: String): Flow<List<TransactionEntity>> =
        dao.getAllForUser(userId)

    fun getTransactionsByTypeAndUser(userId: String, type: String): Flow<List<TransactionEntity>> =
        dao.getByType(userId, type)

    suspend fun addTransaction(transaction: TransactionEntity): Long =
        dao.insert(transaction).also { backupRequests.requestUpload(BackupReason.TRANSACTION) }

    suspend fun updateTransaction(transaction: TransactionEntity): Int =
        dao.update(transaction).also { backupRequests.requestUpload(BackupReason.TRANSACTION) }

    suspend fun deleteTransaction(id: Long): Int =
        dao.delete(id).also { backupRequests.requestUpload(BackupReason.TRANSACTION) }

    /**
     * Deletes every row for one account.
     *
     * The backup is asked for even though nothing here is uid-scoped beyond this call: the
     * rows that go are the account's, and a file left holding them would be a copy of
     * something the user has just said they no longer have on the device.
     *
     * The bond trades that went with those rows are removed by the foreign key cascade, and
     * that is counted as a change too — the trade list is part of what is backed up.
     */
    suspend fun deleteAllForUser(userId: String): Int =
        dao.deleteAllForUser(userId).also { backupRequests.requestUpload(BackupReason.TRANSACTION) }
}
