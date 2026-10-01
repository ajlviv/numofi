package com.financetracker.data.rates

import com.financetracker.model.ExchangeRates
import com.financetracker.model.RECORDABLE_CURRENCIES
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Turns NBU's flat directory into a cache holding only the currencies this app can record.
 *
 * The response lists roughly forty currencies and two of them are wanted. Parsing is kept
 * apart from the network call so that filtering decision is testable without a socket, and so
 * the filtering is stated in one place rather than spread across a converter and a repository.
 *
 * UAH is absent from the response and absent from the cache, correctly: it is the unit every
 * other rate is quoted against rather than a quoted currency, and [ExchangeRates] converts it
 * at 1 without needing a row.
 */
object NbuRatesParser {

    private val gson = Gson()
    private val rowList = object : TypeToken<List<NbuRateResponse>>() {}.type

    /**
     * [now] is injected so a test can assert the stored fetch time; production omits it.
     *
     * Throws rather than returning an empty cache for a body it cannot read, and that is the
     * one behaviour here worth being strict about. The caller saves whatever it is handed, so
     * an empty result arriving from a mangled response would replace the user's last known
     * rates with nothing — the failure would look like success all the way to the screen.
     */
    fun parse(body: String, now: Long = System.currentTimeMillis()): ExchangeRates {
        val rows: List<NbuRateResponse> = gson.fromJson(body, rowList) ?: emptyList()

        val kept = rows.mapNotNull { row ->
            val code = row.currencyCode?.takeIf { it in RECORDABLE_CURRENCIES } ?: return@mapNotNull null
            val rate = row.rate?.takeIf { it > 0.0 } ?: return@mapNotNull null
            code to rate
        }

        return ExchangeRates(
            toUah = kept.toMap(),
            // The directory carries one date for the whole day, so the first row speaks for
            // all of them. Null when nothing was kept: a cache with no rows has no date to
            // report, and one that claimed a date would read as fresh.
            date = rows.firstOrNull()?.exchangeDate?.takeIf { it.isNotBlank() },
            fetchedAt = now
        )
    }
}
