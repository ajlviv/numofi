package com.financetracker.model


/**
 * Spending and income grouped by category, expressed in the base currency.
 *
 * Two facts make this a breakdown of the *converted* flows and not a per-currency one: the
 * dashboard headlines a single figure in [base], and a category that spans two currencies is
 * one category to the eye but two to a raw count. Sums are converted per row through [rates],
 * the same gate [netWorth] uses, so the numbers that sit under a slice always add up to the
 * income and expense figures that sit above it.
 *
 * A currency the cache cannot quote is dropped from every total — never guessed — and named by
 * [unquoted] so the deficit stays visible. When the cache cannot quote [base] itself there is
 * no figure at all, which [CategoryTotals.unquotable] models rather than a breakdown of zeros:
 * zero is the reading "you earned and spent nothing", and it must never be available by accident.
 *
 * Transfers are excluded on both sides. Money that moved between the user's own accounts is not
 * income and is not spending, and counting it as either would double-count a bond purchase and
 * pull the spending figure away from what left the account.
 */
data class CategoryTotals(
    /** The base currency every slice is converted into. */
    val base: String,
    /** Income grouped by category, largest first and already rounded to whole percentages. */
    val income: List<CategorySlice>,
    /** Expenses grouped by category, largest first and already rounded to whole percentages. */
    val expense: List<CategorySlice>,
    val totalIncome: Double,
    val totalExpense: Double,
    /**
     * Currencies this breakdown could not include, each dropped and named.
     *
     * A null entry is a group of rows whose `currencyCode` is missing — filed on their own by
     * [totalsByCurrency] rather than joined to a real currency — and treated exactly like a
     * currency the cache misses: excluded from every figure and named where the deficit shows.
     */
    val unquoted: List<String?>,
    /**
     * False when [base] itself cannot be quoted, so the card knows to hide itself and leave
     * the explanation to the net-worth card.
     */
    val isBaseQuotable: Boolean
) {
    companion object {
        /** A breakdown that cannot be computed because the base currency is unquotable. */
        fun unquotable(base: String) = CategoryTotals(
            base = base,
            income = emptyList(),
            expense = emptyList(),
            totalIncome = 0.0,
            totalExpense = 0.0,
            unquoted = listOf(base),
            isBaseQuotable = false
        )
    }
}

/**
 * One category's contribution to one side of the breakdown.
 *
 * [label] is the canonical English label [CategoryLabel] produces — resolved to the user's
 * language at the call site, the same way a transaction row resolves its category. [amount] is
 * the converted value in [CategoryTotals.base], and [percent] is its exact share of the side
 * total (sums to 100 across a side up to floating point error).
 */
data class CategorySlice(
    val label: String,
    val amount: Double,
    /** Exact share of the side total, 0..100. */
    val percent: Double
)

/**
 * Prepares a side's slices for the donut.
 *
 * Only the count is bounded: when a side has more than [maxSlices] categories the largest
 * [maxSlices] - 1 are kept and the rest fold into one "Other" (merging with a real one when it
 * is among the kept). A ring is read by shape, and past a handful of wedges the eye can no
 * longer match a colour to a legend row; the same cap keeps the legend from growing to a
 * screenful of percentages. The cap never fires when it would leave only "Other".
 *
 * There is deliberately no percentage threshold. A threshold reads well until one bucket — the
 * unclassified "Other"/"Imported" most imports carry, or a run of large transfers — dominates
 * the side, at which point *every* real category falls below it and the ring collapses to a
 * single slice. That made widening the window hide categories: the all-time breakdown showed
 * less than a month did. Capping by count instead means a wider window can only ever add rows
 * up to the cap, never remove them. A near-zero share is honest and visible in the legend.
 *
 * Percentages are rounded to whole numbers that still sum to exactly 100 via the largest
 * remainder method — the biggest slice absorbs the rounding remainder, which also keeps the
 * categories where the eye expects them.
 *
 * The shape is stable across recompositions that do not change the data, so the caller is free
 * to [remember] it without extra keys.
 */
fun displaySlices(
    slices: List<CategorySlice>,
    otherLabel: String,
    maxSlices: Int = 7
): List<CategorySlice> {
    if (slices.isEmpty()) return emptyList()
    val total = slices.sumOf { it.amount }
    if (total <= 0.0) return emptyList()

    val exact = slices.map { it.copy(percent = it.amount / total * 100.0) }
    return normalizeToHundred(capToOther(exact, maxSlices, otherLabel))
}

