package com.financetracker.model

import java.util.Locale

/**
 * The user's own list of transaction categories, shown in the settings page and offered as a
 * dropdown everywhere a category is entered.
 *
 * A single shared list for income and expense: splitting it would double the settings surface
 * for a distinction the ledger does not make (a row already carries its type), and a category
 * typed for one side is still a reasonable suggestion for the other.
 *
 * Stored labels are English display labels (e.g. "Groceries") rather than machine keys, so
 * [com.financetracker.util.CategoryLabel] keeps resolving them to `cat_*` resources. A value
 * the user typed that matches no known label is still stored verbatim on the row — the
 * dropdown allows a one-off custom value without adding it to this list — and renders through
 * the `prettify` path as free-typed categories always have.
 *
 * Name comparison is in Kotlin with full-Unicode `lowercase()`, because SQLite's `lower()`
 * folds ASCII only and these names may be Ukrainian. Same reason bank-name uniqueness lives
 * in Kotlin.
 */
class Categories(names: List<String> = emptyList()) {

    /** What the settings screen lists and the dropdowns offer: what was typed, in order. */
    val names: List<String> = names
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase(Locale.ROOT) }

    val isEmpty: Boolean get() = names.isEmpty()

    override fun equals(other: Any?): Boolean =
        this === other || (other is Categories &&
            names.map { it.lowercase(Locale.ROOT) } == other.names.map { it.lowercase(Locale.ROOT) })

    override fun hashCode(): Int = names.map { it.lowercase(Locale.ROOT) }.hashCode()
}
