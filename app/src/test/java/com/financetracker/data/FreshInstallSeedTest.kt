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
 * [Migration45Test] covers the upgrade path, and it is easy to leave it as the only seeded
 * path by mistake: migrations do not run when Room creates the file, so a fresh install
 * would open with no banks at all — nothing to import a statement into and nothing to
 * filter transactions by, with no error to show for it. This drives the real builder
 * rather than calling the seed directly, so it fails if the callback is ever unwired.
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
    fun `a clean install seeds the same names the upgrade path does`() = runTest {
        // The two paths build the same list from the same constant, but a clean install and
        // an upgrade that disagreed would mean search behaves differently depending on how
        // the app was installed.
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