/**
 * Keeps the largest [maxSlices] slices, folding the rest into [otherLabel].
 *
 * Sorted largest first before the cut so "the largest are kept" is a property of the amounts
 * rather than of the order the caller happened to pass. When [otherLabel] is already among the
 * kept the overflow is merged into it rather than producing a second row with the same name.
 */
private fun capToOther(
    slices: List<CategorySlice>,
    maxSlices: Int,
    otherLabel: String
): List<CategorySlice> {
    // The guard keeps the cap from ever producing a lone "Other": with two rows or fewer to
    // spare there is nothing worth folding, so the set is left whole.
    if (slices.size <= maxSlices || maxSlices < 2) return slices

    val sorted = slices.sortedByDescending { it.amount }
    val top = sorted.take(maxSlices - 1)
    val rest = sorted.drop(maxSlices - 1)
    val restAmount = rest.sumOf { it.amount }
    // The overflow's exact share, recomputed against the whole so normalizeToHundred rounds it
    // on the same basis as its neighbours.
    val grandTotal = slices.sumOf { it.amount }
    val restPercent = if (grandTotal > 0.0) restAmount / grandTotal * 100.0 else 0.0

    if (top.any { it.label == otherLabel }) {
        return top.map { slice ->
            if (slice.label == otherLabel) {
                slice.copy(
                    amount = slice.amount + restAmount,
                    percent = slice.percent + restPercent
                )
            } else slice
        }
    }
    return top + CategorySlice(otherLabel, restAmount, restPercent)
}

/**
 * Largest-remainder rounding to whole percentages that sum to exactly 100.
 *
 * Each slice floors toward zero (percentages here are non-negative), the leftover units are
 * handed out one at a time to the largest slices first, and the list is returned sorted largest
 * first so the donut and the legend agree on colour assignment.
 */
private fun normalizeToHundred(slices: List<CategorySlice>): List<CategorySlice> {
    if (slices.isEmpty()) return emptyList()
    val sorted = slices.sortedByDescending { it.percent }
    val floored = sorted.map { it.percent.toInt() }
    var remainder = 100 - floored.sum()
    return sorted.mapIndexed { index, slice ->
        val rounded = if (index < remainder) floored[index] + 1 else floored[index]
        slice.copy(percent = rounded.toDouble())
    }
}

/**
 * Income and spending by category for a window of transactions, converted into [base].
 *
 * [labelOf] turns each row's stored category key into the canonical English label that is then
 * resolved to the user's language at display time; passing it in keeps this a pure function and
 * keeps the category tables out of the model layer. The result carries [CategoryTotals.unquoted]
 * so the caller can name the currencies it dropped, mirroring how [netWorth] reports omissions.
 */
fun categoryTotals(
    transactions: List<Transaction>,
    rates: ExchangeRates,
    base: String,
    labelOf: (String) -> String
): CategoryTotals {
    // A base the cache cannot quote is not a total of zero: it is no total at all.
    if (rates.convert(1.0, base, base) == null) return CategoryTotals.unquotable(base)

    val unquoted = linkedSetOf<String?>()
    val incomeByCategory = linkedMapOf<String, Double>()
    val expenseByCategory = linkedMapOf<String, Double>()

    for (row in transactions) {
        // Transfers stay out of both columns: they are money the user moved, not money earned
        // or spent, and the balance is the place their direction already shows.
        if (row.isTransfer()) continue
        val converted = rates.convert(row.amount, row.currencyCode, base)
        if (converted == null) {
            unquoted += row.currencyCode
            continue
        }
        val bucket = when {
            row.isIncome() -> incomeByCategory
            row.isExpense() -> expenseByCategory
            else -> null
        } ?: continue
        val label = labelOf(row.category)
        bucket[label] = bucket.getOrDefault(label, 0.0) + converted
    }

    val income = toSlices(incomeByCategory)
    val expense = toSlices(expenseByCategory)
    return CategoryTotals(
        base = base,
        income = income,
        expense = expense,
        totalIncome = income.sumOf { it.amount },
        totalExpense = expense.sumOf { it.amount },
        unquoted = unquoted.toList(),
        isBaseQuotable = true
    )
}

private fun toSlices(grouped: Map<String, Double>): List<CategorySlice> {
    if (grouped.isEmpty()) return emptyList()
    val total = grouped.values.sum()
    return grouped.entries
        .map { (label, amount) -> CategorySlice(label, amount, amount / total * 100.0) }
        .sortedByDescending { it.amount }
}
