package com.financetracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.financetracker.model.BankEntity
import kotlinx.coroutines.flow.Flow

/**
 * The user's bank list.
 *
 * Deliberately holds no name-uniqueness query. SQLite's `lower()` folds ASCII only, so a
 * SQL comparison would accept "ПриватБанк" beside "приватбанк" — the same reason
 * [com.financetracker.model.SearchText] is folded in Kotlin. [getAllOnce] exposes the raw
 * rows instead and the repository decides, which is cheap for a list of a handful of banks
 * and correct for every script.
 */
@Dao
interface BankDao {

    @Query("SELECT * FROM banks ORDER BY position, displayName")
    fun observeAll(): Flow<List<BankEntity>>

    /** What the import picker offers: archived banks are for history, not for new files. */
    @Query("SELECT * FROM banks WHERE archived = 0 ORDER BY position, displayName")
    fun observeActive(): Flow<List<BankEntity>>

    @Query("SELECT * FROM banks ORDER BY position, displayName")
    suspend fun getAllOnce(): List<BankEntity>

    @Query("SELECT * FROM banks WHERE code = :code")
    suspend fun getByCode(code: String): BankEntity?

    /**
     * Aborts rather than replacing on a code collision, so a generated code that somehow
     * already exists fails loudly instead of overwriting a bank the user can see.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(bank: BankEntity)

    @Query("UPDATE banks SET displayName = :displayName WHERE code = :code")
    suspend fun rename(code: String, displayName: String): Int

    @Query("UPDATE banks SET archived = :archived WHERE code = :code")
    suspend fun setArchived(code: String, archived: Boolean): Int

    /**
     * A single position write, not a move.
     *
     * Writing one bank onto a position another bank already holds produces a tie, which
     * `ORDER BY position, displayName` then breaks by name — so the bank silently lands
     * somewhere the user did not ask for. Reordering is therefore done by swapping two
     * positions inside one transaction, never by a lone call to this.
     */
    @Query("UPDATE banks SET position = :position WHERE code = :code")
    suspend fun setPosition(code: String, position: Int): Int

    /**
     * Rewrites the whole order in one transaction, assigning each bank its slot.
     *
     * Reordering is expressed as a dense renumbering rather than a swap of two positions
     * because a swap cannot repair a list that already contains ties, and a tie silently
     * puts a bank somewhere the user did not ask for. The list is a handful of rows, so
     * writing them all is cheaper than reasoning about which pairs need fixing.
     */
    @Transaction
    suspend fun replaceOrder(codes: List<String>) {
        codes.forEachIndexed { slot, code -> setPosition(code, slot) }
    }

    /**
     * Every bank code that any stored row names, reactively.
     *
     * Not scoped to a user, and deliberately so: the bank list is app-wide rather than
     * per-account, and the only use is deciding which archived banks the filter should keep
     * offering. Offering a bank that has no rows for this user costs one empty chip.
     */
    @Query("SELECT DISTINCT bankCode FROM transactions WHERE bankCode IS NOT NULL")
    fun referencedBankCodes(): Flow<List<String>>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM banks")
    suspend fun nextPosition(): Int
}
