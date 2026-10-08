package com.financetracker.data.settings

import com.financetracker.model.Categories
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser

/**
 * How the user's category list is written into the single string DataStore holds.
 *
 * **JSON, and not a joined string**, for the same reason as [ExclusionRulesEncoding]: a
 * category is arbitrary user text and may contain a comma (`переказ, на карту`). Same Gson
 * dependency as [com.financetracker.data.backup.BackupSnapshotCodec], so nothing new.
 *
 * Absent from the backup snapshot like exclusion rules and the rate cache: a restored device
 * falls back to [DEFAULT_CATEGORIES]. Stored rows keep their own `category` verbatim, so a
 * missing list never rewrites history.
 */
private val GSON = Gson()

/**
 * The default popular list a fresh install starts with.
 *
 * Every entry is an English label [com.financetracker.util.CategoryLabel] already knows, so
 * each one resolves to a `cat_*` resource in both languages without new copy. Kept to the
 * popular buckets rather than the full MCC table: the dropdown is a short list the user
 * extends, not the bank's taxonomy.
 */
val DEFAULT_CATEGORIES: List<String> = listOf(
    "Groceries",
    "Restaurant",
    "Fast food",
    "Pharmacy",
    "Fuel",
    "Clothing",
    "Electronics",
    "Home & furniture",
    "Travel & transport",
    "Services",
    "Health & beauty",
    "Leisure & professional",
    "Digital services",
    "ATM & cash",
    "Investments",
    "Other"
)

/**
 * Reads the stored value, falling back to [DEFAULT_CATEGORIES].
 *
 * Forgiving like the exclusion-rules read: this runs inside a flow the settings screen
 * observes, and a throw takes that screen down. An unreadable value reads as the defaults —
 * visible and fixable — and one unreadable entry costs that entry, not the rest.
 */
internal fun readCategories(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return DEFAULT_CATEGORIES
    val array: JsonArray = try {
        JsonParser.parseString(raw).takeIf { it.isJsonArray }?.asJsonArray
            ?: return DEFAULT_CATEGORIES
    } catch (e: RuntimeException) {
        return DEFAULT_CATEGORIES
    }
    val names = array.mapNotNull { element ->
        element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }
    if (names.isEmpty()) return DEFAULT_CATEGORIES
    return Categories(names).names.ifEmpty { DEFAULT_CATEGORIES }
}

/** Writes the list as one JSON array, in the order given. */
internal fun encodeCategories(names: List<String>): String = GSON.toJson(names)
