package com.financetracker.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The omission count on a total, and the one thing it must never be.
 *
 * The count is what makes a user's own exclusion rule legible. Without it, adding a rule silently
 * moves a headline and the user has no way to find the reason; the same failure `NetWorth.unquoted`
 * already guards against for a currency the rate cache cannot quote, and by the same argument.
 */
class ExcludedTotalsTest {

    private val rates = ExchangeRates(mapOf("UAH" to 1.0), "01.10.2026", 1L)

    private fun tx(title: String, amount: Double, type: TransactionType = TransactionType.EXPENSE) =
        Transaction(
            title = title,
            amount = amount,
            type = type,
            category = "Other",
            timestamp = 0L,
            currencyCode = "UAH"
        )

    @Test
    fun `a total says how many rows the user's rules held back`() {
        val rows = listOf(
            tx("Salary", 1000.0, TransactionType.INCOME),
            tx("Transfer to savings", 500.0),
            tx("Groceries", 100.0)
        )
        val selection = ExclusionRules(listOf("transfer")).select(rows)

        val result = netWorth(
            totals = totalsByCurrency(selection.counted),
            positions = emptyList(),
            rates = rates,
            base = "UAH",
            excluded = selection.excluded
        )

        assertEquals(1, result.excluded)
    }

    @Test
    fun `the total itself is built from the counted rows only`() {
        val rows = listOf(
            tx("Salary", 1000.0, TransactionType.INCOME),
            tx("Transfer to savings", 500.0)
        )
        val selection = ExclusionRules(listOf("transfer")).select(rows)

        val result = netWorth(
            totals = totalsByCurrency(selection.counted),
            positions = emptyList(),
            rates = rates,
            base = "UAH",
            excluded = selection.excluded
        )

        // 1000 in, nothing out: the 500 is in the ledger and out of the total, which is the
        // whole point of the rule and the reason the count has to be printed beside the figure.
        assertEquals(1000.0, result.total!!, 0.001)
        assertEquals(1000.0, result.income!!, 0.001)
        assertEquals(0.0, result.expense!!, 0.001)
    }

    @Test
    fun `with no rules the count is zero rather than absent`() {
        val result = netWorth(
            totals = emptyList(),
            positions = emptyList(),
            rates = rates,
            base = "UAH"
        )

        assertEquals(0, result.excluded)
    }

    @Test
    fun `a base that cannot be quoted still reports what was excluded`() {
        // The two omissions are independent. A device with no rate for its own base shows no
        // total at all, and a rule that happened to drop rows is still a fact about the
        // data — reporting it costs one line and costs nothing when there is no figure.
        //
        // GBP rather than UAH because UAH is the pivot and converts at 1 by definition, so it
        // can never be the base this branch is about.
        val result = netWorth(
            totals = emptyList(),
            positions = emptyList(),
            rates = rates,
            base = "GBP",
            excluded = 3
        )

        assertEquals(null, result.total)
        assertEquals(3, result.excluded)
    }

    @Test
    fun `a currency that cannot be quoted and a rule that excluded rows are both named`() {
        val rows = listOf(
            tx("Transfer", 100.0),
            tx("Groceries", 50.0)
        )
        val selection = ExclusionRules(listOf("transfer")).select(rows)
        val totals = totalsByCurrency(selection.counted) + CurrencyTotals("GBP", 900.0, 0.0)

        val result = netWorth(
            totals = totals,
            positions = emptyList(),
            rates = rates,
            base = "UAH",
            excluded = selection.excluded
        )

        // Both gates hold at once, and neither swallows the other. Collapsing them into one
        // "what was left out" line would leave the user unable to tell a rate problem from
        // their own rule, which are fixed in completely different places.
        assertEquals(listOf("GBP"), result.unquoted)
        assertEquals(1, result.excluded)
    }
}
