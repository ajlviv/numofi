package com.financetracker.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.financetracker.model.ExchangeRates
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
 * The rate cache and the base-currency preference, against real preferences.
 *
 * The same shape as `BackupUploaderTest`: a real [SettingsRepository] over the real DataStore,
 * because what is worth checking here is what actually lands on disk and what a second read
 * makes of it. A fake would agree with whatever the code was written to do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsRepositoryRatesTest {

    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() = runBlocking {
        settings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        resetStoredState()
    }

    /**
     * The preference delegate is a top-level property, so every instance in the JVM shares one
     * file and a rate left behind here would be the next test's starting point. Zero reads as
     * "never" throughout the repository, which is what makes clearing enough.
     */
    @After
    fun tearDown() = runBlocking { resetStoredState() }

    private suspend fun resetStoredState() {
        settings.setBaseCurrency(DEFAULT_BASE_CURRENCY)
        settings.saveExchangeRates(ExchangeRates(emptyMap(), null, 0L))
    }

    @Test
    fun `a fresh install reports UAH, so the dashboard always has a total`() = runBlocking {
        // Not nullable on purpose: with no base there is nothing to show, and an empty
        // dashboard reads as an empty account rather than as a missing preference.
        assertEquals(DEFAULT_BASE_CURRENCY, settings.baseCurrency.first())
    }

    @Test
    fun `a base currency round-trips`() = runBlocking {
        settings.setBaseCurrency("USD")
        assertEquals("USD", settings.baseCurrency.first())
    }

    @Test
    fun `the base can be changed back and forth`() = runBlocking {
        settings.setBaseCurrency("EUR")
        settings.setBaseCurrency("USD")
        settings.setBaseCurrency(DEFAULT_BASE_CURRENCY)
        assertEquals(DEFAULT_BASE_CURRENCY, settings.baseCurrency.first())
    }

    @Test
    fun `a stored currency the app no longer offers falls back to the default`() = runBlocking {
        // Hand-planted, as if written by a build that offered a currency this one dropped.
        // Falling back rather than reporting it keeps the dashboard on a base the cache can
        // quote; null would leave it with no base at all, which is the state this feature no
        // longer has.
        settings.setBaseCurrency("GBP")
        assertEquals(DEFAULT_BASE_CURRENCY, settings.baseCurrency.first())
    }

    @Test
    fun `rates round-trip with their date and fetch time`() = runBlocking {
        settings.saveExchangeRates(
            ExchangeRates(mapOf("USD" to 44.6819, "EUR" to 50.7166), "01.10.2026", 1_700_000_000_000L)
        )
        val read = settings.exchangeRates.first()
        assertEquals(44.6819, read.toUah["USD"]!!, 0.0)
        assertEquals(50.7166, read.toUah["EUR"]!!, 0.0)
        assertEquals("01.10.2026", read.date)
        assertEquals(1_700_000_000_000L, read.fetchedAt)
    }

    @Test
    fun `an unfetched cache reads as empty rather than as zeros`() = runBlocking {
        val read = settings.exchangeRates.first()
        assertTrue(read.toUah.isEmpty())
        assertNull(read.date)
        assertEquals(0L, read.fetchedAt)
    }

    @Test
    fun `a second save replaces the first instead of merging into it`() = runBlocking {
        settings.saveExchangeRates(ExchangeRates(mapOf("USD" to 44.0, "EUR" to 50.0), "01.10.2026", 1L))
        settings.saveExchangeRates(ExchangeRates(mapOf("USD" to 41.0), "02.10.2026", 2L))
        val read = settings.exchangeRates.first()
        // EUR gone rather than still sitting at yesterday's rate: a currency NBU stops
        // publishing has to disappear, or the app keeps valuing it at a rate nothing supports.
        assertNull(read.toUah["EUR"])
        assertEquals(41.0, read.toUah["USD"]!!, 0.0)
        assertEquals("02.10.2026", read.date)
    }

    @Test
    fun `changing the base currency leaves the cached rates alone`() = runBlocking {
        settings.saveExchangeRates(ExchangeRates(mapOf("USD" to 44.6819), "01.10.2026", 1L))
        settings.setBaseCurrency("EUR")
        // Same rule as backup: choosing a different basis releases the old choice, it does not
        // destroy what the fetch produced. Switching back should not have to wait for a network
        // round trip to show a figure again.
        assertEquals(44.6819, settings.exchangeRates.first().toUah["USD"]!!, 0.0)
    }
}
