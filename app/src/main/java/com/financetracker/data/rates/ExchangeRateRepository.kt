package com.financetracker.data.rates

import com.financetracker.data.settings.SettingsRepository
import com.financetracker.model.ExchangeRates
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
     * Fetches at most once a day, on the way into the dashboard.
     *
     * A day rather than the three [ExchangeRates.isStale] tolerates, and the two numbers are
     * not interchangeable: this one is about not hammering a public service with four requests
     * because the user opened four screens, while that one is about how much trust a figure
     * has earned. Stretching the interval to match would hide a week-old rate behind a day-old
     * fetch; tightening the figure's window would blank out a weekend.
     */
    suspend fun refreshIfStale(now: Long = System.currentTimeMillis()): Boolean {
        val cached = settings.exchangeRates.first()
        if (now - cached.fetchedAt < REFRESH_INTERVAL_MILLIS) return false
        return refresh(now)
    }

    private companion object {
        const val TAG = "ExchangeRateRepository"
        const val REFRESH_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
    }
}
