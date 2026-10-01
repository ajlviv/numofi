package com.financetracker.data.rates

import com.financetracker.model.RECORDABLE_CURRENCIES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NbuRatesParserTest {

    private val json = """
        [
          {"r030":36,"txt":"Австралійський долар","rate":31.1348,"cc":"AUD","exchangedate":"01.10.2026","special":null},
          {"r030":840,"txt":"Долар США","rate":44.6819,"cc":"USD","exchangedate":"01.10.2026","special":"N"},
          {"r030":978,"txt":"Євро","rate":50.7166,"cc":"EUR","exchangedate":"01.10.2026","special":null}
        ]
    """.trimIndent()

    @Test
    fun `keeps only currencies the app can record`() {
        val rates = NbuRatesParser.parse(json, now = 0L)
        assertEquals(setOf("USD", "EUR"), rates.toUah.keys)
        assertEquals(44.6819, rates.toUah["USD"]!!, 0.0)
        assertEquals(50.7166, rates.toUah["EUR"]!!, 0.0)
    }

    @Test
    fun `a currency with no row is simply absent rather than zero`() {
        val rates = NbuRatesParser.parse("""[{"cc":"USD","rate":44.0,"exchangedate":"01.10.2026"}]""", now = 0L)
        assertNull(rates.toUah["EUR"])
    }

    @Test
    fun `an empty response yields an empty cache with no date`() {
        val rates = NbuRatesParser.parse("[]", now = 0L)
        assertTrue(rates.toUah.isEmpty())
        // A cache built from nothing must not claim a date, or it reads as fresh.
        assertNull(rates.date)
    }

    @Test
    fun `the parsed cache stays inside the recordable set`() {
        // Guards against a later edit to RECORDABLE_CURRENCIES widening what gets cached.
        assertTrue(NbuRatesParser.parse(json, now = 0L).toUah.keys.all { it in RECORDABLE_CURRENCIES })
    }

    @Test
    fun `PLN is quoted and therefore kept`() {
        // PLN is the reason this list is not the obvious one. NBU publishes a rate for it, and a
        // currency the user holds but the app refuses to quote is excluded from every total and
        // named as missing — technically honest, and still an undercount. The two sets have to
        // agree: a currency that can be recorded must be one that can be converted.
        assertTrue("PLN" in RECORDABLE_CURRENCIES)
        val body = """[{"r030":985,"txt":"Злотий","rate":11.6125,"cc":"PLN","exchangedate":"01.10.2026"}]"""
        assertEquals(11.6125, NbuRatesParser.parse(body, now = 0L).toUah["PLN"]!!, 1e-9)
    }

    @Test
    fun `a row with no rate is skipped rather than cached as zero`() {
        // Gson leaves a missing Double as 0.0 without complaining, and a zero rate would sit
        // in the cache until something divided by it.
        val rates = NbuRatesParser.parse(
            """[{"cc":"USD","exchangedate":"01.10.2026"},{"cc":"EUR","rate":50.7166,"exchangedate":"01.10.2026"}]""",
            now = 0L
        )
        assertEquals(setOf("EUR"), rates.toUah.keys)
    }

    @Test
    fun `a row with a negative rate is skipped`() {
        val rates = NbuRatesParser.parse(
            """[{"cc":"USD","rate":-1.0,"exchangedate":"01.10.2026"}]""",
            now = 0L
        )
        assertTrue(rates.toUah.isEmpty())
    }

    @Test
    fun `malformed json throws instead of reading as a day with no rates`() {
        // Returning an empty cache here would be the one way this could destroy data: the
        // caller saves what it is given, and the user's last known rates would be replaced
        // by nothing because a proxy mangled the body.
        var threw = false
        try {
            NbuRatesParser.parse("<html>502 Bad Gateway</html>", now = 0L)
        } catch (expected: Exception) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `the date and fetch time are carried through`() {
        val rates = NbuRatesParser.parse(json, now = 1_700_000_000_000L)
        assertEquals("01.10.2026", rates.date)
        assertEquals(1_700_000_000_000L, rates.fetchedAt)
    }

    @Test
    fun `a blank date does not become a cached date of nothing`() {
        val rates = NbuRatesParser.parse("""[{"cc":"USD","rate":44.0}]""", now = 0L)
        assertNull(rates.date)
    }
}
