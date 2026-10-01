package com.financetracker.model

/**
 * One total across currencies, and everything that could not be part of it.
 *
 * Both omissions exist because the alternative is worse. A total that quietly omits what it
 * could not include is displayed with exactly the same confidence as one that could, and the
 * user has no way to tell the difference — the number looks finished. Naming the omissions
 * costs one line of text and turns a lie into a caveat.
 *
 * [unquoted] is the omission this app did not choose: a currency the rate cache cannot convert.
 * [excluded] is one the user chose: rows their own [ExclusionRules] held back. They are separate
 * fields, not one combined list, because they are fixed in different places — one is a rate to
 * fetch, the other is a rule in Settings — and a user who cannot tell which is which has no way
 * to act on either.
 *
 * A null entry in [unquoted] is a group of rows with no currency code at all, which
 * [totalsByCurrency] files separately rather than joining a real currency.
 */
data class NetWorth(
    /** Null only when the cache cannot quote the base itself; see [netWorth]. */
    val total: Double?,
    /** Converted the same way as [total], and null for the same reason. */
    val income: Double?,
    val expense: Double?,
    val unquoted: List<String?>,
    /**
     * How many rows the user's rules kept out of the figures above. Zero whenever no rule
     * matches, which is why it is a defaulted argument rather than something every caller has
     * to supply: a total computed from a list that was never filtered has excluded nothing.
     */
    val excluded: Int = 0,
    val rates: ExchangeRates
)

/**
 * Cash at ledger balance plus bonds at nominal, converted into [base].
 *
 * [totals] is expected to have already had the user's [ExclusionRules] applied; [excluded] is the
 * count of what that removed, carried here only to be reported. This function does not filter,
 * and should not: a totals function applying a user preference is not what it is for, and the
 * rows it would have dropped would be dropped for the dashboard and the list by two different
 * calls that could then disagree.
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
    base: String,
    excluded: Int = 0
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
            excluded = excluded,
            rates = rates
        )
    }

    return NetWorth(
        total = cash + bondValue,
        income = income,
        expense = expense,
        unquoted = unquoted.toList(),
        excluded = excluded,
        rates = rates
    )
}
