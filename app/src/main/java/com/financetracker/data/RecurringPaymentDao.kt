package com.financetracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.financetracker.model.RecurringPaymentEntity
import kotlinx.coroutines.flow.Flow

/**
 * The user's recurring payment schedules.
 *
 * Scoped by `userId` on every read, like [TransactionDao] and unlike [BankDao]: a schedule is
 * one person's commitments, and the account it belongs to is the whole reason it can be restored
 * onto the right device.
 *
 * No uniqueness query lives here. Two identical schedules are not an error the database should
 * decide on — the user may genuinely have two monthly payments of the same amount — so identity
 * (for the restore's "already here" test) is decided in Kotlin over the whole row, not by a
 * constraint here.
 */
@Dao
interface RecurringPaymentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(payment: RecurringPaymentEntity): Long

    @Update
    suspend fun update(payment: RecurringPaymentEntity): Int

    @Query("DELETE FROM recurring_payments WHERE id = :id")
    suspend fun delete(id: Long): Int

    /**
     * The account's schedules, soonest start first.
     *
     * Ordered by the anchor rather than by amount or title: the list is read to see what is
     * coming, and the anchor is the only field that answers that without running the projection.
     */
    @Query("SELECT * FROM recurring_payments WHERE userId = :userId ORDER BY startDate, id")
    fun observeForUser(userId: String): Flow<List<RecurringPaymentEntity>>

    /**
     * Every row for one account, for the backup export and the restore's "already here" test.
     *
     * A suspend read rather than a [Flow] because both callers want one snapshot, not a stream:
     * the exporter is writing a file, and the restorer is comparing against what is present at
     * this instant.
     */
    @Query("SELECT * FROM recurring_payments WHERE userId = :userId ORDER BY id")
    suspend fun getAllForUserOnce(userId: String): List<RecurringPaymentEntity>

    /**
     * The archived flag as stored, or null when no such row exists.
     *
     * Read before [setArchived] because SQLite's `UPDATE` reports rows *matched*, not rows
     * *changed*: re-archiving an already-archived row still returns 1, and a write that changed
     * nothing must not be mistaken for one that did. See [com.financetracker.repository.RecurringPaymentRepository].
     */
    @Query("SELECT archived FROM recurring_payments WHERE id = :id")
    suspend fun isArchived(id: Long): Boolean?

    @Query("UPDATE recurring_payments SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean): Int
}
