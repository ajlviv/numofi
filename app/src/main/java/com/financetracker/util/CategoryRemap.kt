package com.financetracker.util

import com.financetracker.data.TitleCategoryCount
import java.util.Locale

/**
 * The match a bulk category remap is built on: a transaction title, folded, against the name
 * the user typed.
 *
 * [CategorySuggestion] folds titles for the same reason — SQLite's `lower()` folds ASCII only
 * and these titles are largely Cyrillic — and this holds the whole decision in one pure place,
 * so the count shown before the write and the rows the write touches are the same rule and
 * cannot disagree.
 *
 * Pure and JVM-testable: no Android, no database, no [com.financetracker.model.TransactionEntity].
 */
object CategoryRemap {

    fun fold(title: String): String = title.trim().lowercase(Locale.ROOT)

    fun matches(title: String, typedTitle: String): Boolean =
        fold(title) == fold(typedTitle)

    /**
     * The distinct titles the user has, folded and merged across case variants, in the spelling
     * of the first one seen and ordered by how many rows each covers, busiest first.
     */
    fun suggestions(counts: List<TitleCategoryCount>): List<String> {
        val byFold = LinkedHashMap<String, Pair<String, Int>>()
        for (c in counts) {
            val key = fold(c.title)
            if (key.isEmpty()) continue
            val existing = byFold[key]
            byFold[key] = if (existing == null) c.title.trim() to c.count
            else existing.first to (existing.second + c.count)
        }
        return byFold.values
            .sortedWith(
                compareByDescending<Pair<String, Int>> { it.second }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.first }
            )
            .map { it.first }
    }

    /**
     * How many rows a remap of [typedTitle] to [target] would change: rows whose folded title
     * matches and whose category is not already exactly [target]. A blank name or target
     * changes nothing.
     *
     * The target comparison is exact, matching the stored key the category filter binds: a
     * differently-cased spelling is a different value in the column, not the same one.
     */
    fun plannedCount(counts: List<TitleCategoryCount>, typedTitle: String, target: String): Int {
        if (typedTitle.isBlank() || target.isBlank()) return 0
        return counts
            .filter { matches(it.title, typedTitle) && it.category != target }
            .sumOf { it.count }
    }
}
