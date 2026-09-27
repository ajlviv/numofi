package com.financetracker.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.financetracker.model.BankCode
import com.financetracker.model.SearchText
import com.financetracker.model.TransactionEntity
import com.financetracker.model.TransactionType
import kotlinx.coroutines.flow.first
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
 * Exercises the filter and search query against a real SQLite database, because the whole
 * point of the query is how SQL treats nulls, `LIKE` and a lowercase haystack. A mock
 * could not show any of that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TransactionDaoQueryTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: TransactionDao

    private fun row(
        title: String,
        amount: Double,
        type: TransactionType,
        bank: String?,
        card: String?,
        note: String? = null
    ) = TransactionEntity(
        userId = "uid-1",
        title = title,
        amount = amount,
        type = type,
        category = "grocery",
        timestamp = 1_757_000_000_000,
        note = note,
        externalId = "x-${title}-$amount",
        source = bank,
        currencyCode = "UAH",
        bankCode = bank,
        cardLabel = card,
        searchText = SearchText.of(title, note, "grocery", bank, card)
    )

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.transactionDao()
        dao.insertAll(
            listOf(
                row("TORUS", 10.0, TransactionType.EXPENSE, BankCode.MONOBANK, "535129****5783"),
                row("Поповнення", 20.0, TransactionType.INCOME, BankCode.MONOBANK, "535129****5783"),
                row("АПТЕКА", 30.0, TransactionType.EXPENSE, BankCode.UKRSIBBANK, "4111****2222"),
                row("Lunch", 40.0, TransactionType.EXPENSE, null, null)
            )
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `no filter returns everything`() = runTest {
        assertEquals(4, dao.getFiltered("uid-1", null, null, null, null).first().size)
    }

    @Test
    fun `bank narrows on its own`() = runTest {
        assertEquals(1, dao.getFiltered("uid-1", BankCode.UKRSIBBANK, null, null, null).first().size)
    }

    @Test
    fun `direction narrows on its own`() = runTest {
        assertEquals(1, dao.getFiltered("uid-1", null, null, TransactionType.INCOME, null).first().size)
    }

    @Test
    fun `filters intersect rather than union`() = runTest {
        val rows = dao.getFiltered("uid-1", BankCode.MONOBANK, null, TransactionType.EXPENSE, null).first()
        assertEquals(1, rows.size)
        assertEquals("TORUS", rows.single().title)
    }

    @Test
    fun `card narrows within the selected bank`() = runTest {
        val rows = dao.getFiltered("uid-1", BankCode.MONOBANK, "535129****5783", null, null).first()
        assertEquals(2, rows.size)
    }

    @Test
    fun `a card that belongs to another bank matches nothing`() = runTest {
        assertEquals(0, dao.getFiltered("uid-1", BankCode.UKRSIBBANK, "535129****5783", null, null).first().size)
    }

    @Test
    fun `search folds Cyrillic in both directions`() = runTest {
        assertEquals(1, dao.getFiltered("uid-1", null, null, null, "аптека").first().size)
        assertEquals(1, dao.getFiltered("uid-1", null, null, null, "АПТЕКА").first().size)
        assertEquals(1, dao.getFiltered("uid-1", null, null, null, "АпТеКа").first().size)
    }

    @Test
    fun `search matches a partial word`() = runTest {
        assertEquals(1, dao.getFiltered("uid-1", null, null, null, "пте").first().size)
    }

    @Test
    fun `search matches the card label and the bank label`() = runTest {
        assertEquals(2, dao.getFiltered("uid-1", null, null, null, "535129").first().size)
        assertEquals(2, dao.getFiltered("uid-1", null, null, null, "monobank").first().size)
        assertEquals(1, dao.getFiltered("uid-1", null, null, null, "ukrsibbank").first().size)
    }

    @Test
    fun `search and filters combine`() = runTest {
        val rows = dao.getFiltered("uid-1", BankCode.MONOBANK, null, null, "поповнення").first()
        assertEquals(1, rows.size)
        assertEquals("Поповнення", rows.single().title)
    }

    @Test
    fun `a blank term is not a filter`() = runTest {
        assertEquals(4, dao.getFiltered("uid-1", null, null, null, "").first().size)
    }

    @Test
    fun `another user's rows are never returned`() = runTest {
        assertEquals(0, dao.getFiltered("uid-2", null, null, null, null).first().size)
    }

    @Test
    fun `the distinct card list excludes rows with no card`() = runTest {
        assertEquals(
            setOf("535129****5783", "4111****2222"),
            dao.getDistinctCardLabels("uid-1", null).first().toSet()
        )
    }

    @Test
    fun `the distinct card list is scoped to the user`() = runTest {
        assertEquals(emptyList<String>(), dao.getDistinctCardLabels("uid-2", null).first())
    }

    @Test
    fun `the card list narrows to the selected bank`() = runTest {
        // Offering the Monobank card while Ukrsibbank is selected would let the user pick a
        // combination that cannot exist and land on an empty list.
        assertEquals(
            listOf("4111****2222"),
            dao.getDistinctCardLabels("uid-1", BankCode.UKRSIBBANK).first()
        )
        assertEquals(
            listOf("535129****5783"),
            dao.getDistinctCardLabels("uid-1", BankCode.MONOBANK).first()
        )
    }

    @Test
    fun `a bank with only cardless rows offers no card chip`() = runTest {
        assertEquals(emptyList<String>(), dao.getDistinctCardLabels("uid-1", "unknown-bank").first())
    }

    @Test
    fun `results stay newest first`() = runTest {
        val rows = dao.getFiltered("uid-1", null, null, null, null).first()
        val timestamps = rows.map { it.timestamp }
        assertEquals(timestamps.sortedDescending(), timestamps)
    }
}
