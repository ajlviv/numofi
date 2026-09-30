package com.financetracker.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.financetracker.data.AppDatabase
import com.financetracker.data.backup.BackupExporter
import com.financetracker.data.backup.BackupSnapshot
import com.financetracker.model.BankCode
import com.financetracker.model.BankEntity
import com.financetracker.model.BondEntity
import com.financetracker.model.BondTradeEntity
import com.financetracker.model.BondTradeSide
import com.financetracker.model.SearchText
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
 * Exercises [BackupExporter] against a real database, because what it has to get right is
 * which rows end up in the file — a question a fake DAO would agree with either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupExporterTest {

    private lateinit var db: AppDatabase
    private lateinit var exporter: BackupExporter

    private val mine = "uid-mine"
    private val theirs = "uid-theirs"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        exporter = BackupExporter(db.transactionDao(), db.bankDao(), db.bondDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun store(
        userId: String,
        title: String,
        searchText: String? = null,
        timestamp: Long = 1_700_000_000_000L
    ) = db.transactionDao().insert(
        TransactionEntity(
            userId = userId,
            title = title,
            amount = 10.0,
            type = TransactionType.EXPENSE,
            category = "groceries",
            timestamp = timestamp,
            bankCode = BankCode.MONOBANK,
            searchText = searchText
        )
    )

    @Test
    fun `only the signed-in account's transactions are included`() = runTest {
        store(mine, "Кава")
        store(theirs, "Someone else's coffee")

        val snapshot = exporter.snapshot(mine, now = 1_700_000_000_000L)

        assertEquals(listOf("Кава"), snapshot.transactions.map { it.title })
    }

    @Test
    fun `the snapshot records who and when it was taken`() = runTest {
        val snapshot = exporter.snapshot(mine, now = 1_700_000_123_456L)

        assertEquals(BackupSnapshot.FORMAT, snapshot.format)
        assertEquals(mine, snapshot.uid)
        assertEquals(1_700_000_123_456L, snapshot.createdAt)
    }

    @Test
    fun `banks are taken whole, including archived ones`() = runTest {
        db.bankDao().insert(BankEntity(BankCode.MONOBANK, "Monobank", 0, archived = true, builtIn = true))
        db.bankDao().insert(BankEntity(BankCode.UKRSIBBANK, "Ukrsibbank", 1, builtIn = true))

        val snapshot = exporter.snapshot(mine, now = 0L)

        assertEquals(2, snapshot.banks.size)
        // An archived bank still has transactions pointing at it, so dropping it from the
        // backup would take the name of those rows with it.
        assertTrue(snapshot.banks.any { it.code == BankCode.MONOBANK && it.archived })
    }

    @Test
    fun `bonds and their trades travel whole, oldest trade first`() = runTest {
        db.bondDao().insertIfAbsent(BondEntity("UA3501234567", "ОвДП 24/Б", 1000.0, "UAH"))
        db.bondDao().insertTrade(
            BondTradeEntity(
                isin = "UA3501234567",
                side = BondTradeSide.BUY,
                quantity = 3,
                price = 1020.0,
                tradeDate = 1_700_000_000_000L
            )
        )
        // Inserted out of order on purpose: the order in the file is the order a position is
        // folded in, and a restore that reversed it would report a different cost basis for
        // the same two trades.
        db.bondDao().insertTrade(
            BondTradeEntity(
                isin = "UA3501234567",
                side = BondTradeSide.SELL,
                quantity = 1,
                price = 1040.0,
                tradeDate = 1_600_000_000_000L
            )
        )

        val snapshot = exporter.snapshot(mine, now = 0L)

        assertEquals(listOf("UA3501234567"), snapshot.bonds.map { it.isin })
        assertEquals(
            listOf(BondTradeSide.SELL, BondTradeSide.BUY),
            snapshot.bondTrades.map { it.side }
        )
    }

    @Test
    fun `a stored search haystack is copied out as written, not rebuilt`() = runTest {
        // The haystack was folded when the bank was still called one thing. Renaming the bank
        // deliberately does not reach back into stored rows, so the two have drifted apart by
        // design and the backup has to preserve the row's own copy of the old name.
        val baked = SearchText.of("Кава", null, "groceries", com.financetracker.model.BankRef(BankCode.MONOBANK, "Monobank"), null)
        store(mine, "Кава", searchText = baked)
        db.bankDao().insert(BankEntity(BankCode.MONOBANK, "Mono", 0, builtIn = true))

        val snapshot = exporter.snapshot(mine, now = 0L)

        assertEquals(baked, snapshot.transactions.single().searchText)
    }

    @Test
    fun `an account with nothing in it still produces a snapshot`() = runTest {
        val snapshot = exporter.snapshot(mine, now = 0L)

        assertEquals(emptyList<TransactionEntity>(), snapshot.transactions)
    }
}
