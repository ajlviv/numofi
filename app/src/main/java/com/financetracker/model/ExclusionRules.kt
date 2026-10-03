package com.financetracker.model

import java.util.Locale

/**
 * The user's own rules for what a total may not count, folded once.
 *
 * Each rule is a plain substring of a transaction's title, matched case-insensitively. Nothing
 * richer: a title is the one field a user can be certain what the bank wrote, and substring
 * matching is the one thing they can predict without being told the rules' grammar.
 *
 * **Folding happens here, in the constructor, and never again.** This is the whole performance
 * story. A rule set is built when the user changes a setting, which is rare, and is then asked
 * about every row on every emission of the transaction flow. Folding per row would re-derive
 * every pattern for every row, so the cost would be O(rows × rules) string allocations on each
 * emission rather than O(rules) per settings change. A title is folded at most once per `select`
 * instead, and only when there is at least one rule to fold it for.
 *
 * The fold is against [Locale.ROOT], not the device locale, for the reason `SearchText` gives
 * about SQLite's `lower()`: this app's text is largely Cyrillic and Latin, and the default
 * locale's casing rules are not English ones. In Turkish, `I` lowercases to a dotless `ı`, so a
 * Latin-script rule would stop matching a Latin-script title and the same stored rules would
 * exclude different rows on two devices — with neither device wrong.
 *
 * Not a data class, because the property that identifies a rule set is the *folded* one and a
 * generated `equals` would compare the display form instead. Two rule sets holding "Transfer"
 * and "  transfer  " exclude exactly the same rows and are the same rule set; comparing the
 * forms the user typed would call them different.
 *
 * That matters beyond tidiness. DataStore re-emits its whole value on any write, including
 * writes to unrelated keys, so a flow combining this with the transaction list would otherwise
 * re-run every rule against every row each time some other setting changed. Equality is what
 * lets `distinctUntilChanged` — or any `StateFlow` upstream of it — skip the work.
 */
class ExclusionRules(patterns: List<String> = emptyList()) {

    /** What the settings screen lists: what was typed, not what it folded to. */
    val patterns: List<String> = patterns
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase(Locale.ROOT) }

    /** Whether one title is held back, folded against [Locale.ROOT] for the same reason. */
    private fun excludes(title: String): Boolean {
        if (needles.isEmpty()) return false
        val folded = title.lowercase(Locale.ROOT)
        return needles.any { folded.contains(it) }
    }

    // Derived from [patterns] and not from the constructor argument: the trimmed, non-blank,
    // deduplicated form. Taking the argument directly would put a blank needle back in, and a
    // blank needle is a substring of every title — one stored empty rule would empty every
    // total in the app. Declared after it so initialisation order is not a trap.
    private val needles: List<String> = this.patterns.map { it.lowercase(Locale.ROOT) }

    val isEmpty: Boolean get() = needles.isEmpty()

    override fun equals(other: Any?): Boolean =
        this === other || (other is ExclusionRules && needles == other.needles)

    override fun hashCode(): Int = needles.hashCode()

    /**
     * The rows a total may count, and how many were held back.
     *
     * Returns the input list itself when there is nothing to exclude, rather than a copy, so
     * the common case — a user with no rules, which is most of them — allocates nothing per
     * emission of the transaction flow.
     */
    fun select(transactions: List<Transaction>): CountedTransactions {
        if (needles.isEmpty()) return CountedTransactions(transactions, 0)
        val counted = ArrayList<Transaction>(transactions.size)
        var excluded = 0
        for (row in transactions) {
            if (excludes(row.title)) excluded++ else counted.add(row)
        }
        return CountedTransactions(counted, excluded)
    }

    /**
     * The holdings a total may count, and how many were held back.
     *
     * A bond purchase is two records: a cash row for what left the account, and the position
     * itself. [select] takes the first away on a rule matching its title, and without this the
     * second would stay in the total — so the figure would report the full nominal of a holding
     * whose purchase has already been subtracted out of the cash side of the same sum.
     *
     * Matched on [Bond.name] because that is what the cash row is titled with: the purchase
     * writes a row called the bond's name, so a rule that catches one catches the other, and a
     * user has one rule rather than two that must be kept in step.
     *
     * Returns the input list itself when there is nothing to exclude, as [select] does.
     */
    fun selectHoldings(positions: List<BondPosition>): CountedHoldings {
        if (needles.isEmpty()) return CountedHoldings(positions, 0)
        val counted = ArrayList<BondPosition>(positions.size)
        var excluded = 0
        for (position in positions) {
            if (excludes(position.bond.name)) excluded++ else counted.add(position)
        }
        return CountedHoldings(counted, excluded)
    }
}

/** The result of applying an [ExclusionRules] to a set of holdings. */
data class CountedHoldings(
    /** Only these positions may be added to a total. */
    val counted: List<BondPosition>,
    /** How many [counted] is short by, reported beside the total. See [CountedTransactions.excluded]. */
    val excluded: Int
)

/** The result of applying an [ExclusionRules] to a set of rows. */
data class CountedTransactions(
    /** Only these rows may be summed. Complete otherwise — nothing is rewritten. */
    val counted: List<Transaction>,
    /**
     * How many rows [counted] is short by, to be shown beside the total.
     *
     * Carried rather than recomputed at the display site, because the two have to be the same
     * number. A total that quietly omits rows is shown with the same confidence as one that
     * could not omit them and the user has no way to tell — which is the reason
     * `NetWorth.unquoted` exists. A rule added an hour ago and forgotten about does exactly
     * what an unquotable currency does to a headline, and gets the same answer: name it.
     */
    val excluded: Int
) {
    companion object {
        val ALL = CountedTransactions(emptyList(), 0)
    }
}
