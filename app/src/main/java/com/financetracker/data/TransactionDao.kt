package com.financetracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.financetracker.model.CardRef
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
     * The list behind the transaction screen: every filter is optional.
     *
     * The nullable-single pattern this used to rely on has no equivalent for a set, because
     * `col = NULL` is never true but `col IN ()` is a syntax error. So each collection
     * filter carries its own size as a guard: a size of 0 makes the predicate trivially true
     * and leaves the list irrelevant, which is exactly what "no constraint" means. The
     * counts are supplied by [getFiltered] rather than by callers, so they cannot drift
     * away from the lists they guard.
     *
     * The date bounds are half-open. [getFiltered] converts the inclusive end of the range
     * into the start of the following day, so a transaction in the last second of the final
     * day is still inside the range and nothing needs to know about the length of a day.
     *
     * `search` matches the pre-folded `searchText` column rather than the columns
     * themselves: SQLite folds case for ASCII only, and the descriptions are Cyrillic, so
     * matching here would miss anything not already typed in one exact case. See
     * [com.financetracker.model.SearchText].
     */
    @Query(
        "SELECT * FROM transactions WHERE userId = :userId " +
            "AND (:bankCount = 0 OR bankCode IN (:bankCodes)) " +
            "AND (:cardCount = 0 OR cardLabel IN (:cardLabels)) " +
            "AND (:typeCount = 0 OR type IN (:types)) " +
            "AND (:fromMillis IS NULL OR timestamp >= :fromMillis) " +
            "AND (:toMillis IS NULL OR timestamp < :toMillis) " +
            "AND (:search IS NULL OR searchText LIKE '%' || :search || '%') " +
            "ORDER BY timestamp DESC"
    )
    fun getFilteredQuery(
        userId: String,
        bankCodes: List<String>,
        bankCount: Int,
        cardLabels: List<String>,
        cardCount: Int,
        types: List<TransactionType>,
        typeCount: Int,
        fromMillis: Long?,
        toMillis: Long?,
        search: String?
    ): Flow<List<TransactionEntity>>

    /**
     * Normalises the search term in Kotlin, then delegates. Doing the folding here rather
     * than in the caller means `LIKE` is left as a plain substring test, and a query like
     * "АПТЕКА" cannot silently return nothing just because it was not pre-lowercased. A
     * blank term becomes null, so clearing the field drops the filter instead of matching
     * everything.
     */
    fun getFiltered(
        userId: String,
        bankCodes: List<String> = emptyList(),
        cardLabels: List<String> = emptyList(),
        types: List<TransactionType> = emptyList(),
        fromMillis: Long? = null,
        toMillis: Long? = null,
        search: String? = null
    ): Flow<List<TransactionEntity>> = getFilteredQuery(
        userId,
        bankCodes,
        bankCodes.size,
        cardLabels,
        cardLabels.size,
        types,
        types.size,
        fromMillis,
        toMillis,
        search?.trim()?.lowercase()?.ifBlank { null }
    )

    /**
     * Every card the user has transacted with, tagged with the bank that issued it.
     *
     * One query serves both the card dropdown and the pruning of a card selection that a
     * change of banks has invalidated. Rows with no card are excluded, so "no card" is never
     * offered as one.
     */
    @Query(
        "SELECT DISTINCT bankCode, cardLabel FROM transactions " +
            "WHERE userId = :userId AND cardLabel IS NOT NULL ORDER BY cardLabel"
    )
    fun getCardRefs(userId: String): Flow<List<CardRef>>

    @Query("SELECT * FROM transactions WHERE userId = :userId AND type = :type ORDER BY timestamp DESC")
    fun getByType(userId: String, type: String): Flow<List<TransactionEntity>>

    // There is deliberately no "total income"/"total expense" query here. A SUM across the
    // whole table adds hryvnias to dollars and returns a figure that looks like a total but
    // is not one. Totals are grouped by currency instead; see
    // com.financetracker.model.totalsByCurrency.

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