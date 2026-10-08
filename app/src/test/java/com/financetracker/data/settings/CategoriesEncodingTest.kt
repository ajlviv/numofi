package com.financetracker.data.settings

import com.financetracker.model.Categories
import com.financetracker.util.CategoryLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The category list encoding, on its own.
 *
 * What happens to a value this build cannot read is tested here rather than against real
 * storage: reaching a hand-planted value into a real DataStore file means a second instance
 * on that file, and that is an error rather than a test. Same split as
 * `ExclusionRulesEncodingTest`.
 */
class CategoriesEncodingTest {

    @Test
    fun `a blank value reads as the defaults`() {
        assertEquals(DEFAULT_CATEGORIES, readCategories(null))
        assertEquals(DEFAULT_CATEGORIES, readCategories(""))
        assertEquals(DEFAULT_CATEGORIES, readCategories("   "))
    }

    @Test
    fun `a non-array value reads as the defaults`() {
        assertEquals(DEFAULT_CATEGORIES, readCategories("{\"a\":1}"))
        assertEquals(DEFAULT_CATEGORIES, readCategories("not json"))
    }

    @Test
    fun `a stored list round-trips in order`() {
        val names = listOf("Groceries", "Кава", "переказ, на карту")
        assertEquals(names, readCategories(encodeCategories(names)))
    }

    @Test
    fun `a category containing a comma survives, which is why the encoding is JSON`() {
        val name = "переказ, на карту"
        assertEquals(listOf(name), readCategories(encodeCategories(listOf(name))))
    }

    @Test
    fun `blank entries are dropped on read`() {
        assertEquals(
            listOf("Groceries"),
            readCategories(encodeCategories(listOf("Groceries", "   ")))
        )
    }

    @Test
    fun `duplicates fold case-insensitively on read`() {
        assertEquals(
            listOf("Groceries"),
            readCategories(encodeCategories(listOf("Groceries", "groceries")))
        )
    }

    @Test
    fun `the defaults are known CategoryLabel buckets`() {
        // Every default must resolve to a cat_* resource, or the dropdown would offer a label
        // the display layer renders through the untranslated prettify path. `resource` matches
        // on the stored English label, so a multi-word default like "Fast food" must not be
        // routed through label()/prettify ("Fast Food"), which is what dropped it to 0 and let
        // the English string leak through in a Ukrainian locale.
        assertTrue(DEFAULT_CATEGORIES.isNotEmpty())
        assertEquals(DEFAULT_CATEGORIES, Categories(DEFAULT_CATEGORIES).names)
        DEFAULT_CATEGORIES.forEach { label ->
            assertTrue(
                "Category '$label' has no cat_* resource and would render untranslated",
                CategoryLabel.resource(label) != 0
            )
        }
    }
}
