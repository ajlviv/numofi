package com.financetracker.data.rates

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.financetracker.data.settings.DEFAULT_BASE_CURRENCY
import com.financetracker.data.settings.SettingsRepository
import com.financetracker.model.ExchangeRates
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
 * A cache that cannot quote everything the app accepts, on the day it was fetched.
 *
 * NBU publishes some currencies only intermittently — PLN among the ones this app records is the
 * standing example — so a response can leave the cache short of a currency the app is able to
 * record rows in. Every row in that currency is then excluded from every total and named beside
 * it, which is honest, and it stays that way until a later response happens to include it.
 *
 * A gate that looked only at elapsed time would leave it that way for a full day and across app
 * restarts, on a device that was perfectly able to fetch. The cache is therefore judged on
 * whether it can do its job, not only on how old it is.
 *
 * The day gate survives, because a cache that *can* quote everything is left alone until it is
 * a day old: the point here is to recover from an incomplete cache promptly, not to refetch on
 * every dashboard entry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IncompleteCacheRefreshTest {

    private class FakeNbuApi(var body: String = BODY_WITHOUT_PLN) : NbuApi {
        var calls = 0
        override suspend fun exchangeRates(): okhttp3.ResponseBody {
            calls++
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
        settings.setBaseCurrency(DEFAULT_BASE_CURRENCY)
        settings.saveExchangeRates(ExchangeRates(emptyMap(), null, 0L))
        api = FakeNbuApi()
        repository = ExchangeRateRepository(api, settings)
    }

    @After
    fun tearDown() = runBlocking {
        settings.saveExchangeRates(ExchangeRates(emptyMap(), null, 0L))
    }

    @Test
    fun `a cache missing a recordable currency is refetched within the day`() = runBlocking {
        // PLN absent, so every zloty row is excluded from every total until a response
        // includes it. Fetched an hour ago, which the day gate alone would leave alone.
        api.body = BODY_WITHOUT_PLN
        repository.refresh(now)
        assertEquals(1, api.calls)
        assertTrue(settings.exchangeRates.first().toUah["PLN"] == null)

        // The next answer has it.
        api.body = BODY_WITH_PLN

        assertTrue(repository.refreshIfStale(now + hour))
        assertEquals(2, api.calls)
        assertEquals(11.6125, settings.exchangeRates.first().toUah["PLN"]!!, 0.0)
    }

    @Test
    fun `a cache that can quote everything is still left alone within the day`() = runBlocking {
        api.body = BODY_WITH_PLN
        repository.refresh(now)

        // The incompleteness check passes here, so the day gate decides, and it declines.
        assertFalse(repository.refreshIfStale(now + hour))
        assertEquals(1, api.calls)
    }

    @Test
    fun `a complete cache is refetched once it is a day old`() = runBlocking {
        api.body = BODY_WITH_PLN
        repository.refresh(now)

        assertTrue(repository.refreshIfStale(now + 25 * hour))
        assertEquals(2, api.calls)
    }

    @Test
    fun `a fetch that does not fix the gap is retried no more than once an hour`() = runBlocking {
        // NBU keeps omitting PLN. "Incomplete" does not become false on its own, so without an
        // interval of its own every dashboard entry would be a request to a public service. The
        // cache is still incomplete after an hour, so the attempt is deferred, not abandoned.
        api.body = BODY_WITHOUT_PLN
        repository.refresh(now)

        assertFalse(repository.refreshIfStale(now + 30 * 60_000L))
        assertEquals(1, api.calls)
        assertTrue(repository.refreshIfStale(now + 61 * 60_000L))
        assertEquals(2, api.calls)
    }

    @Test
    fun `an empty cache is refetched as soon as the retry interval is up`() = runBlocking {
        // A first launch with nothing cached: the answer holds no rates at all and every figure
        // would be unquotable. There is no sensible reading in which that is current.
        api.body = BODY_WITH_PLN
        repository.refresh(now)
        settings.saveExchangeRates(ExchangeRates(emptyMap(), "01.10.2026", now))

        assertTrue(repository.refreshIfStale(now + 61 * 60_000L))
        assertEquals(11.6125, settings.exchangeRates.first().toUah["PLN"]!!, 0.0)
    }

    private companion object {
        const val BODY_WITHOUT_PLN = """
            [{"r030":840,"txt":"Долар США","rate":44.6819,"cc":"USD","exchangedate":"01.10.2026","special":"N"},
             {"r030":978,"txt":"Євро","rate":50.7166,"cc":"EUR","exchangedate":"01.10.2026","special":null}]
        """
        const val BODY_WITH_PLN = """
            [{"r030":840,"txt":"Долар США","rate":44.6819,"cc":"USD","exchangedate":"01.10.2026","special":"N"},
             {"r030":978,"txt":"Євро","rate":50.7166,"cc":"EUR","exchangedate":"01.10.2026","special":null},
             {"r030":985,"txt":"Злотий","rate":11.6125,"cc":"PLN","exchangedate":"01.10.2026","special":null}]
        """
    }
}