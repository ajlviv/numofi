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
 * The windows the plans screen offers, as half-open `[from, to)` day ranges.
 *
 * Mirrors `DatePreset` in the transaction list: a small enum that turns "today" into a range,
 * so the screen owns no date arithmetic. The ranges are half-open for the same reason the
 * transaction filter and [Recurrence] are — a period's `to` is the next period's `from`, so two
 * adjacent windows never both claim a day.
 *
 * Three of the four start today and are pure forecasts. [NEXT_MONTH] is the whole next calendar
 * month, and [THIS_YEAR] is the whole calendar year, so each can include an occurrence that
 * already passed — for the year that is usually just an annual charge earlier in the year. A
 * "this month" window used to sit here and was dropped: on the 17th it was ninety-odd percent
 * past, a figure that answered nothing the "30 days" chip did not answer better.
 *
 * No label lives here. The strings are resolved at the display site, the same rule the
 * screen's message flow follows.
 */
enum class UpcomingPeriod {
    NEXT_30_DAYS,
    NEXT_MONTH,
    NEXT_12_MONTHS,
    THIS_YEAR;

    fun range(today: LocalDate): Pair<LocalDate, LocalDate> = when (this) {
        NEXT_30_DAYS -> today to today.plusDays(30)
        NEXT_MONTH -> {
            val firstOfNextMonth = today.withDayOfMonth(1).plusMonths(1)
            firstOfNextMonth to firstOfNextMonth.plusMonths(1)
        }
        NEXT_12_MONTHS -> today to today.plusDays(365)
        THIS_YEAR -> {
            val firstOfYear = today.withDayOfYear(1)
            firstOfYear to firstOfYear.plusYears(1)
        }
    }
}

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
