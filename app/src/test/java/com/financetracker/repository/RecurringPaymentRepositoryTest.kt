package com.financetracker.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.financetracker.data.AppDatabase
import com.financetracker.data.backup.BackupReason
import com.financetracker.model.RepeatFrequency
import com.financetracker.model.RecurringPayment
import com.financetracker.model.TransactionType
import com.financetracker.testing.FakeBackupRequests
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
 * Exercises [RecurringPaymentRepository] against real SQLite.
 *
 * The case worth a real database is the archive no-op: it turns on SQLite reporting rows
 * *matched* rather than *changed*, so re-archiving an archived row must still ask for nothing.
 * A mock would agree with either implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecurringPaymentRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RecurringPaymentRepository
    private lateinit var backups: FakeBackupRequests

    @Before
    fun setUp() {
        backups = FakeBackupRequests()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
        repository = RecurringPaymentRepository(db.recurringPaymentDao(), backups)
    }

    @After
    fun tearDown() = db.close()

    private fun payment(title: String = "Rent") = RecurringPayment(
        title = title,
        amount = 12_000.0,
        type = TransactionType.EXPENSE,
        category = "other",
        currencyCode = "UAH",
        frequency = RepeatFrequency.MONTHLY,
        startDate = 1_000L
    )

    @Test
    fun `adding a schedule asks for one backup`() = runTest {
        repository.add(UID, payment())

        assertEquals(listOf(BackupReason.RECURRING), backups.reasons)
    }

    @Test
    fun `schedules are read back for the account as domain types`() = runTest {
        repository.add(UID, payment(title = "Rent"))

        val rows = repository.observe(UID).first()

        assertEquals(1, rows.size)
        assertEquals("Rent", rows.first().title)
        assertEquals(RepeatFrequency.MONTHLY, rows.first().frequency)
    }

    @Test
    fun `archiving a live schedule asks for a backup and flips the flag`() = runTest {
        val id = repository.add(UID, payment())
        backups.reasons.clear()

        assertTrue(repository.setArchived(id, true))

        assertEquals(listOf(BackupReason.RECURRING), backups.reasons)
        assertTrue(db.recurringPaymentDao().isArchived(id) == true)
    }

    @Test
    fun `archiving an already-archived schedule asks for nothing`() = runTest {
        val id = repository.add(UID, payment())
        repository.setArchived(id, true)
        backups.reasons.clear()

        // Still true: the row holds the state the caller asked for. But nothing changed, so no
        // upload is requested — the negative case the backup invariant turns on.
        assertTrue(repository.setArchived(id, true))

        assertTrue(backups.reasons.isEmpty())
    }

    @Test
    fun `archiving a row that is not there reports false and asks for nothing`() = runTest {
        assertFalse(repository.setArchived(404L, true))

        assertTrue(backups.reasons.isEmpty())
    }

    @Test
    fun `deleting asks for a backup`() = runTest {
        val id = repository.add(UID, payment())
        backups.reasons.clear()

        assertEquals(1, repository.delete(id))

        assertEquals(listOf(BackupReason.RECURRING), backups.reasons)
    }

    private companion object {
        const val UID = "uid-1"
    }
}
