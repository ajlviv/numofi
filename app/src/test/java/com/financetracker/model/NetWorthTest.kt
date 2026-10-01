package com.financetracker.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What goes into the one number on the dashboard, and what is kept out of it.
 *
 * The exclusion cases are the point of this file. Every one of them has a version that looks
 * right in code review and is quietly wrong on screen: a total that quietly omits a holding
 * it could not convert is presented with the same confidence as one that could.
 */
class NetWorthTest {

    private val rates = ExchangeRates(
        toUah = mapOf("USD" to 40.0, "EUR" to 50.0),
        date = "01.10.2026",
        fetchedAt = 0L
    )

    private fun position(
        nominalCurrency: String,
        nominal: Double,
        quantity: Int = 1,
        lastPrice: Double? = null
    ) = BondPosition(
        bond = Bond(
            isin = "UA0000000001",
            name = "ОВДП",
            nominal = nominal,
            nominalCurrency = nominalCurrency
        ),
        quantity = quantity,
        cost = 0.0,
        averageCost = null,
        lastPrice = lastPrice,
        annualCouponIncome = null,
        heldSince = 0L
    )

    private fun cash(code: String?, balance: Double) =
        CurrencyTotals(
            currencyCode = code,
            income = if (balance >= 0) balance else 0.0,
            expense = if (balance < 0) -balance else 0.0
        )

    @Test
    fun `adds converted cash to converted bond nominal`() {
        val result = netWorth(
            totals = listOf(cash("UAH", 1_000.0)),
            positions = listOf(position("USD", nominal = 100.0)),
            rates = rates,
            base = "UAH"
        )
        // 1000 UAH cash, plus one bond of 100 USD nominal at 40.
        assertEquals(5_000.0, result.total!!, 1e-6)
        assertTrue(result.unquoted.isEmpty())
    }

    @Test
    fun `bonds count at nominal and never at the last price the user typed`() {
        val result = netWorth(
            totals = emptyList(),
            positions = listOf(position("UAH", nominal = 20_000.0, lastPrice = 3.0)),
            rates = rates,
            base = "UAH"
        )
        // marketValue would be 3.0 here. Nominal is what the state repays at maturity;
        // lastPrice is whatever the user typed, possibly months ago.
        assertEquals(20_000.0, result.total!!, 1e-6)
    }

    @Test
    fun `a currency with no rate is left out and named`() {
        val result = netWorth(
            totals = listOf(cash("UAH", 1_000.0), cash("GBP", 500.0)),
            positions = emptyList(),
            rates = rates,
            base = "UAH"
        )
        assertEquals(1_000.0, result.total!!, 1e-6)
        assertEquals(listOf("GBP"), result.unquoted)
    }

    @Test
    fun `a currency-less group is left out rather than guessed at`() {
        val result = netWorth(
            totals = listOf(cash(null, 999.0)),
            positions = emptyList(),
            rates = rates,
            base = "UAH"
        )
        // The amount is real and the total cannot include it. Reporting 0.0 as "total" and
        // saying nothing is the failure this exists to prevent.
        assertEquals(0.0, result.total!!, 1e-6)
        assertEquals(listOf<String?>(null), result.unquoted)
    }

    @Test
    fun `an unquotable bond denomination is named too`() {
        val result = netWorth(
            totals = emptyList(),
            positions = listOf(position("GBP", nominal = 1_000.0)),
            rates = rates,
            base = "UAH"
        )
        assertEquals(0.0, result.total!!, 1e-6)
        assertEquals(listOf("GBP"), result.unquoted)
    }

    @Test
    fun `a base the cache cannot quote yields no total at all`() {
        val result = netWorth(
            totals = listOf(cash("UAH", 1_000.0)),
            positions = emptyList(),
            rates = rates,
            base = "JPY"
        )
        // Null rather than zero. Zero would be indistinguishable from "you own nothing", which
        // is the one reading that must never be available by accident.
        assertNull(result.total)
    }

    @Test
    fun `an empty ledger totals zero in a quotable base`() {
        // The honest zero, distinct from the case above by the cache being able to quote at all.
        val result = netWorth(emptyList(), emptyList(), rates, "UAH")
        assertEquals(0.0, result.total!!, 1e-6)
        assertTrue(result.unquoted.isEmpty())
    }

    @Test
    fun `a fully sold position counts nothing and is not reported as missing`() {
        val result = netWorth(
            totals = emptyList(),
            positions = listOf(position("GBP", nominal = 1_000.0, quantity = 0)),
            rates = rates,
            base = "UAH"
        )
        // Nothing is held, so a missing GBP rate is irrelevant and must not be surfaced as if
        // something had been left out.
        assertEquals(0.0, result.total!!, 1e-6)
        assertTrue(result.unquoted.isEmpty())
    }

    @Test
    fun `a negative balance reduces the total rather than disappearing`() {
        val result = netWorth(
            totals = listOf(cash("UAH", -400.0)),
            positions = listOf(position("UAH", nominal = 1_000.0)),
            rates = rates,
            base = "UAH"
        )
        assertEquals(600.0, result.total!!, 1e-6)
    }

    @Test
    fun `positions in three currencies are each converted before being added`() {
        val result = netWorth(
            totals = emptyList(),
            positions = listOf(
                position("USD", nominal = 100.0),
                position("EUR", nominal = 100.0),
                position("UAH", nominal = 100.0)
            ),
            rates = rates,
            base = "UAH"
        )
        // 4000 + 5000 + 100, added only after each was converted.
        assertEquals(9_100.0, result.total!!, 1e-6)
    }

    @Test
    fun `the cache that produced the total travels with it`() {
        // The card has to say where the number came from, and reading the date off a
        // separately-read cache would let the label and the figure disagree.
        val result = netWorth(emptyList(), emptyList(), rates, "UAH")
        assertEquals(rates, result.rates)
    }

    @Test
    fun `income and expense are converted on the same terms as the balance`() {
        val result = netWorth(
            totals = listOf(
                CurrencyTotals(currencyCode = "USD", income = 100.0, expense = 40.0),
                CurrencyTotals(currencyCode = "UAH", income = 500.0, expense = 100.0)
            ),
            positions = emptyList(),
            rates = rates,
            base = "UAH"
        )
        // 100 USD in at 40, 500 UAH in; 40 USD out at 40, 100 UAH out.
        assertEquals(4_500.0, result.income!!, 1e-6)
        assertEquals(1_700.0, result.expense!!, 1e-6)
    }

    @Test
    fun `an unquotable currency is left out of the flows as well as the balance`() {
        // The point of putting the flows through the same gate: if GBP were dropped from the
        // balance but kept in income, the two lines under the total would no longer add up to
        // the figure they are there to explain.
        val result = netWorth(
            totals = listOf(
                CurrencyTotals(currencyCode = "UAH", income = 500.0, expense = 100.0),
                CurrencyTotals(currencyCode = "GBP", income = 900.0, expense = 300.0)
            ),
            positions = emptyList(),
            rates = rates,
            base = "UAH"
        )
        assertEquals(500.0, result.income!!, 1e-6)
        assertEquals(100.0, result.expense!!, 1e-6)
        assertEquals(listOf("GBP"), result.unquoted)
    }

    @Test
    fun `flows are null for the same reason the total is`() {
        val result = netWorth(
            totals = listOf(cash("UAH", 100.0)),
            positions = emptyList(),
            rates = rates,
            base = "GBP"
        )
        // A base the cache cannot quote means no total, and a figure beside a missing one would
        // be the same claim made more quietly.
        assertNull(result.total)
        assertNull(result.income)
        assertNull(result.expense)
    }
}
