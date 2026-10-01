package com.financetracker.ui.transaction

import com.financetracker.model.ExchangeRates
import com.financetracker.model.Transaction
import com.financetracker.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionSummaryTest {

    /** Enough to quote everything the tests use, so the conversion is never the thing that fails. */
    private val rates = ExchangeRates(
        toUah = mapOf("USD" to 40.0, "EUR" to 50.0, "PLN" to 10.0),
        date = "01.10.2026",
        fetchedAt = 0L
    )

    private fun transaction(
        amount: Double,
        type: TransactionType,
        currencyCode: String? = "UAH"
    ) = Transaction(
        title = "row",
        amount = amount,
        type = type,
        category = "other",
        timestamp = 0,
        currencyCode = currencyCode
    )

    private fun of(vararg rows: Transaction) = TransactionSummary.of(rows.toList(), rates, "UAH")

    @Test
    fun `an empty list totals to nothing rather than to zero`() {
        // Zero is a real balance, and claiming it for a list that simply has no rows would
        // claim the user had broken even.
        val summary = TransactionSummary.of(emptyList(), rates, "UAH")
        assertTrue(summary.isEmpty)
        assertEquals(0, summary.count)
        assertEquals(emptyList<Any>(), summary.totals)
    }

    @Test
    fun `income and expenses are totalled separately`() {
        val summary = of(
            transaction(100.0, TransactionType.INCOME),
            transaction(30.0, TransactionType.EXPENSE),
            transaction(20.0, TransactionType.EXPENSE)
        )
        val totals = summary.totals.single()
        assertEquals(100.0, totals.income, 1e-9)
        assertEquals(50.0, totals.expense, 1e-9)
        assertEquals(50.0, totals.balance, 1e-9)
    }

    @Test
    fun `the count is the number of rows shown, not the number of groups`() {
        val summary = of(
            transaction(10.0, TransactionType.INCOME, "UAH"),
            transaction(10.0, TransactionType.EXPENSE, "UAH"),
            transaction(10.0, TransactionType.EXPENSE, "USD")
        )
        assertEquals(3, summary.count)
        assertEquals(2, summary.totals.size)
    }

    @Test
    fun `currencies are never added to one another in the raw totals`() {
        // The per-currency groups survive for anyone who needs them; it is the printed summary
        // that is converted, and `convertTotals` is where that happens.
        val summary = of(
            transaction(100.0, TransactionType.INCOME, "UAH"),
            transaction(100.0, TransactionType.INCOME, "USD")
        )
        assertEquals(2, summary.totals.size)
        summary.totals.forEach { assertEquals(100.0, it.income, 1e-9) }
    }

    @Test
    fun `a negative balance is negative, not a positive expense`() {
        val summary = of(transaction(40.0, TransactionType.EXPENSE))
        val totals = summary.totals.single()
        assertEquals(0.0, totals.income, 1e-9)
        assertEquals(-40.0, totals.balance, 1e-9)
    }

    @Test
    fun `a list of expenses only is not empty`() {
        val summary = of(transaction(40.0, TransactionType.EXPENSE))
        assertFalse(summary.isEmpty)
        assertEquals(1, summary.count)
    }

    @Test
    fun `fractions are not rounded away`() {
        val summary = of(
            transaction(0.1, TransactionType.INCOME),
            transaction(0.2, TransactionType.INCOME)
        )
        // 0.30000000000000004 in binary floating point; the total is summed, not tidied.
        assertEquals(0.3, summary.totals.single().income, 1e-9)
    }

    @Test
    fun `the printed summary is in the base currency`() {
        val summary = of(
            transaction(100.0, TransactionType.INCOME, "UAH"),
            transaction(100.0, TransactionType.INCOME, "USD")
        )
        // The whole reason this screen changed: the dashboard would say 5,000 and this one used
        // to print two separate 100s, leaving the user to reconcile them by hand.
        assertEquals(4_100.0, summary.converted.income!!, 1e-6)
        assertEquals("UAH", summary.converted.base)
    }

    @Test
    fun `an unquotable currency is named on the summary`() {
        val summary = of(
            transaction(100.0, TransactionType.INCOME, "UAH"),
            transaction(100.0, TransactionType.INCOME, "GBP")
        )
        assertEquals(100.0, summary.converted.income!!, 1e-6)
        assertEquals(listOf("GBP"), summary.converted.unquoted)
    }
}