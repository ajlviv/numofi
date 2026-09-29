package com.financetracker.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.financetracker.model.BondEntity
import com.financetracker.model.BondTradeEntity
import com.financetracker.model.BondTradeSide
import com.financetracker.model.TransactionEntity
import com.financetracker.model.TransactionType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the database Room builds has to hold, checked against the real file.
 *
 * A trade and the cash row that moved with it are one fact written in two places, and the two
 * foreign keys are what keep them together: deleting a bond takes its trades with it, and
 * deleting a purchase's cash row takes the trade. Neither is visible in the entity
 * declarations — Room compiles them into SQL — so a dropped `onDelete = CASCADE` would
 * otherwise only surface as a position still counting bonds the user no longer bought.
 *
 * The database is opened through the same builder the app uses, rather than the schema being
 * re-created by hand: the generated SQL is the thing under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BondSchemaTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    /** One bond, one cash row, and the trade that joins them. */
    private suspend fun seedTrade(transactionId: Long): Long {
        db.bondDao().insertIfAbsent(
            BondEntity(isin = ISIN, name = "ОвДП 24/Б", nominal = 1000.0, nominalCurrency = "UAH")
        )
        return db.bondDao().insertTrade(
            BondTradeEntity(
                isin = ISIN,
                side = BondTradeSide.BUY,
                quantity = 1,
                price = 1000.0,
                tradeDate = TRADE_DATE,
                transactionId = transactionId
            )
        )
    }

    private suspend fun seedCashRow(): Long = db.transactionDao().insert(
        TransactionEntity(
            userId = "uid-1",
            title = "Купівля ОВДП",
            amount = 1000.0,
            type = TransactionType.TRANSFER,
            category = "bonds"
        )
    )

    private fun exec(sql: String) = db.openHelper.writableDatabase.execSQL(sql)

    private fun count(table: String): Int =
        db.query("SELECT COUNT(*) FROM $table", null).use { cursor ->
            if (cursor.moveToNext()) cursor.getInt(0) else -1
        }

    @Test
    fun `deleting a bond takes its trades with it`() = runTest {
        val tradeId = seedTrade(transactionId = seedCashRow())
        assertTrue(tradeId > 0)

        exec("DELETE FROM bonds WHERE isin = '$ISIN'")

        assertEquals(0, count("bond_trades"))
        // The cash row is the bond's business only through the trade, so it stays.
        assertEquals(1, count("transactions"))
    }

    @Test
    fun `deleting a trade's cash row takes the trade with it`() = runTest {
        val cashRow = seedCashRow()
        seedTrade(transactionId = cashRow)

        exec("DELETE FROM transactions WHERE id = $cashRow")

        assertEquals(0, count("bond_trades"))
        assertEquals(1, count("bonds"))
    }

    @Test
    fun `a trade cannot claim a cash row that is not there`() = runTest {
        // Without the constraint the trade would be written pointing at nothing, and the
        // position fold would count a purchase with no money behind it.
        val failed = runCatching { seedTrade(transactionId = MISSING_CASH_ROW) }.isFailure

        assertTrue(failed)
        assertEquals(0, count("bond_trades"))
    }

    @Test
    fun `deleting a trade on its own leaves the bond and the cash row alone`() = runTest {
        val cashRow = seedCashRow()
        val tradeId = seedTrade(transactionId = cashRow)

        exec("DELETE FROM bond_trades WHERE id = $tradeId")

        assertEquals(1, count("bonds"))
        assertEquals(1, count("transactions"))
    }

    private companion object {
        const val ISIN = "UA4000000001"
        const val TRADE_DATE = 1_757_000_000_000L
        const val MISSING_CASH_ROW = 999L
    }
}
