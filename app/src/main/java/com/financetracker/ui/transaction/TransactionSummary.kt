package com.financetracker.ui.transaction

import com.financetracker.data.settings.DEFAULT_BASE_CURRENCY
import com.financetracker.model.ConvertedTotals
import com.financetracker.model.CurrencyTotals
import com.financetracker.model.ExchangeRates
import com.financetracker.model.ExclusionRules
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
 * than either choice alone.
 *
 * Two different things can be missing from those figures and they are reported separately, on
 * the same terms as everywhere else: a currency the cache cannot quote is left out and named in
 * [converted], and rows the user's own exclusion rules held back are counted in [excluded].
 * They are not merged because they are fixed in different places — one is a rate to fetch, the
 * other is a rule in Settings — and a user who cannot tell which is which cannot act on either.
 */
data class TransactionSummary(
    val count: Int,
    val totals: List<CurrencyTotals>,
    val converted: ConvertedTotals,
    /** Rows the user's [com.financetracker.model.ExclusionRules] kept out of the figures. */
    val excluded: Int = 0
) {
    val isEmpty: Boolean get() = count == 0

    companion object {
        val EMPTY = TransactionSummary(
            count = 0,
            totals = emptyList(),
            converted = ConvertedTotals(DEFAULT_BASE_CURRENCY, 0.0, 0.0, 0.0, emptyList()),
            excluded = 0
        )

/**
 * [transactions] is the whole list the screen is showing, and [rules] the user's own exclusion
 * rules. Everything else is derived here rather than by the caller, because two of the three
 * numbers have to agree with each other:
 *
 * - [count] is how many rows are on screen, not how many were summed. A panel that said "7
 *   transactions" under a list of ten would be the first thing anyone noticed, and the exclusion
 *   line below it would be explaining a discrepancy rather than a decision.
 * - the figures are built from the rows [rules] kept, and
 * - [excluded] is how many it dropped.
 *
 * Having one function derive all three is what stops a caller passing a pre-filtered list and
 * getting a count that no longer means what the list says.
 */
fun of(
    transactions: List<Transaction>,
    rules: ExclusionRules,
    rates: ExchangeRates,
    base: String,
    bondNominal: Double = 0.0
): TransactionSummary {
    val selection = rules.select(transactions)
    if (transactions.isEmpty()) {
        // Routed through `convertTotals` rather than built by hand, because that is what applies
        // the gate deciding whether the base can be quoted at all. Building the zeros here
        // skipped it, so an empty list on an unquotable base printed a confident `0.00 EUR`
        // where every other screen says "no rate" — and a zero reads as "you broke even", which
        // is the one claim an empty list cannot support. The bond figure and the base go through
        // with it, so a line the screen was showing a moment ago does not blank out.
        return EMPTY.copy(converted = convertTotals(emptyList(), rates, base, bondNominal))
    }

    val totals = totalsByCurrency(selection.counted)
    return TransactionSummary(
        count = transactions.size,
        totals = totals,
        converted = convertTotals(totals, rates, base, bondNominal),
        excluded = selection.excluded
    )
}
    }
}
