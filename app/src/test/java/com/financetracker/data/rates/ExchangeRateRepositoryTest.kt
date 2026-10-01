package com.financetracker.data.rates

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.financetracker.data.settings.DEFAULT_BASE_CURRENCY
import com.financetracker.data.settings.SettingsRepository
import com.financetracker.model.ExchangeRates
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
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
 * Fetching, caching and — above all — failing to fetch without losing what was already cached.
 *
 * The failure cases carry more weight than the success ones here. A repository that overwrites
 * the cache on the way down would leave a user with no net worth at all the first time they
 * opened the app on a train, and nothing in the UI would say why.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExchangeRateRepositoryTest {

    private class FakeNbuApi(
        var body: String = VALID_BODY,
        var failure: Exception? = null
    ) : NbuApi {
        var calls = 0
        /** Returned twice over in a real body; kept short here so a failure names one cause. */
        override suspend fun exchangeRates(): ResponseBody {
            calls++
            failure?.let { throw it }
            return body.toResponseBody()
        }
    }

    private lateinit var settings: SettingsRepository
    private lateinit var api: FakeNbuApi
    private lateinit var repository: ExchangeRateRepository

    private val now = 1_757_000_000_000L
    private val hour = 3_600_000L

    @Before
    fun setUp() = runBlocking {
        settings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        resetStoredState()
        api = FakeNbuApi()
        repository = ExchangeRateRepository(api, settings)
    }

    @After
    fun tearDown() = runBlocking { resetStoredState() }

    /** The preference delegate is a top-level property: one file for the whole JVM. */
    private suspend fun resetStoredState() {
        settings.setBaseCurrency(DEFAULT_BASE_CURRENCY)
        settings.saveExchangeRates(ExchangeRates(emptyMap(), null, 0L))
    }

    @Test
    fun `a fresh install fetches and caches`() = runBlocking {
        assertTrue(repository.refresh(now))
        assertEquals(1, api.calls)
        val cached = settings.exchangeRates.first()
        assertEquals(44.6819, cached.toUah["USD"]!!, 0.0)
        assertEquals("01.10.2026", cached.date)
        assertEquals(now, cached.fetchedAt)
    }

    @Test
    fun `a complete cache fetched an hour ago is left alone`() = runBlocking {
        // When the cache already contains all recordable currencies, the 24‑hour gate still
        // applies: a refresh within that window is suppressed even though the completeness
        // check also passes.
        api.body = BODY_WITH_PLN
        repository.refresh(now)
        assertFalse(repository.refreshIfStale(now + hour))
        assertEquals(1, api.calls)
    }

    @Test
    fun `a cache older than a day is refetched`() = runBlocking {
        repository.refresh(now)
        assertTrue(repository.refreshIfStale(now + 25 * hour))
        assertEquals(2, api.calls)
    }

    @Test
    fun `the refresh gate is a day with a complete cache`() = runBlocking {
        // When the cache already contains all recordable currencies, the 24‑hour gate still
        // applies: a refresh within that window is suppressed, but after the window expires
        // a fresh fetch is required.
        api.body = BODY_WITH_PLN
        repository.refresh(now)
        // Within the freshness window the cache is still considered good.
        assertFalse(repository.refreshIfStale(now + 4 * hour))
        assertEquals(1, api.calls)
        // Past the window a fresh fetch is required.
        assertTrue(repository.refreshIfStale(now + 48 * hour))
        assertEquals(2, api.calls)
    }

    @Test
    fun `a fresh cache that can quote everything is left alone`() = runBlocking {
        api.body = BODY_WITH_PLN
        repository.refresh(now)
        assertEquals(11.6125, settings.exchangeRates.first().toUah["PLN"]!!, 0.0)
        // UAH is skipped by the completeness check precisely so this holds: if UAH counted as
        // missing, every dashboard entry would refetch and the day gate would never apply.
        assertFalse(repository.refreshIfStale(now + hour))
        assertEquals(1, api.calls)
    }

    @Test
    fun `a failed fetch leaves the cached rates exactly as they were`() = runBlocking {
        repository.refresh(now)
        val before = settings.exchangeRates.first()

        api.failure = IllegalStateException("no network")
        assertFalse(repository.refresh(now + 25 * hour))

        val after = settings.exchangeRates.first()
        assertEquals(before.toUah, after.toUah)
        assertEquals(before.date, after.date)
        assertEquals(before.fetchedAt, after.fetchedAt)
    }

    @Test
    fun `a body that cannot be parsed leaves the cache alone`() = runBlocking {
        repository.refresh(now)
        // The one case where returning an empty cache would be a data-loss bug rather than a
        // style question: the caller saves what it is handed.
        api.body = "<html>502 Bad Gateway</html>"
        assertFalse(repository.refresh(now + 25 * hour))
        assertEquals(44.6819, settings.exchangeRates.first().toUah["USD"]!!, 0.0)
    }

    @Test
    fun `a valid but empty response is a success that caches nothing`() = runBlocking {
        // NBU answering with no rows is a real answer, not a failure, and it must not leave a
        // previous day's rates in place pretending to be today's.
        repository.refresh(now)
        api.body = "[]"
        assertTrue(repository.refresh(now + 25 * hour))
        assertTrue(settings.exchangeRates.first().toUah.isEmpty())
    }

    @Test
    fun `the base currency and the cache are exposed straight from settings`() = runBlocking {
        settings.setBaseCurrency("EUR")
        assertEquals("EUR", repository.baseCurrency.first())
        assertEquals(ExchangeRates(emptyMap(), null, 0L), repository.rates.first())
    }

    private companion object {
        const val VALID_BODY = """
            [{"r030":840,"txt":"Долар США","rate":44.6819,"cc":"USD","exchangedate":"01.10.2026","special":"N"},
             {"r030":978,"txt":"Євро","rate":50.7166,"cc":"EUR","exchangedate":"01.10.2026","special":null}]
        """
        /** What NBU actually answers with once PLN is among the currencies the app records. */
        const val BODY_WITH_PLN = """
            [{"r030":840,"txt":"Долар США","rate":44.6819,"cc":"USD","exchangedate":"01.10.2026","special":"N"},
             {"r030":978,"txt":"Євро","rate":50.7166,"cc":"EUR","exchangedate":"01.10.2026","special":null},
             {"r030":985,"txt":"Злотий","rate":11.6125,"cc":"PLN","exchangedate":"01.10.2026","special":null}]
        """
    }
}
