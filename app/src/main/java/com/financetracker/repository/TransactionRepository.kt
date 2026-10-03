package com.financetracker.repository

import com.financetracker.data.TransactionDao
import com.financetracker.data.backup.BackupReason
import com.financetracker.data.backup.BackupRequests
import com.financetracker.model.TransactionEntity
import com.financetracker.model.TransactionKind
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

    suspend fun addTransaction(transaction: TransactionEntity): Long =
        dao.insert(transaction).also { backupRequests.requestUpload(BackupReason.TRANSACTION) }

    suspend fun updateTransaction(transaction: TransactionEntity): Int =
        dao.update(transaction).also { backupRequests.requestUpload(BackupReason.TRANSACTION) }

    suspend fun deleteTransaction(id: Long): Int =
        dao.delete(id).also { backupRequests.requestUpload(BackupReason.TRANSACTION) }

    /**
     * Retypes one row, and reports whether it changed.
     *
     * The way a user corrects a type the app inferred. A bank sends one movement that may
     * honestly be read as income, as spending, or as money moving between their own
     * accounts, and nothing in the statement says which — so the transfer pairing has to
     * guess from the shape of the two rows. A wrong guess is recoverable here, which is the
     * only reason guessing is acceptable at all.
     *
     * The current row is read back first and compared, because SQLite reports the rows an
     * `UPDATE` matched rather than the rows whose columns changed: setting a value a row
     * already holds still counts as one row updated. Comparing here is what lets a pick that
     * changes nothing skip the write *and* the backup, which is the same rule every other
     * write path in the app follows — a dropped upload call fails silently, with the row
     * written and Drive quietly falling behind.
     *
     * Only the type and its direction move. The amount, the ids and the haystack are copied
     * through untouched: the type is a label on a fact the bank stated, not a replacement
     * for it, and a haystack is built once at write time and never rewritten.
     */
    suspend fun setType(id: Long, kind: TransactionKind): Boolean {
        val row = dao.getById(id) ?: return false
        if (row.type == kind.type && row.transferDirection == kind.direction) return false
        dao.update(row.copy(type = kind.type, transferDirection = kind.direction))
        backupRequests.requestUpload(BackupReason.TRANSACTION)
        return true
    }

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
