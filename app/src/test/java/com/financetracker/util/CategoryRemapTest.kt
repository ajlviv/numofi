package com.financetracker.util

import com.financetracker.data.TitleCategoryCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule a bulk remap is built on: a transaction title, folded, against a typed name.
 * Pure, so it needs no database and no Android runtime.
 */
class CategoryRemapTest {

    private fun count(title: String, category: String, n: Int = 1) =
        TitleCategoryCount(title, category, n)

    @Test
    fun `folding trims and lowercases Cyrillic`() {
        assertEquals("сільпо", CategoryRemap.fold("  Сільпо "))
        assertEquals("atb", CategoryRemap.fold("ATB"))
    }

    @Test
    fun `matching is exact, not a substring`() {
        assertTrue(CategoryRemap.matches("Сільпо", "сільпо"))
        assertFalse(CategoryRemap.matches("Сільпо №12", "Сільпо"))
        assertFalse(CategoryRemap.matches("Сільпо", "Сільпо №12"))
    }

    @Test
    fun `a blank name matches nothing to plan`() {
        val counts = listOf(count("Сільпо", "Groceries"))
        assertEquals(0, CategoryRemap.plannedCount(counts, "", "Fuel"))
        assertEquals(0, CategoryRemap.plannedCount(counts, "Сільпо", ""))
    }

    @Test
    fun `only rows not already in the target are planned`() {
        val counts = listOf(
            count("Сільпо", "mcc_5411", 3),
            count("Сільпо", "Groceries", 2)
        )
        // The three in mcc_5411 move; the two already in Groceries do not.
        assertEquals(3, CategoryRemap.plannedCount(counts, "Сільпо", "Groceries"))
    }

    @Test
    fun `the target comparison is exact, as the stored key is`() {
        val counts = listOf(count("Сільпо", "groceries", 2))
        // "Groceries" is a different stored key from "groceries", so both move.
        assertEquals(2, CategoryRemap.plannedCount(counts, "Сільпо", "Groceries"))
    }

    @Test
    fun `matching folds case across variants and sums their counts`() {
        val counts = listOf(
            count("Сільпо", "mcc_5411", 1),
            count("СІЛЬПО", "Other", 2)
        )
        assertEquals(3, CategoryRemap.plannedCount(counts, "сільпо", "Groceries"))
    }

    @Test
    fun `suggestions merge case variants and keep the first spelling`() {
        val counts = listOf(
            count("Сільпо", "Groceries", 2),
            count("СІЛЬПО", "Other", 1),
            count("АТБ", "Groceries", 1)
        )
        assertEquals(listOf("Сільпо", "АТБ"), CategoryRemap.suggestions(counts))
    }

    @Test
    fun `suggestions put the busiest title first`() {
        val counts = listOf(
            count("АТБ", "Groceries", 1),
            count("Сільпо", "Groceries", 5)
        )
        assertEquals(listOf("Сільпо", "АТБ"), CategoryRemap.suggestions(counts))
    }

    @Test
    fun `a blank stored title is not a suggestion`() {
        assertEquals(emptyList<String>(), CategoryRemap.suggestions(listOf(count("   ", "Other"))))
    }
}
