package com.financetracker.model

/**
 * Turns a transaction's parts into the string a search matches against.
 *
 * The folding happens here, in Kotlin, because SQLite's `lower()` and its
 * case-insensitive `LIKE` fold ASCII only. Descriptions in this app are largely Cyrillic,
 * so folding in the database would store `МОБІЛЬНИЙ` unchanged and a lowercase query
 * would silently miss it. Both sides are lowercased in Kotlin instead, which leaves
 * `LIKE` a plain substring test with no case handling of its own.
 *
 * The result is written once, when the row is written, and never rebuilt. That is why the
 * bank's *name* goes in rather than its code: a generated code such as `bank-010203` is
 * not something anyone can type, and renaming a bank deliberately does not reach back
 * into rows already stored.
 */
object SearchText {

    fun of(
        title: String?,
        note: String?,
        category: String?,
        bank: BankRef?,
        cardLabel: String?
    ): String = listOfNotNull(
        title?.takeIf { it.isNotBlank() },
        note?.takeIf { it.isNotBlank() },
        category?.takeIf { it.isNotBlank() },
        cardLabel?.takeIf { it.isNotBlank() },
        bank?.label?.takeIf { it.isNotBlank() }
    ).joinToString(" ").lowercase()
}
