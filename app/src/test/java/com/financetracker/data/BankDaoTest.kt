package com.financetracker.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.financetracker.model.BankEntity
import com.financetracker.model.TransactionEntity
import com.financetracker.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [BankDao] against a real SQLite database.
 *
 * The queries here are small, but two of them carry the behaviour the whole feature rests
 * on: `archived = 0` filtering, and the `ORDER BY position` the reorder buttons depend on.
 * Both are things a mock would agree with regardless of the SQL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BankDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: BankDao

    private fun bank(
        code: String,
        name: String,
        position: Int,
        archived: Boolean = false,
        builtIn: Boolean = false
    ) = BankEntity(
        code = code,
        displayName = name,
        position = position,
        archived = archived,
        builtIn = builtIn
    )

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.bankDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedTransactions(vararg codes: String?) {
        codes.forEachIndexed { index, code ->
            db.transactionDao().insert(
                TransactionEntity(
                    userId = "uid-1",
                    title = "Row $index",
                    amount = 10.0,
                    type = TransactionType.EXPENSE,
                    category = "other",
                    timestamp = 1_757_000_000_000L + index,
                    bankCode = code
                )
            )
        }
    }

    @Test
    fun `rows come back in the order the user arranged them`() = runTest {
        dao.insert(bank("uk", "Ukrsibbank", 1))
        dao.insert(bank("mo", "Monobank", 0))
        dao.insert(bank("pb", "PrivatBank", 2))

        assertEquals(
            listOf("Monobank", "Ukrsibbank", "PrivatBank"),
            dao.observeAll().first().map { it.displayName }
        )
    }

    @Test
    fun `a bank with the same position is ordered by name rather than arbitrarily`() =
        runTest {
            dao.insert(bank("b", "Bravo", 0))
            dao.insert(bank("a", "Alpha", 0))

            assertEquals(
                listOf("Alpha", "Bravo"),
                dao.observeAll().first().map { it.displayName }
            )
        }

    @Test
    fun `the active list hides archived banks and the full list does not`() = runTest {
        dao.insert(bank("mo", "Monobank", 0, builtIn = true))
        dao.insert(bank("gone", "Old Bank", 1, archived = true))

        assertEquals(
            listOf("Monobank"),
            dao.observeActive().first().map { it.displayName }
        )
        assertEquals(
            listOf("Monobank", "Old Bank"),
            dao.observeAll().first().map { it.displayName }
        )
    }

    @Test
    fun `renaming leaves the code alone`() = runTest {
        dao.insert(bank("bank-abc123", "Old Name", 0))

        assertEquals(1, dao.rename("bank-abc123", "New Name"))
        val stored = dao.observeAll().first().single()
        assertEquals("New Name", stored.displayName)
        // The code is what feeds the import fingerprint, so it must survive a rename intact.
        assertEquals("bank-abc123", stored.code)
    }

    @Test
    fun `archiving hides a bank from the active list and restoring brings it back`() = runTest {
        dao.insert(bank("gone", "Old Bank", 0))

        assertEquals(1, dao.setArchived("gone", true))
        assertTrue(dao.observeActive().first().isEmpty())

        assertEquals(1, dao.setArchived("gone", false))
        assertEquals(listOf("Old Bank"), dao.observeActive().first().map { it.displayName })
    }

    @Test
    fun `swapping two positions reorders the list`() = runTest {
        dao.insert(bank("a", "Alpha", 0))
        dao.insert(bank("b", "Bravo", 1))
        dao.insert(bank("c", "Charlie", 2))

        // What "move up" and "move down" actually do. Two writes, not one: see the next
        // test for why a single write is not enough.
        assertEquals(1, dao.setPosition("c", 1))
        assertEquals(1, dao.setPosition("b", 2))

        assertEquals(
            listOf("Alpha", "Charlie", "Bravo"),
            dao.observeAll().first().map { it.displayName }
        )
    }

    @Test
    fun `a position that ties with another falls back to name order`() = runTest {
        dao.insert(bank("a", "Alpha", 0))
        dao.insert(bank("b", "Bravo", 1))

        // Writing one bank onto an occupied position is not a move, it is a tie, and it
        // silently lands in the wrong place: the user asked for Bravo first and gets Alpha.
        // The repository therefore reorders by swapping, never by a lone setPosition.
        assertEquals(1, dao.setPosition("b", 0))

        assertEquals(
            listOf("Alpha", "Bravo"),
            dao.observeAll().first().map { it.displayName }
        )
    }

    @Test
    fun `referenced codes come only from rows that name a bank`() = runTest {
        dao.insert(bank("mo", "Monobank", 0))
        seedTransactions("mo", "mo", "uk", "bank-abc123", null)

        assertEquals(
            listOf("bank-abc123", "mo", "uk"),
            dao.referencedBankCodes().first().sorted()
        )
    }

    @Test
    fun `a bank nothing references is not reported as referenced`() = runTest {
        seedTransactions("mo")

        assertTrue(dao.referencedBankCodes().first().containsAll(listOf("mo")))
        assertEquals(1, dao.referencedBankCodes().first().size)
    }

    @Test
    fun `looking up an unknown code returns nothing rather than a blank row`() = runTest {
        assertNull(dao.getByCode("never-added"))
    }

    @Test
    fun `every stored name is readable so uniqueness can be decided on any script`() = runTest {
        // Deliberately not a SQL lower() comparison: that folds ASCII only, so it would
        // happily accept "ПриватБанк" beside "приватбанк". The list is a handful of rows,
        // so the check reads them and compares in Kotlin.
        dao.insert(bank("a", "ПриватБанк", 0))

        assertEquals(listOf("ПриватБанк"), dao.getAllOnce().map { it.displayName })
    }

    @Test
    fun `the next position continues after the highest one in use`() = runTest {
        assertEquals(0, dao.nextPosition())

        dao.insert(bank("mo", "Monobank", 0))
        dao.insert(bank("uk", "Ukrsibbank", 7))

        assertEquals(8, dao.nextPosition())
    }
}
