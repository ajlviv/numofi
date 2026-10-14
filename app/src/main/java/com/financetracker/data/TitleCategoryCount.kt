package com.financetracker.data

/**
 * One stored title, the category a row carries, and how many rows share that pair.
 *
 * A projection of the bulk-remap query. It arrives unfolded because SQLite's `lower()` folds
 * ASCII only and these titles are largely Cyrillic; folding and merging happen in Kotlin, in
 * [com.financetracker.util.CategoryRemap], the same way import suggestions fold titles.
 */
data class TitleCategoryCount(
    val title: String,
    val category: String,
    val count: Int
)
