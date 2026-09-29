package com.financetracker.util

import androidx.annotation.StringRes
import com.financetracker.R
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

    /**
     * The string resource for a stored key, or 0 when the label is not this object's to
     * translate.
     *
     * 0 means [label] is echoing the user's own words back at them — the `prettify` path for
     * free-typed categories — which no translation table can improve on; the screen then
     * renders [label] as it is. Everything else resolves through [RESOURCE_BY_LABEL], keyed
     * by the *English* label rather than the stored key so that the exact and range tables
     * above stay the single source of category grouping. `CategoryResourceParityTest` pins
     * every non-zero result against `res/values/strings.xml`, so the two cannot drift.
     */
    @StringRes
    fun resource(category: String?): Int = RESOURCE_BY_LABEL[label(category)] ?: 0

    private val RESOURCE_BY_LABEL: Map<String, Int> = mapOf(
        "Phone & data" to R.string.cat_phone_data,
        "Digital services" to R.string.cat_digital_services,
        "Groceries" to R.string.cat_groceries,
        "Supermarket" to R.string.cat_supermarket,
        "Auto parts" to R.string.cat_auto_parts,
        "Fuel" to R.string.cat_fuel,
        "Clothing" to R.string.cat_clothing,
        "Home & furniture" to R.string.cat_home_furniture,
        "Electronics" to R.string.cat_electronics,
        "Restaurant" to R.string.cat_restaurant,
        "Bar & nightlife" to R.string.cat_bar_nightlife,
        "Fast food" to R.string.cat_fast_food,
        "Pharmacy" to R.string.cat_pharmacy,
        "Cosmetics" to R.string.cat_cosmetics,
        "Toys & games" to R.string.cat_toys_games,
        "Health & beauty" to R.string.cat_health_beauty,
        "General retail" to R.string.cat_general_retail,
        "ATM & cash" to R.string.cat_atm_cash,
        "Health services" to R.string.cat_health_services,
        "Other services" to R.string.cat_other_services,
        "Gambling" to R.string.cat_gambling,
        "Professional services" to R.string.cat_professional_services,
        "Membership & leisure" to R.string.cat_membership_leisure,
        "Hospital" to R.string.cat_hospital,
        "Accounting & legal" to R.string.cat_accounting_legal,
        "Government" to R.string.cat_government,
        "Public services" to R.string.cat_public_services,
        "Fuel & auto" to R.string.cat_fuel_auto,
        "Home & electronics" to R.string.cat_home_electronics,
        "Dining & nightlife" to R.string.cat_dining_nightlife,
        "Health & general retail" to R.string.cat_health_retail,
        "Travel & transport" to R.string.cat_travel_transport,
        "Services" to R.string.cat_services,
        "Leisure & professional" to R.string.cat_leisure_professional,
        "Imported" to R.string.cat_imported,
        "Other" to R.string.cat_other,
        "Investments" to R.string.cat_investments,
        "Uncategorised" to R.string.cat_uncategorised
    )

    private fun prettify(key: String): String =
        key.split('_', '-', ' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
            .ifEmpty { "Uncategorised" }
}
