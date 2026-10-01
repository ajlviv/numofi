package com.financetracker.ui.transaction

import com.financetracker.data.settings.DEFAULT_BASE_CURRENCY
import com.financetracker.model.ConvertedTotals
import com.financetracker.model.CurrencyTotals
import com.financetracker.model.ExchangeRates
import com.financetracker.model.Transaction
import com.financetracker.model.convertTotals
import com.financetracker.model.totalsByCurrency

/**
 * What the rows currently on screen add up to.
 *
 * Counted from the list the screen is already showing rather than from a second query, so the
 * two cannot disagree: whatever the filters and the search term have selected is exactly what
 * is totalled.
 *
 * The figures are converted into [base] rather than printed per currency, because the dashboard
 * does the same and two screens answering the same question in two different currencies is worse
 * than either choice alone. What was dropped is named in [converted], on the same terms as
 * everywhere else: a currency the cache cannot quote is left out and named.
 */
data class TransactionSummary(
    val count: Int,
    val totals: List<CurrencyTotals>,
    val converted: ConvertedTotals
) {
    val isEmpty: Boolean get() = count == 0

    companion object {
        val EMPTY = TransactionSummary(
            count = 0,
            totals = emptyList(),
            converted = ConvertedTotals(DEFAULT_BASE_CURRENCY, 0.0, 0.0, 0.0, emptyList())
        )

fun of(
    transactions: List<Transaction>,
    rates: ExchangeRates,
    base: String,
    bondNominal: Double = 0.0
): TransactionSummary =
    if (transactions.isEmpty()) {
        EMPTY.copy(converted = ConvertedTotals(base, 0.0, 0.0, 0.0, emptyList(), bondNominal))
    } else {
        val totals = totalsByCurrency(transactions)
        TransactionSummary(
            count = transactions.size,
            totals = totals,
            converted = convertTotals(totals, rates, base, bondNominal)
        )
    }
    }
}
