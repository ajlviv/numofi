package com.financetracker.util

import com.financetracker.data.TitleCategory
import com.financetracker.data.settings.DEFAULT_CATEGORIES
import java.util.Locale

/**
 * Suggests a category for a row that arrived without a human-readable one.
 *
 * Imported statement lines carry `imported` and bank-synced rows without an MCC carry `other`;
 * synced rows with an MCC carry `mcc_<code>`. This maps those machine keys onto the user's
 * own [categories] list so the import preview can offer something better than "Imported":
 *
 * - an `mcc_<code>` key resolves through [CategoryLabel.forMcc] and is kept when the label
 *   is one of the user's categories (matched case-insensitively, since names are Ukrainian
 *   and SQLite's `lower()` folds ASCII only);
 * - anything else keeps the history value for an identical title (folded with
 *   [Locale.ROOT]) when that value is still usable, i.e. it is in the list or is a custom
 *   one-off the user typed before;
 * - otherwise the first list entry that the label matches, else "Other" when present, else
 *   the list head, else the label itself.
 *
 * Pure and JVM-testable: no Android, no database. History is a caller-supplied map of
 * folded title to stored category, so the database query stays at the call site.
 */
object CategorySuggestion {

    fun suggest(
        storedCategory: String?,
        title: String,
        categories: List<String>,
        history: Map<String, String> = emptyMap()
    ): String {
        val options = categories.ifEmpty { DEFAULT_CATEGORIES }
        val key = storedCategory?.trim().orEmpty()
        val mcc = mccCode(key)
        if (mcc != null) {
            val label = CategoryLabel.forMcc(mcc)
            findOption(options, label)?.let { return it }
            return fallback(options, label)
        }
        history[title.trim().lowercase(Locale.ROOT)]?.takeIf { it.isNotBlank() }?.let { past ->
            val pastTrimmed = past.trim()
            findOption(options, pastTrimmed)?.let { return it }
            // A custom one-off the user typed before is still a valid suggestion even though
            // it was never added to the settings list.
            return pastTrimmed
        }
        val label = CategoryLabel.label(key.ifEmpty { null })
        findOption(options, label)?.let { return it }
        return fallback(options, label)
    }

    private fun mccCode(key: String): Int? {
        if (!key.startsWith("mcc_", ignoreCase = true)) return null
        return key.lowercase(Locale.ROOT).removePrefix("mcc_").toIntOrNull()
    }

    /**
     * Folds the raw title/category pairs into the history map [suggest] reads.
     *
     * One implementation for both call sites — the import service and the preview — because
     * the two must agree: a preview offering "Other" while the service stores "Groceries"
     * for the same row is a suggestion the user never chose. Newest wins (`pairs` arrives
     * ordered `timestamp DESC`), and the fold is [Locale.ROOT] for the reason
     * [ExclusionRules] gives: SQLite's `lower()` folds ASCII only and these titles are
     * largely Cyrillic.
     */
    fun foldHistory(pairs: List<TitleCategory>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (pair in pairs) {
            val title = pair.title.trim().lowercase(Locale.ROOT)
            val category = pair.category.trim()
            if (title.isNotEmpty() && category.isNotEmpty() && title !in out) {
                out[title] = category
            }
        }
        return out
    }

    private fun findOption(options: List<String>, label: String): String? =
        options.firstOrNull { it.equals(label, ignoreCase = true) }

    private fun fallback(options: List<String>, label: String): String {
        findOption(options, "Other")?.let { return it }
        if (label.isNotBlank()) return label
        return options.firstOrNull() ?: "Other"
    }
}
