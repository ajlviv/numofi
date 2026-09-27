package com.financetracker.model

/**
 * Builds the string a transaction search matches against.
 *
 * The folding happens here, in Kotlin, because SQLite's `lower()` and its
 * case-insensitive `LIKE` fold ASCII only. Descriptions in this app are largely Cyrillic,
 * so folding in the database would store `МОБІЛЬНИЙ` unchanged and a lowercase query
 * would silently miss it. Both sides are lowercased in Kotlin instead, which leaves
 * `LIKE` a plain substring test with no case handling of its own.
 */
object SearchText {

    fun of(
        title: String?,
        note: String?,
        category: String?,
        bankCode: String?,
        cardLabel: String?
    ): String = listOfNotNull(
        title?.takeIf { it.isNotBlank() },
        note?.takeIf { it.isNotBlank() },
        category?.takeIf { it.isNotBlank() },
        cardLabel?.takeIf { it.isNotBlank() },
        bankCode?.let(BankCode::label)
    ).joinToString(" ").lowercase()
}
