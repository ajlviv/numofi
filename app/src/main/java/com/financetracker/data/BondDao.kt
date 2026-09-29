package com.financetracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.financetracker.model.BondEntity
import com.financetracker.model.BondTradeEntity
import kotlinx.coroutines.flow.Flow

/**
 * The bond catalogue and the trades in it.
 *
 * No position query lives here. A position is folded from [getTrades] in Kotlin rather than
 * computed in SQL, because a weighted average that has to survive an empty position and a
 * bond the user only half-sold is arithmetic, and SQL would either get the rounding subtly
 * different from the UI or need the same code twice. There is no stored position table to
 * fall out of step with the trades; that is the point.
 */
@Dao
interface BondDao {

    @Query("SELECT * FROM bonds ORDER BY name, isin")
    fun observeBonds(): Flow<List<BondEntity>>

    @Query("SELECT * FROM bonds ORDER BY name, isin")
    suspend fun getBondsOnce(): List<BondEntity>

    @Query("SELECT * FROM bonds WHERE isin = :isin")
    fun observeBond(isin: String): Flow<BondEntity?>

    @Query("SELECT * FROM bonds WHERE isin = :isin")
    suspend fun getByIsin(isin: String): BondEntity?

    /**
     * Replaces a bond's terms only where it already exists.
     *
     * [OnConflictStrategy.IGNORE] rather than `REPLACE` because `REPLACE` is a delete
     * followed by an insert, and a cascade delete would take every trade in that bond with
     * it. A bond's terms are editable; its history is not, and this is what keeps the
     * distinction from being a detail of the SQL.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(bond: BondEntity): Long

    /**
     * Updates the descriptive terms of a bond the user already has.
     *
     * Not the ISIN, which is the primary key and the thing a trade refers to. A correction
     * to a name or a coupon is a convenience; a corrected ISIN is a different instrument and
     * should go in as a new one.
     */
    @Update
    suspend fun update(bond: BondEntity)

    @Insert
    suspend fun insertTrade(trade: BondTradeEntity): Long

    @Query("SELECT * FROM bond_trades ORDER BY tradeDate DESC, id DESC")
    fun observeTrades(): Flow<List<BondTradeEntity>>

    /**
     * Every trade, oldest first.
     *
     * Oldest first because the position fold is a running total: buys add, sells subtract,
     * and averaging a sale in the wrong order produces a different cost basis for the same
     * set of trades.
     */
    @Query("SELECT * FROM bond_trades ORDER BY tradeDate ASC, id ASC")
    suspend fun getTrades(): List<BondTradeEntity>

    @Query("SELECT * FROM bond_trades WHERE isin = :isin ORDER BY tradeDate ASC, id ASC")
    suspend fun getTradesFor(isin: String): List<BondTradeEntity>

    @Query("SELECT * FROM bond_trades WHERE transactionId = :transactionId LIMIT 1")
    suspend fun getTradeForTransaction(transactionId: Long): BondTradeEntity?
}
