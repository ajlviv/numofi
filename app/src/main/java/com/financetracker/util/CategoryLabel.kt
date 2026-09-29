package com.financetracker.util

import java.util.Locale

/**
 * Turns stored category keys into labels a person can read.
 *
 * Categories are stored as stable keys rather than prose, so that grouping and filtering
 * do not depend on wording: `mcc_5499` is what the bank reported, and `imported` marks a
 * row that arrived from a statement. The label is applied only at display time, which
 * means the mapping can improve later without a data migration.
 *
 * MCC values follow ISO/IEC 18245, the merchant category standard banks use.
 */
object CategoryLabel {

    private const val MCC_PREFIX = "mcc_"

    /** Exact codes, for the categories that are common enough to name precisely. */
    private val EXACT: Map<Int, String> = mapOf(
        4812 to "Phone & data",
        4829 to "Phone & data",
        4899 to "Digital services",
        5411 to "Groceries",
        5412 to "Supermarket",
        5422 to "Supermarket",
        5441 to "Supermarket",
        5499 to "Groceries",
        5533 to "Auto parts",
        5541 to "Fuel",
        5542 to "Fuel",
        5651 to "Clothing",
        5712 to "Home & furniture",
        5732 to "Electronics",
        5734 to "Electronics",
        5812 to "Restaurant",
        5813 to "Bar & nightlife",
        5814 to "Fast food",
        5912 to "Pharmacy",
        5921 to "Cosmetics",
        5945 to "Toys & games",
        5977 to "Health & beauty",
        5992 to "General retail",
        6011 to "ATM & cash",
        7297 to "Health services",
        7298 to "Other services",
        7995 to "Gambling",
        7996 to "Professional services",
        7997 to "Membership & leisure",
        8062 to "Hospital",
        8931 to "Accounting & legal",
        9311 to "Government",
        9399 to "Public services"
    )

    /** Ranges, checked in order and only when no exact code matches. */
    private val RANGES: List<Pair<IntRange, String>> = listOf(
        5411..5419 to "Groceries",
        5422..5499 to "Groceries",
        5500..5599 to "Fuel & auto",
        5600..5699 to "Clothing",
        5700..5799 to "Home & electronics",
        5800..5899 to "Dining & nightlife",
        5900..5999 to "Health & general retail",
        6000..6999 to "Travel & transport",
        7200..7299 to "Services",
        7300..7999 to "Leisure & professional",
        8000..8999 to "Professional services"
    )

    /**
     * @param category stored key, e.g. `mcc_5499` or `imported`
     * @return a label suitable for display, never null and never a raw `mcc_` key
     */
    fun label(category: String?): String {
        val key = category?.trim().orEmpty()
        if (key.isEmpty()) return "Uncategorised"

        if (key.startsWith(MCC_PREFIX, ignoreCase = true)) {
            // removePrefix is case sensitive, so normalise before stripping.
            val code = key.lowercase(Locale.ROOT).removePrefix(MCC_PREFIX).toIntOrNull()
                ?: return "Other"
            return forMcc(code)
        }

        return when (key.lowercase(Locale.ROOT)) {
            "imported" -> "Imported"
            "other" -> "Other"
            // Named here rather than left to prettify, which would render the key as
            // "Investments" only by accident of capitalisation. This is the category every
            // bond cash movement is filed under, so it is the label users see most often on
            // the transactions a bond purchase produces.
            "investments" -> "Investments"
            else -> prettify(key)
        }
    }

    fun forMcc(code: Int): String {
        EXACT[code]?.let { return it }
        RANGES.firstOrNull { (range, _) -> code in range }?.let { return it.second }
        return "Other"
    }

    private fun prettify(key: String): String =
        key.split('_', '-', ' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
            .ifEmpty { "Uncategorised" }
}
