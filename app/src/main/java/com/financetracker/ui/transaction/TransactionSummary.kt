package com.financetracker.ui.transaction

import com.financetracker.model.CurrencyTotals
import com.financetracker.model.Transaction
import com.financetracker.model.totalsByCurrency

/**
 * What the rows currently on screen add up to.
 *
 * Counted from the list the screen is already showing rather than from a second query, so the
 * two cannot disagree: whatever the filters and the search term have selected is exactly what
 * is totalled. Totals stay separated per currency for the reason the dashboard keeps them
 * apart — adding hryvnias to dollars yields a number that means nothing, and one labelled with
 * a currency makes it look real.
 */
data class TransactionSummary(
    val count: Int,
    val totals: List<CurrencyTotals>
) {
    val isEmpty: Boolean get() = count == 0

    companion object {
        val EMPTY = TransactionSummary(count = 0, totals = emptyList())

        fun of(transactions: List<Transaction>): TransactionSummary =
            if (transactions.isEmpty()) {
                EMPTY
            } else {
                TransactionSummary(
                    count = transactions.size,
                    totals = totalsByCurrency(transactions)
                )
            }
    }
}
