package com.financetracker.model

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Cached official rates, quoted **to UAH**, and the conversion through them.
 *
 * The pivot is UAH and not the user's chosen base because NBU quotes everything against UAH,
 * including EUR. Storing rates to UAH therefore means one cache serves every base the user
 * might pick: switching base is arithmetic on cached numbers, so the preference can be changed
 * offline, instantly, and the cache is not invalidated by changing it.
 *
 * [convert] returns null for anything it cannot quote. A missing rate is not a zero, and
 * returning zero would let an unconvertible holding contribute nothing to a total that then
 * displays as if it were complete.
 */
data class ExchangeRates(
    /** Currency code to units of UAH. UAH is absent by definition; it converts at 1. */
    val toUah: Map<String, Double>,
    /** NBU's `exchangedate`, `dd.MM.yyyy`, or null when nothing has been fetched. */
    val date: String?,
    /** When this device last reached NBU, epoch millis. Not in the backup snapshot. */
    val fetchedAt: Long
) {

    /**
     * Parsed once rather than per call, and lazily so a cache that is never read against a
     * date — the conversion path, which is the one the dashboard takes on every frame — does
     * not pay for a parse it will not use.
     */
    private val parsedDate: LocalDate? by lazy {
        date?.let { DATE_FORMAT.parse(it, LocalDate::from) }
    }

    /**
     * [amount] of [from] expressed in [to], or null when either side cannot be quoted.
     *
     * [from] is nullable because [CurrencyTotals.currencyCode] is: a hand-entered row can
     * carry no code, and [totalsByCurrency] files such a row in a group of its own rather than
     * joining a real one. That group is not convertible, and guessing a rate for it would put
     * a number in the total that no source ever produced.
     */
    fun convert(amount: Double, from: String?, to: String): Double? {
        if (from == null) return null
        val fromRate = rateToUah(from) ?: return null
        val toRate = rateToUah(to) ?: return null
        return amount * fromRate / toRate
    }

    private fun rateToUah(code: String): Double? =
        if (code == PIVOT) 1.0 else toUah[code]?.takeIf { it > 0.0 }

    /**
     * Members of [required] this cache cannot quote.
     *
     * UAH is skipped because it converts at 1 by definition and is never in the cache: treating
     * it as missing would report the cache incomplete forever, and the caller would refetch on
     * every launch in a loop that can never succeed.
     *
     * Used to decide whether a cache is good enough, so that widening [required] takes effect
     * without waiting out a freshness window. A currency the app accepts but cannot convert is
     * excluded from every total and named beside it, which is honest and still an undercount;
     * a refresh gate keyed only on time would hold that state until the window expired.
     */
    fun unquotable(required: Collection<String>): List<String> =
        required.filter { it != PIVOT && rateToUah(it) == null }

    /**
     * True when the cache is old enough that a total built on it has to say where it came from.
     *
     * Three days rather than one, because NBU publishes on business days and a weekend plus a
     * holiday lands inside a shorter window without the figure being wrong. A cache with no
     * date counts as stale: assuming it fresh would be a guess in the direction of looking more
     * certain than the evidence is.
     *
     * [cachedDate] is a parameter so the boundary can be tested without a clock; production
     * callers pass nothing and get the parsed [date].
     */
    fun isStale(today: LocalDate, cachedDate: LocalDate? = parsedDate): Boolean =
        cachedDate == null || cachedDate.plusDays(MAX_AGE_DAYS).isBefore(today)

    private companion object {
        /** The unit every cached rate is quoted against. */
        const val PIVOT = "UAH"
        const val MAX_AGE_DAYS = 3L
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}
