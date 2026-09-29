package com.financetracker.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.financetracker.model.BankCode
import com.financetracker.model.BankEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Checks that a clean install ends up with the built-in banks.
 *
 * Creating the file is the only path this database has — there is no upgrade path — so the
 * seed callback is the only thing that puts banks in the table. Without it a fresh install
 * would open with no banks at all: nothing to import a statement into and nothing to filter
 * transactions by, with no error to show for it. This drives the real builder rather than
 * calling the seed directly, so it fails if the callback is ever unwired.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FreshInstallSeedTest {

    private fun openSeeded() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        AppDatabase::class.java
    )
        .addCallback(AppDatabase.SEED_ON_CREATE)
        .allowMainThreadQueries()
        .build()

    @Test
    fun `a clean install starts with the three built-in banks`() = runTest {
        val db = openSeeded()
        try {
            assertEquals(
                listOf("Monobank", "Ukrsibbank", "PrivatBank"),
                db.bankDao().getAllOnce().map(BankEntity::displayName)
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `a clean install seeds the codes the detector and the sync service key off`() = runTest {
        // BankDetector matches on UKRSIBBANK/PRIVATBANK and BankSyncService writes MONOBANK,
        // so seeding different strings would silently disable both.
        val db = openSeeded()
        try {
            val codes = db.bankDao().getAllOnce().map(BankEntity::code)
            assertEquals(
                listOf(BankCode.MONOBANK, BankCode.UKRSIBBANK, BankCode.PRIVATBANK),
                codes
            )
            assertEquals(
                BankEntity.BUILT_IN.map(BankEntity::displayName),
                db.bankDao().getAllOnce().map(BankEntity::displayName)
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `a clean install has no archived banks and no gaps in the order`() = runTest {
        val db = openSeeded()
        try {
            val banks = db.bankDao().observeAll().first()
            assertEquals(listOf(0, 1, 2), banks.map { it.position })
            assertEquals(emptyList<String>(), banks.filter { it.archived }.map { it.code })
        } finally {
            db.close()
        }
    }
}
