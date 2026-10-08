package com.financetracker.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Suggestions for rows that arrived without a human-readable category.
 *
 * Pure JVM: no Android, no database. The failure pinned here is a suggestion that ignores
 * the user's own list and offers a label no dropdown holds — the preview would then show a
 * choice the form cannot repeat.
 */
class CategorySuggestionTest {

    private val categories = listOf("Groceries", "Restaurant", "Fuel", "Other")

    @Test
    fun `an mcc key maps onto the matching list entry`() {
        assertEquals(
            "Groceries",
            CategorySuggestion.suggest("mcc_5499", "TORUS", categories)
        )
    }

    @Test
    fun `an mcc key with no list match falls back to Other`() {
        assertEquals(
            "Other",
            CategorySuggestion.suggest("mcc_9999", "TORUS", categories)
        )
    }

    @Test
    fun `history wins over the generic label for a known title`() {
        val history = mapOf("torus" to "Groceries")
        assertEquals(
            "Groceries",
            CategorySuggestion.suggest("imported", "TORUS", categories, history)
        )
    }

    @Test
    fun `a past one-off stays suggestible even though it never joined the list`() {
        val history = mapOf("торус" to "Кава з собою")
        assertEquals(
            "Кава з собою",
            CategorySuggestion.suggest("imported", "Торус", categories, history)
        )
    }

    @Test
    fun `an unknown title falls back through the label to Other`() {
        assertEquals(
            "Other",
            CategorySuggestion.suggest("imported", "SOMETHING NEW", categories)
        )
    }

    @Test
    fun `an empty list falls back to the defaults`() {
        assertEquals(
            "Groceries",
            CategorySuggestion.suggest("mcc_5499", "TORUS", emptyList())
        )
    }
}
