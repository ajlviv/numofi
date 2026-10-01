package com.financetracker.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ExchangeRatesTest {

    // The NBU figures read on 2026-10-01, which are the rates the design was argued over.
    private val rates = ExchangeRates(
        toUah = mapOf("USD" to 44.6819, "EUR" to 50.7166),
        date = "01.10.2026",
        fetchedAt = 0L
    )

    @Test
    fun `converts through the UAH pivot rather than needing a base rate`() {
        // 100 EUR priced in USD is 50.7166 / 44.6819 = 113.505916, not the other way round.
        assertEquals(113.505916, rates.convert(100.0, "EUR", "USD")!!, 1e-4)
    }

    @Test
    fun `UAH converts at one and needs no stored rate`() {
        assertEquals(500.0, rates.convert(500.0, "UAH", "UAH")!!, 0.0)
        assertEquals(44.6819, rates.convert(1.0, "USD", "UAH")!!, 1e-4)
    }

    @Test
    fun `a currency with no rate converts to null and never to zero`() {
        // Zero would let an unconvertible holding contribute nothing to a total that then
        // displays as if it were complete.
        assertNull(rates.convert(100.0, "GBP", "UAH"))
        assertNull(rates.convert(100.0, "USD", "GBP"))
    }

    @Test
    fun `a row with no currency code converts to null`() {
        // totalsByCurrency forms a currency-less group rather than joining a real one, and
        // that group must not be silently folded into the total.
        assertNull(rates.convert(100.0, null, "UAH"))
    }

    @Test
    fun `an empty cache converts nothing at all`() {
        val empty = ExchangeRates(emptyMap(), null, 0L)
        assertNull(empty.convert(100.0, "USD", "UAH"))
        assertEquals(100.0, empty.convert(100.0, "UAH", "UAH")!!, 0.0)
    }

    @Test
    fun `staleness is measured in days and tolerates a weekend`() {
        // Boundary: three days is still fresh, four is not.
        val cachedDate = LocalDate.of(2026, 10, 1)
        assertFalse(rates.isStale(cachedDate.plusDays(3), cachedDate))
        assertTrue(rates.isStale(cachedDate.plusDays(4), cachedDate))
    }

    @Test
    fun `a cache with no date is stale rather than assumed fresh`() {
        assertTrue(ExchangeRates(mapOf("USD" to 44.0), null, 0L).isStale(LocalDate.of(2026, 10, 1)))
    }

    @Test
    fun `the date NBU sends is parsed for the default staleness read`() {
        // The production call passes no date, so the dd.MM.yyyy parse is on the path and
        // has to be right without a test supplying the parsed value for it.
        assertFalse(rates.isStale(LocalDate.of(2026, 10, 1)))
        assertTrue(rates.isStale(LocalDate.of(2026, 10, 10)))
    }

    @Test
    fun `UAH is never reported as unquotable`() {
        // It converts at 1 and is deliberately absent from the cache. Counting it as missing
        // would make every cache look incomplete and every dashboard entry refetch.
        assertTrue(
            ExchangeRates(emptyMap(), null, 0L)
                .unquotable(listOf("UAH")).isEmpty()
        )
    }

    @Test
    fun `a currency the cache cannot quote is reported by code`() {
        val cached = ExchangeRates(mapOf("USD" to 44.0, "EUR" to 50.0), "01.10.2026", 0L)
        assertEquals(listOf("PLN"), cached.unquotable(listOf("UAH", "USD", "EUR", "PLN")))
    }

    @Test
    fun `a zero rate counts as unquotable rather than as free`() {
        // A rate of zero would otherwise pass a `containsKey` check and divide to infinity.
        val cached = ExchangeRates(mapOf("PLN" to 0.0), "01.10.2026", 0L)
        assertEquals(listOf("PLN"), cached.unquotable(listOf("PLN")))
    }
}
