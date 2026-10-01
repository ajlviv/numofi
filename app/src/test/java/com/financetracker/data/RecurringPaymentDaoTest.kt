package com.financetracker.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.financetracker.model.RepeatFrequency
import com.financetracker.model.RecurringPaymentEntity
import com.financetracker.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The recurring-payment queries against real SQLite.
 *
 * The account scoping is the part worth a real database rather than a mock: a schedule filed
 * under the wrong uid would simply never be seen again, and the restore keys off exactly this
 * filter. The archived read is here too, because SQLite's `UPDATE` reporting rows *matched*
 * rather than *changed* is a fact about the engine, not about the repository above it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecurringPaymentDaoTest {

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

    private fun payment(
        uid: String = UID,
        title: String = "Rent",
        start: Long = 1_000L
    ) = RecurringPaymentEntity(
        userId = uid,
        title = title,
        amount = 100.0,
        type = TransactionType.EXPENSE,
        category = "other",
        currencyCode = "UAH",
        frequency = RepeatFrequency.MONTHLY,
        startDate = start
    )

    @Test
    fun `a schedule comes back for the account that wrote it`() = runTest {
        db.recurringPaymentDao().insert(payment())

        val rows = db.recurringPaymentDao().observeForUser(UID).first()

        assertEquals(1, rows.size)
        assertEquals("Rent", rows.first().title)
        assertEquals(RepeatFrequency.MONTHLY, rows.first().frequency)
    }

    @Test
    fun `another account does not see this account's schedules`() = runTest {
        db.recurringPaymentDao().insert(payment(uid = "uid-1"))
        db.recurringPaymentDao().insert(payment(uid = "uid-2"))

        assertEquals(1, db.recurringPaymentDao().observeForUser("uid-1").first().size)
        assertEquals(1, db.recurringPaymentDao().getAllForUserOnce("uid-1").size)
    }

    @Test
    fun `schedules are ordered by their anchor`() = runTest {
        db.recurringPaymentDao().insert(payment(title = "Later", start = 5_000L))
        db.recurringPaymentDao().insert(payment(title = "Sooner", start = 1_000L))

        assertEquals(
            listOf("Sooner", "Later"),
            db.recurringPaymentDao().observeForUser(UID).first().map { it.title }
        )
    }

    @Test
    fun `archiving flips the stored flag and reports it`() = runTest {
        val id = db.recurringPaymentDao().insert(payment())

        assertEquals(false, db.recurringPaymentDao().isArchived(id))
        assertEquals(1, db.recurringPaymentDao().setArchived(id, true))
        assertEquals(true, db.recurringPaymentDao().isArchived(id))
    }

    @Test
    fun `a missing row reads as no flag rather than false`() = runTest {
        // The repository relies on null and false being different: null is "no such row" and
        // must not be reported as an already-done archive.
        assertNull(db.recurringPaymentDao().isArchived(404L))
    }

    @Test
    fun `deleting removes exactly the named row`() = runTest {
        db.recurringPaymentDao().insert(payment(title = "Keep"))
        val drop = db.recurringPaymentDao().insert(payment(title = "Drop", start = 2_000L))

        assertEquals(1, db.recurringPaymentDao().delete(drop))

        assertEquals(listOf("Keep"), db.recurringPaymentDao().observeForUser(UID).first().map { it.title })
    }

    @Test
    fun `deleting a row that is not there reports nothing`() = runTest {
        assertEquals(0, db.recurringPaymentDao().delete(404L))
    }

    private companion object {
        const val UID = "uid-1"
    }
}
