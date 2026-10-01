package com.financetracker.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transaction list's summary, in the base currency.
 *
 * The list and the dashboard now answer in the same currency, which is the point: two screens
 * answering the same question differently is how a user ends up not trusting either. These are
 * the cases where that conversion has to admit something rather than produce a clean number.
 */
class ConvertedTotalsTest {

    private val rates = ExchangeRates(
        toUah = mapOf("USD" to 40.0, "EUR" to 50.0, "PLN" to 10.0),
        date = "01.10.2026",
        fetchedAt = 0L
    )

    private fun group(code: String?, income: Double, expense: Double, transferOut: Double = 0.0) =
        CurrencyTotals(
            currencyCode = code,
            income = income,
            expense = expense,
            transferOut = transferOut
        )

    @Test
    fun `adds currencies only after converting each`() {
        val result = convertTotals(
            listOf(
                group("UAH", income = 1_000.0, expense = 400.0),
                group("USD", income = 100.0, expense = 40.0)
            ),
            rates,
            "UAH"
        )
        // 1000 UAH plus 100 USD at 40 = 5,000; 400 UAH plus 40 USD at 40 = 2,000.
        assertEquals(5_000.0, result.income!!, 1e-6)
        assertEquals(2_000.0, result.expense!!, 1e-6)
        assertEquals(3_000.0, result.balance!!, 1e-6)
    }

    @Test
    fun `balance keeps transfers, which is what separates it from income minus expense`() {
        // A bond purchase leaves the account without ever being spending. A summary built from
        // income minus expense would not match the ledger the list below it is drawing from.
        val result = convertTotals(
            listOf(group("UAH", income = 1_000.0, expense = 0.0, transferOut = 250.0)),
            rates,
            "UAH"
        )
        assertEquals(750.0, result.balance!!, 1e-6)
        assertEquals(0.0, result.expense!!, 1e-6)
    }

    @Test
    fun `names a currency it could not quote and leaves it out`() {
        val result = convertTotals(
            listOf(
                group("UAH", income = 500.0, expense = 0.0),
                group("GBP", income = 900.0, expense = 0.0)
            ),
            rates,
            "UAH"
        )
        assertEquals(500.0, result.income!!, 1e-6)
        assertEquals(listOf("GBP"), result.unquoted)
    }

    @Test
    fun `keeps a missing currency code distinguishable from a real one`() {
        val result = convertTotals(
            listOf(
                group("UAH", income = 500.0, expense = 0.0),
                group(null, income = 700.0, expense = 0.0)
            ),
            rates,
            "UAH"
        )
        assertEquals(500.0, result.income!!, 1e-6)
        assertEquals(listOf<String?>(null), result.unquoted)
    }

    @Test
    fun `an unquotable base gives no figures at all rather than zeroes`() {
        val result = convertTotals(listOf(group("UAH", 500.0, 100.0)), rates, "GBP")
        // Zero would read as "nothing came in, nothing went out", which is the one reading a
        // missing rate must not be able to produce.
        assertNull(result.income)
        assertNull(result.expense)
        assertNull(result.balance)
        assertTrue(result.unquoted.contains("UAH"))
    }

    @Test
    fun `the base is quoted even when no other currency is`() {
        val result = convertTotals(listOf(group("UAH", 500.0, 100.0)), rates, "UAH")
        assertEquals(500.0, result.income!!, 1e-6)
        assertTrue(result.unquoted.isEmpty())
    }
}