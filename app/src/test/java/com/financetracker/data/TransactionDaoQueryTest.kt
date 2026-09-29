package com.financetracker.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.financetracker.model.BankCode
import com.financetracker.model.BankEntity
import com.financetracker.model.BankNames
import com.financetracker.model.CardRef
import com.financetracker.model.SearchText
import com.financetracker.model.TransactionEntity
import com.financetracker.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the filter and search query against a real SQLite database, because the whole
 * point of the query is how SQL treats nulls, empty collections, `IN` and `LIKE` over a
 * lowercase haystack. A mock could not show any of that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TransactionDaoQueryTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: TransactionDao

    private val day = 1_757_000_000_000L

    /** The seeded banks as a write path sees them, so the haystack carries real names. */
    private val seededBankNames = BankEntity.BUILT_IN.associate { it.code to it.displayName }

    private fun row(
        title: String,
        amount: Double,
        type: TransactionType,
        bank: String?,
        card: String?,
        note: String? = null,
        timestamp: Long = day
    ) = TransactionEntity(
        userId = "uid-1",
        title = title,
        amount = amount,
        type = type,
        category = "grocery",
        timestamp = timestamp,
        note = note,
        externalId = "x-$title-$amount",
        source = bank,
        currencyCode = "UAH",
        bankCode = bank,
        cardLabel = card,
        searchText = SearchText.of(
            title,
            note,
            "grocery",
            BankNames.ref(bank, seededBankNames),
            card
        )
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
                row("TORUS", 10.0, TransactionType.EXPENSE, BankCode.MONOBANK, "535129****5783", timestamp = day + 3),
                row("Поповнення", 20.0, TransactionType.INCOME, BankCode.MONOBANK, "535129****5783", timestamp = day + 2),
                row("АПТЕКА", 30.0, TransactionType.EXPENSE, BankCode.UKRSIBBANK, "4111****2222", timestamp = day + 1),
                row("Lunch", 40.0, TransactionType.EXPENSE, null, null, timestamp = day)
            )
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `no filter returns everything`() = runTest {
        assertEquals(4, dao.getFiltered("uid-1").first().size)
    }

    @Test
    fun `an empty selection is not a filter`() = runTest {
        // Every collection filter is passed empty here, which is the case the guard flags
        // exist for. `col IN ()` is a syntax error in SQLite, so if the flags were not
        // short-circuiting this would fail outright rather than quietly return everything.
        assertEquals(4, dao.getFiltered("uid-1", emptyList(), emptyList(), emptyList()).first().size)
    }

    @Test
    fun `bank narrows on its own`() = runTest {
        assertEquals(1, dao.getFiltered("uid-1", bankCodes = listOf(BankCode.UKRSIBBANK)).first().size)
    }

    @Test
    fun `several banks are a union, not an intersection`() = runTest {
        val rows = dao.getFiltered(
            "uid-1",
            bankCodes = listOf(BankCode.MONOBANK, BankCode.UKRSIBBANK)
        ).first()
        assertEquals(3, rows.size)
        assertTrue(rows.none { it.title == "Lunch" })
    }

    @Test
    fun `direction narrows on its own`() = runTest {
        assertEquals(1, dao.getFiltered("uid-1", types = listOf(TransactionType.INCOME)).first().size)
    }

    @Test
    fun `both directions together are every row`() = runTest {
        val rows = dao.getFiltered(
            "uid-1",
            types = listOf(TransactionType.INCOME, TransactionType.EXPENSE)
        ).first()
        assertEquals(4, rows.size)
    }

    @Test
    fun `different filters intersect rather than union`() = runTest {
        val rows = dao.getFiltered(
            "uid-1",
            bankCodes = listOf(BankCode.MONOBANK),
            types = listOf(TransactionType.EXPENSE)
        ).first()
        assertEquals(1, rows.size)
        assertEquals("TORUS", rows.single().title)
    }

    @Test
    fun `card narrows within the selected bank`() = runTest {
        val rows = dao.getFiltered(
            "uid-1",
            bankCodes = listOf(BankCode.MONOBANK),
            cardLabels = listOf("535129****5783")
        ).first()
        assertEquals(2, rows.size)
    }

    @Test
    fun `several cards are a union`() = runTest {
        val rows = dao.getFiltered(
            "uid-1",
            cardLabels = listOf("535129****5783", "4111****2222")
        ).first()
        assertEquals(3, rows.size)
    }

    @Test
    fun `a card that belongs to another bank matches nothing`() = runTest {
        val rows = dao.getFiltered(
            "uid-1",
            bankCodes = listOf(BankCode.UKRSIBBANK),
            cardLabels = listOf("535129****5783")
        ).first()
        assertEquals(0, rows.size)
    }

    @Test
    fun `the range covers the whole of the last day, and no further`() = runTest {
        val from = day
        val to = day + 86_400_000
        dao.insertAll(
            listOf(
                row("before", 1.0, TransactionType.EXPENSE, BankCode.MONOBANK, "c", timestamp = from - 1),
                row("first-instant", 2.0, TransactionType.EXPENSE, BankCode.MONOBANK, "c", timestamp = from),
                row("last-instant", 3.0, TransactionType.EXPENSE, BankCode.MONOBANK, "c", timestamp = to - 1),
                row("after", 4.0, TransactionType.EXPENSE, BankCode.MONOBANK, "c", timestamp = to)
            )
        )

        val titles = dao.getFiltered("uid-1", fromMillis = from, toMillis = to)
            .first()
            .map { it.title }
            .toSet()

        // The end bound is exclusive, so the final millisecond of the range is inside it and
        // the next one is not. Getting this backwards is the off-by-one that hides a
        // transaction on the very day the user filtered to.
        assertTrue("the first millisecond of the range is inside it", "first-instant" in titles)
        assertTrue("the last millisecond of the range is inside it", "last-instant" in titles)
        assertFalse("a millisecond before the range is outside it", "before" in titles)
        assertFalse("the exclusive end does not reach the next day", "after" in titles)
    }

    @Test
    fun `an open start includes everything from that instant on`() = runTest {
        val rows = dao.getFiltered("uid-1", fromMillis = day + 2).first()
        assertEquals(2, rows.size)
        assertTrue("TORUS" in rows.map { it.title })
    }

    @Test
    fun `an open end excludes the row sitting exactly on the bound`() = runTest {
        // The АПТЕКА row is at day + 1, which is the exclusive bound itself, so it is the
        // first row to drop out.
        val rows = dao.getFiltered("uid-1", toMillis = day + 1).first()
        assertEquals(listOf("Lunch"), rows.map { it.title })
    }

    @Test
    fun `a range combines with the other filters`() = runTest {
        val rows = dao.getFiltered(
            "uid-1",
            bankCodes = listOf(BankCode.MONOBANK),
            fromMillis = day + 2
        ).first()
        assertEquals(2, rows.size)
        assertTrue("TORUS" in rows.map { it.title })
    }

    @Test
    fun `search folds Cyrillic in both directions`() = runTest {
        assertEquals(1, dao.getFiltered("uid-1", search = "аптека").first().size)
        assertEquals(1, dao.getFiltered("uid-1", search = "АПТЕКА").first().size)
        assertEquals(1, dao.getFiltered("uid-1", search = "АпТеКа").first().size)
    }

    @Test
    fun `search matches a partial word`() = runTest {
        assertEquals(1, dao.getFiltered("uid-1", search = "пте").first().size)
    }

    @Test
    fun `search matches the card label and the bank label`() = runTest {
        assertEquals(2, dao.getFiltered("uid-1", search = "535129").first().size)
        assertEquals(2, dao.getFiltered("uid-1", search = "monobank").first().size)
        assertEquals(1, dao.getFiltered("uid-1", search = "ukrsibbank").first().size)
    }

    @Test
    fun `search and filters combine`() = runTest {
        val rows = dao.getFiltered(
            "uid-1",
            bankCodes = listOf(BankCode.MONOBANK),
            search = "поповнення"
        ).first()
        assertEquals(1, rows.size)
    }

    @Test
    fun `a blank term is not a filter`() = runTest {
        assertEquals(4, dao.getFiltered("uid-1", search = "").first().size)
    }

    @Test
    fun `another user's rows are never returned`() = runTest {
        assertEquals(0, dao.getFiltered("uid-2").first().size)
    }

    @Test
    fun `each card is paired with the bank that issued it`() = runTest {
        assertEquals(
            setOf(
                CardRef(BankCode.MONOBANK, "535129****5783"),
                CardRef(BankCode.UKRSIBBANK, "4111****2222")
            ),
            dao.getCardRefs("uid-1").first().toSet()
        )
    }

    @Test
    fun `card refs are scoped to the user`() = runTest {
        assertEquals(emptyList<CardRef>(), dao.getCardRefs("uid-2").first())
    }

    @Test
    fun `results stay newest first`() = runTest {
        val rows = dao.getFiltered("uid-1").first()
        val timestamps = rows.map { it.timestamp }
        assertEquals(timestamps.sortedDescending(), timestamps)
    }

    @Test
    fun `rows with the same timestamp break ties by id descending so newly added rows appear first`() = runTest {
        dao.insertAll(
            listOf(
                row("Earlier Item", 10.0, TransactionType.EXPENSE, null, null, timestamp = 2_000_000_000_000L),
                row("Later Item", 20.0, TransactionType.EXPENSE, null, null, timestamp = 2_000_000_000_000L)
            )
        )

        val rows = dao.getFiltered("uid-1").first().filter { it.timestamp == 2_000_000_000_000L }
        assertEquals(2, rows.size)
        assertEquals("Later Item", rows[0].title)
        assertEquals("Earlier Item", rows[1].title)
    }
}
