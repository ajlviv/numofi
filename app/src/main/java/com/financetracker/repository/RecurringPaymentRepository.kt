package com.financetracker.repository

import com.financetracker.data.RecurringPaymentDao
import com.financetracker.data.backup.BackupReason
import com.financetracker.data.backup.BackupRequests
import com.financetracker.model.RecurringPayment
import com.financetracker.model.RecurringPaymentEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's recurring payment schedules.
 *
 * Thin over [RecurringPaymentDao] by design: there is no arithmetic to own here — the occurrence
 * walk is [com.financetracker.model.Recurrence] and the projection is
 * [com.financetracker.model.upcoming] — so this exists to scope by account and to be the one
 * place a write asks for a backup, exactly as [TransactionRepository] is for transactions.
 *
 * Nothing here writes a [com.financetracker.model.TransactionEntity]. A schedule is a forecast;
 * making it produce ledger rows would merge prediction into fact.
 */
@Singleton
class RecurringPaymentRepository @Inject constructor(
    private val dao: RecurringPaymentDao,
    private val backupRequests: BackupRequests
) {

    /** The account's schedules, soonest first, as the UI sees them. */
    fun observe(userId: String): Flow<List<RecurringPayment>> =
        dao.observeForUser(userId).map { rows -> rows.map(RecurringPaymentEntity::toDomain) }

    suspend fun add(userId: String, payment: RecurringPayment): Long =
        dao.insert(RecurringPaymentEntity.fromDomain(payment, userId))
            .also { backupRequests.requestUpload(BackupReason.RECURRING) }

    suspend fun update(userId: String, payment: RecurringPayment): Int =
        dao.update(RecurringPaymentEntity.fromDomain(payment, userId))
            .also { backupRequests.requestUpload(BackupReason.RECURRING) }

    suspend fun delete(id: Long): Int =
        dao.delete(id).also { backupRequests.requestUpload(BackupReason.RECURRING) }

    /**
     * Moves a schedule in or out of the archive, and reports whether the row was there.
     *
     * The current value is read before writing because SQLite's `UPDATE` reports rows *matched*,
     * not rows *changed*: writing the flag it already holds would report one and ask for a
     * backup of nothing. Same reason and same shape as [BankRepository.setArchived], and the
     * negative case is tested there and here.
     *
     * Returns false only when there is no such row; a no-op on a row that already holds the
     * requested value returns true, because the caller asked for a state and now it holds.
     */
    suspend fun setArchived(id: Long, archived: Boolean): Boolean {
        val current = dao.isArchived(id) ?: return false
        if (current == archived) return true
        dao.setArchived(id, archived)
        backupRequests.requestUpload(BackupReason.RECURRING)
        return true
    }
}
