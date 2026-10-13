package com.financetracker.model

/**
 * One currency's income, expense and balance, expressed in the base currency.
 *
 * Every figure is nullable together. There is no such thing as a converted income without a
 * converted balance: both come from the same rows through the same rate, so if the base is
 * quotable they are quotable too, and if it is not, none of them is. Splitting them would let a
 * screen print a figure beside a "no rate available" and imply the rest was sound.
 *
 * [unquoted] names the currencies that were dropped rather than silently losing them, and a null
 * entry is a group of rows with no currency code at all.
 */
data class ConvertedTotals(
    val base: String,
    val income: Double?,
    val expense: Double?,
    val balance: Double?,
    val unquoted: List<String?>,
    /** Nominal of all bond positions, added to the cash balance so the total matches the dashboard. */
    val bondNominal: Double = 0.0,
    /**
     * How many holdings the user's rules kept out of [balance], reported so the list screen can
     * name what it left out rather than quietly showing a smaller figure. Separate from
     * [com.financetracker.model.NetWorth.excluded] because they are removed in different places.
     */
    val excludedHoldings: Int = 0
)

/**
 * Converts [totals] into [base], one currency at a time.
 *
 * The same rule as [netWorth], and for the same reason: a currency the cache cannot quote is
 * left out and named, because a total that quietly omits part of itself is printed with the same
 * confidence as one that does not.
 *
 * [CurrencyTotals.balance] is converted rather than `income − expense`, because balance also
 * carries transfers: a bond purchase leaves the account without ever being an expense, and a
 * summary that dropped those would not match the ledger.
 *
 * [positions] are folded — and filtered by [rules] — by the same [nominalTotal] the dashboard
 * uses, so the list and the dashboard can never disagree on the bond part of the total. Passing
 * the positions rather than a pre-summed number is what lets an unquotable denomination be named
 * here as it is there.
 */
fun convertTotals(
    totals: List<CurrencyTotals>,
    rates: ExchangeRates,
    base: String,
    positions: List<BondPosition> = emptyList(),
    rules: ExclusionRules = ExclusionRules()
): ConvertedTotals {
    val unquoted = linkedSetOf<String?>()
    val nominal = nominalTotal(positions, rules, rates, base)

    // A base the cache cannot quote yields no figures rather than zeroes. Zero reads as "you
    // spent nothing", which must never be reachable by accident.
    if (rates.convert(1.0, base, base) == null) {
        totals.forEach { unquoted += it.currencyCode }
        unquoted += nominal.unquoted
        return ConvertedTotals(
            base, null, null, null, unquoted.toList(), nominal.amount, nominal.excluded
        )
    }

    var income = 0.0
    var expense = 0.0
    var balance = 0.0
    totals.forEach { group ->
        // Deliberately not `convert(...)?.also { if (it == null) ... }`: the safe call
        // short-circuits on null, so the branch recording the omission would never run.
        val convertedBalance = rates.convert(group.balance, group.currencyCode, base)
        if (convertedBalance == null) {
            unquoted += group.currencyCode
            return@forEach
        }
        balance += convertedBalance
        income += rates.convert(group.income, group.currencyCode, base) ?: 0.0
        expense += rates.convert(group.expense, group.currencyCode, base) ?: 0.0
    }
    unquoted += nominal.unquoted

    // Add the bond nominal so the total matches the dashboard (cash + bonds at nominal).
    balance += nominal.amount

    return ConvertedTotals(
        base, income, expense, balance, unquoted.toList(), nominal.amount, nominal.excluded
    )
}