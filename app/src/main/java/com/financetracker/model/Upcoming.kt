package com.financetracker.model

import java.time.LocalDate
import java.time.ZoneId

/**
 * One projected occurrence, as the upcoming list shows it.
 *
 * The amount is in [currencyCode] and is *not* converted here: an item whose currency the cache
 * cannot quote is still listed, in its own currency, so that the exclusion the totals report can
 * be seen rather than taken on trust.
 */
data class UpcomingItem(
    val definitionId: Long,
    val title: String,
    val date: LocalDate,
    val amount: Double,
    val currencyCode: String,
    val type: TransactionType
)

/**
 * What is scheduled inside one window, converted into the base currency.
 *
 * The shape of [NetWorth] and deliberately not built on it: this figure is money that has *not*
 * moved yet, and it must never be added to the one that has. [expense] and [income] are null
 * together when the base itself cannot be quoted, because zero is the reading "nothing is
 * scheduled" and that must never be reached by accident.
 *
 * [unquoted] names every currency a scheduled amount was dropped for — the same rule the totals
 * use, for the same reason. [items] is everything in the window, unconverted, so the list under
 * the figure accounts for it.
 */
data class UpcomingTotals(
    val expense: Double?,
    val income: Double?,
    val unquoted: List<String?>,
    val rates: ExchangeRates,
    val items: List<UpcomingItem>
)

/**
 * Expands [definitions] over `[from, to)` and converts the result into [base].
 *
 * Archived definitions are skipped: archiving means "stop projecting this", and the list that
 * manages them is still the record of what they were. A definition the user has not yet reached
 * contributes nothing before its start date — [Recurrence] owns that rule, not this.
 *
 * Conversion goes through the same gate as [netWorth] and [convertTotals]: a currency the cache
 * cannot quote is left out of the totals *and named*, because a forecast that quietly omitted
 * part of itself would be printed with the same confidence as one that did not.
 */
fun upcoming(
    definitions: List<RecurringPayment>,
    rates: ExchangeRates,
    base: String,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId
): UpcomingTotals {
    val items = definitions
        .filter { !it.archived }
        .flatMap { definition ->
            Recurrence.occurrencesBetween(
                frequency = definition.frequency,
                intervalCount = definition.intervalCount,
                startDateMillis = definition.startDate,
                endDateMillis = definition.endDate,
                from = from,
                to = to,
                zone = zone
            ).map { date ->
                UpcomingItem(
                    definitionId = definition.id,
                    title = definition.title,
                    date = date,
                    amount = definition.amount,
                    currencyCode = definition.currencyCode,
                    type = definition.type
                )
            }
        }
        .sortedWith(compareBy({ it.date }, { it.definitionId }))

    // A base the cache cannot quote is no forecast at all, not a forecast of zero.
    if (rates.convert(1.0, base, base) == null) {
        return UpcomingTotals(null, null, listOf(base), rates, items)
    }

    val unquoted = linkedSetOf<String?>()
    var expense = 0.0
    var income = 0.0

    items.groupBy { it.currencyCode }.forEach { (currency, group) ->
        val expenseOfGroup = group.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amount }
        val incomeOfGroup = group.filter { it.type == TransactionType.INCOME }.sumOf { it.amount }

        // Converting the expense side is the gate; a group with no expense still has to pass it,
        // so `convert(0.0, …)` is asked rather than skipped — a zero amount does not make an
        // unquotable currency quotable.
        val convertedExpense = rates.convert(expenseOfGroup, currency, base)
        if (convertedExpense == null) {
            unquoted += currency
            return@forEach
        }
        expense += convertedExpense
        // Same currency, same rate, so this cannot fail where the line above succeeded.
        income += rates.convert(incomeOfGroup, currency, base) ?: 0.0
    }

    return UpcomingTotals(
        expense = expense,
        income = income,
        unquoted = unquoted.toList(),
        rates = rates,
        items = items
    )
}
