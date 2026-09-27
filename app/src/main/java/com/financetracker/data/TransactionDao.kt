package com.financetracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.financetracker.model.TransactionEntity
import com.financetracker.model.TransactionType
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(transactions: List<TransactionEntity>): List<Long>

    @Update
    suspend fun update(transaction: TransactionEntity): Int

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("DELETE FROM transactions WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String): Int

    @Query("SELECT * FROM transactions WHERE userId = :userId ORDER BY timestamp DESC")
    fun getAllForUser(userId: String): Flow<List<TransactionEntity>>

    /**
     * The list behind the transaction screen: every filter is optional, and Room binds a
     * null to SQL NULL. Since `col = NULL` is never true, an unset filter simply drops out
     * of the predicate, which is what lets one query serve every combination instead of
     * needing a variant per selection.
     *
     * `search` matches the pre-folded `searchText` column rather than the columns
     * themselves: SQLite folds case for ASCII only, and the descriptions are Cyrillic, so
     * matching here would miss anything not already typed in one exact case. See
     * [com.financetracker.model.SearchText].
     */
    @Query(
        "SELECT * FROM transactions WHERE userId = :userId " +
            "AND (:bankCode IS NULL OR bankCode = :bankCode) " +
            "AND (:cardLabel IS NULL OR cardLabel = :cardLabel) " +
            "AND (:type IS NULL OR type = :type) " +
            "AND (:search IS NULL OR searchText LIKE '%' || :search || '%') " +
            "ORDER BY timestamp DESC"
    )
    fun getFilteredQuery(
        userId: String,
        bankCode: String?,
        cardLabel: String?,
        type: TransactionType?,
        search: String?
    ): Flow<List<TransactionEntity>>

    /**
     * Normalises the term in Kotlin, then delegates. Doing the folding here rather than in
     * the caller means `LIKE` is left as a plain substring test, and a query like "АПТЕКА"
     * cannot silently return nothing just because it was not pre-lowercased. A blank term
     * becomes null, so clearing the field drops the filter instead of matching everything.
     */
    fun getFiltered(
        userId: String,
        bankCode: String?,
        cardLabel: String?,
        type: TransactionType?,
        search: String?
    ): Flow<List<TransactionEntity>> = getFilteredQuery(
        userId,
        bankCode,
        cardLabel,
        type,
        search?.trim()?.lowercase()?.ifBlank { null }
    )

    /**
     * Feeds the card filter, narrowed to one bank when one is selected.
     *
     * A card belongs to the bank that issued it, so offering every card while a bank is
     * selected would present combinations that cannot exist and lead to an empty list with
     * no obvious cause. Nulls are excluded, so "no card" is not offered as one; a null
     * [bankCode] means no bank is selected, and every card is offered.
     */
    @Query(
        "SELECT DISTINCT cardLabel FROM transactions " +
            "WHERE userId = :userId AND cardLabel IS NOT NULL " +
            "AND (:bankCode IS NULL OR bankCode = :bankCode) ORDER BY cardLabel"
    )
    fun getDistinctCardLabels(userId: String, bankCode: String?): Flow<List<String>>

    @Query("SELECT * FROM transactions WHERE userId = :userId AND type = :type ORDER BY timestamp DESC")
    fun getByType(userId: String, type: String): Flow<List<TransactionEntity>>

    // There is deliberately no "total income"/"total expense" query here. A SUM across the
    // whole table adds hryvnias to dollars and returns a figure that looks like a total but
    // is not one. Totals are grouped by currency instead; see
    // com.financetracker.ui.dashboard.totalsByCurrency.

    @Query("SELECT * FROM transactions WHERE userId = :userId ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentTransactions(userId: String, limit: Int): Flow<List<TransactionEntity>>

    /** External ids already imported for this account, used to skip re-imported rows. */
    @Query("SELECT externalId FROM transactions WHERE userId = :userId AND externalId IS NOT NULL")
    suspend fun getExternalIdsForUser(userId: String): List<String>

    /**
     * Rows in a time range, for matching statement lines against transactions that
     * arrived through bank sync. Deduplicating on external id alone is not enough:
     * a statement row has no bank id, so the same payment synced as `monobank_123`
     * and later imported from a CSV would look unrelated to it.
     */
    @Query(
        "SELECT * FROM transactions WHERE userId = :userId " +
            "AND timestamp BETWEEN :from AND :to ORDER BY timestamp DESC"
    )
    suspend fun getInRange(userId: String, from: Long, to: Long): List<TransactionEntity>
}