package com.financetracker.data.rates

import com.financetracker.data.settings.SettingsRepository
import com.financetracker.model.ExchangeRates
import com.financetracker.model.RECORDABLE_CURRENCIES
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import android.util.Log
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place rates are fetched and the one place they are cached.
 *
 * Nothing here throws. A refresh that fails reports false and leaves the previous cache
 * exactly as it was, because the alternative is a user who opens the app with no network —
 * on a train, in a lift — and finds the net worth they saw yesterday replaced by nothing, with
 * no indication of why. A stale figure with its date on it is worth more than no figure.
 */
@Singleton
class ExchangeRateRepository @Inject constructor(
    private val api: NbuApi,
    private val settings: SettingsRepository
) {

    val baseCurrency: Flow<String> = settings.baseCurrency

    val rates: Flow<ExchangeRates> = settings.exchangeRates

    /**
     * Fetches unconditionally. Returns whether the cache now holds what NBU said.
     *
     * `false` covers all three ways it can go wrong — no network, an HTTP error, a body that is
     * not the directory — because from here they are the same thing: the user still has the
     * rates they had. They are logged separately, though, because they are not the same thing
     * to diagnose: a body that will not parse once in a while is a bug here, and reporting it to
     * the user as a network problem would send them to check their connection instead.
     */
    suspend fun refresh(now: Long = System.currentTimeMillis()): Boolean {
        val fetched = try {
            // `use` rather than a bare read: ResponseBody holds a connection, and a leak here
            // would exhaust the pool over a handful of daily fetches.
            val body = api.exchangeRates().use { it.string() }
            NbuRatesParser.parse(body, now)
        } catch (e: IOException) {
            Log.w(TAG, "NBU unreachable", e)
            return false
        } catch (e: HttpException) {
            Log.w(TAG, "NBU answered ${e.code()}", e)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "NBU body could not be read", e)
            return false
        }
        settings.saveExchangeRates(fetched)
        return true
    }

/**
 * Fetches at most once a day, on the way into the dashboard — and straight away whenever the
 * cache cannot quote a currency the app accepts.
 *
 * A day rather than the three [ExchangeRates.isStale] tolerates, and the two numbers are
 * not interchangeable: this one is about not hammering a public service with four requests
 * because the user opened four screens, while that one is about how much trust a figure
 * has earned. Stretching the interval to match would hide a week-old rate behind a day-old
 * fetch; tightening the figure's window would blank out a weekend.
 */
    suspend fun refreshIfStale(now: Long = System.currentTimeMillis()): Boolean {
        val cached = settings.exchangeRates.first()
        // Two gates, and the second one is the point of the method being here at all. Elapsed
        // time alone would leave a cache that cannot quote a currency the app can record in for
        // a full day and across restarts, on a device that was quite able to fetch: NBU publishes
        // some currencies only intermittently, so a response can simply leave one out. Every row
        // in that currency is then excluded from every total and named beside it — honest, and
        // wrong until a later response happens to carry it.
        //
        // An incomplete cache is therefore refetched on its own, shorter clock. The clock is what
        // keeps it from spinning: NBU may keep omitting the currency, and "incomplete" does not
        // become false on its own, so without an interval every dashboard entry would be a
        // request. An hour is the compromise — a currency published once a day is retried well
        // within the day it reappears, and a user opening four screens makes one request, not
        // four.
        val complete = cached.unquotable(RECORDABLE_CURRENCIES).isEmpty()
        val interval = if (complete) REFRESH_INTERVAL_MILLIS else RECOVERY_INTERVAL_MILLIS
        if (now - cached.fetchedAt < interval) return false
        return refresh(now)
    }

    private companion object {
        const val TAG = "ExchangeRateRepository"
        const val REFRESH_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

        /**
         * How often a cache that cannot quote a currency the app records in is retried.
         *
         * Shorter than [REFRESH_INTERVAL_MILLIS] because the gap is the app's problem rather
         * than the figure's age, and short enough to stay short: NBU publishes some currencies
         * infrequently, so the answer stops being absent well within a day. Long enough to
         * remain a throttle rather than a per-read fetch.
         */
        const val RECOVERY_INTERVAL_MILLIS = 60L * 60 * 1000
    }
}
