package com.financetracker.testing

import com.financetracker.data.BankDao
import com.financetracker.model.BankEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A [BankDao] that holds a fixed list and refuses every write.
 *
 * Most callers of the bank list only ever read it, to turn a stored code into a name. Those
 * tests care about what the resolved name does to a row, not about the database, so a real
 * SQLite schema would only add a Robolectric runner and a migration to assert nothing.
 * The mutations throw rather than quietly succeeding, so a test that starts needing one
 * fails loudly instead of passing against a list nothing ever changed.
 */
class FakeBankDao(rows: List<BankEntity> = BankEntity.BUILT_IN) : BankDao {

    val rows: List<BankEntity> = rows

    override fun observeAll(): Flow<List<BankEntity>> = flowOf(rows)

    override fun observeActive(): Flow<List<BankEntity>> =
        flowOf(rows.filterNot(BankEntity::archived))

    override suspend fun getAllOnce(): List<BankEntity> = rows

    override suspend fun getByCode(code: String): BankEntity? = rows.firstOrNull { it.code == code }

    override suspend fun insert(bank: BankEntity): Unit = error("read-only")

    override suspend fun rename(code: String, displayName: String): Int = error("read-only")

    override suspend fun setArchived(code: String, archived: Boolean): Int = error("read-only")

    override suspend fun setPosition(code: String, position: Int): Int = error("read-only")

    override fun referencedBankCodes(): Flow<List<String>> =
        flowOf(rows.map(BankEntity::code))

    override suspend fun nextPosition(): Int = error("read-only")
}
