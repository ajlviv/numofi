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
    fun `an mcc key with no list match keeps the label it maps to`() {
        // The label is a bucket the app can show, so dropping it to "Other" hides what the
        // bank actually reported. "Other" is only a suggestion when the label is blank.
        assertEquals(
            "Restaurant",
            CategorySuggestion.suggest("mcc_5812", "SOME CAFE", categories)
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
    fun `an unknown title keeps the Imported label rather than collapsing to Other`() {
        // A statement line carries no MCC, so its honest bucket is "Imported"; filing it as
        // "Other" would claim the user classified it and pile every import into one slice.
        assertEquals(
            "Imported",
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
