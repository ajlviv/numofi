package com.financetracker.model

/**
 * One total across currencies, and the currencies that could not be part of it.
 *
 * [unquoted] exists because the alternative is worse. A total that quietly omits what it could
 * not convert is displayed with exactly the same confidence as one that could, and the user has
 * no way to tell the difference — the number looks finished. Naming the omissions costs one
 * line of text and turns a lie into a caveat.
 *
 * A null entry is a group of rows with no currency code at all, which [totalsByCurrency]
 * files separately rather than joining a real currency.
 */
data class NetWorth(
    /** Null only when the cache cannot quote the base itself; see [netWorth]. */
    val total: Double?,
    /** Converted the same way as [total], and null for the same reason. */
    val income: Double?,
    val expense: Double?,
    val unquoted: List<String?>,
    val rates: ExchangeRates
)

/**
 * Cash at ledger balance plus bonds at nominal, converted into [base].
 *
 * Bonds contribute [Bond.nominal] × quantity and **not** [BondPosition.marketValue]. Nominal is
 * what the state repays at maturity, so the figure is one that will actually be received;
 * `marketValue` is the last price the user typed in, and ОВДП trade at a discount to par, so a
 * market-value total would be a claim about today's price built from a number that may be
 * months old. The bond card already says this under `dash_honest_label`, and a headline must
 * not contradict a caveat printed nine lines below it.
 *
 * Nothing is summed before it is converted. One expression adding hryvnias to dollars is the
 * mistake this whole type exists to prevent.
 */
fun netWorth(
    totals: List<CurrencyTotals>,
    positions: List<BondPosition>,
    rates: ExchangeRates,
    base: String
): NetWorth {
    val unquoted = linkedSetOf<String?>()

    // Every figure below goes through the same gate, so that one currency being unquotable
    // takes its income and its expenses out of the total *and* out of the two lines beside it.
    // Converting the balance but not the flows would leave income and expense disagreeing with
    // the sum they are supposed to explain.
    var cash = 0.0
    var income = 0.0
    var expense = 0.0
    totals.forEach { group ->
        // Deliberately not `convert(...)?.also { if (it == null) ... }`: a safe call
        // short-circuits on null, so the branch recording the omission would never run — the
        // exact case it was written for.
        val convertedBalance = rates.convert(group.balance, group.currencyCode, base)
        if (convertedBalance == null) {
            unquoted += group.currencyCode
            return@forEach
        }
        cash += convertedBalance
        // A group with a quotable balance has quotable flows too — same currency, same rate —
        // so these two cannot fail where the line above succeeded.
        income += rates.convert(group.income, group.currencyCode, base) ?: 0.0
        expense += rates.convert(group.expense, group.currencyCode, base) ?: 0.0
    }

    // Grouped by denomination so that a currency is converted once rather than once per
    // holding, and so the answer does not depend on the order positions happen to arrive in.
    val bondValue = positions
        .filter { it.quantity > 0 }
        .groupBy { it.bond.nominalCurrency }
        .map { (currency, held) ->
            val nominal = held.sumOf { it.quantity * it.bond.nominal }
            val converted = rates.convert(nominal, currency, base)
            if (converted == null) {
                unquoted += currency
                0.0
            } else {
                converted
            }
        }
        .sum()

    // A base the cache cannot quote is not a total of zero: it is no total at all. Zero is the
    // reading "you own nothing", which must never be available by accident.
    if (rates.convert(1.0, base, base) == null) {
        return NetWorth(
            total = null,
            income = null,
            expense = null,
            unquoted = unquoted.toList(),
            rates = rates
        )
    }

    return NetWorth(
        total = cash + bondValue,
        income = income,
        expense = expense,
        unquoted = unquoted.toList(),
        rates = rates
    )
}
