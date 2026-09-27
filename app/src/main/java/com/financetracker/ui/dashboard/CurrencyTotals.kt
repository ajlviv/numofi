package com.financetracker.ui.dashboard

import com.financetracker.model.Transaction

/**
 * One currency's income, expense and balance.
 *
 * Totals are per currency rather than a single figure because adding hryvnias to dollars
 * produces a number that means nothing, and labelling that number with a currency made it
 * look like a real total. A history spanning several currencies gets one of these each.
 */
data class CurrencyTotals(
    val currencyCode: String?,
    val income: Double,
    val expense: Double
) {
    val balance: Double get() = income - expense
}

/**
 * Totals grouped by currency, most recently used first.
 *
 * A row whose code is missing forms its own group rather than joining a real one, so a
 * hand-entered row cannot inflate a known currency's total. Grouping keeps encounter order,
 * and the list arrives newest first, so the currency the user last transacted in leads.
 */
fun totalsByCurrency(transactions: List<Transaction>): List<CurrencyTotals> =
    transactions
        .groupBy { it.currencyCode }
        .map { (currency, rows) ->
            CurrencyTotals(
                currencyCode = currency,
                income = rows.filter { it.isIncome() }.sumOf { it.amount },
                expense = rows.filter { it.isExpense() }.sumOf { it.amount }
            )
        }
