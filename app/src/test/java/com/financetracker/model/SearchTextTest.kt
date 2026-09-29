package com.financetracker.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The haystack every transaction is findable by.
 *
 * It is built once at write time and never rebuilt, which is what makes the Cyrillic
 * folding here rather than in SQL: SQLite's `lower()` folds ASCII only, so a Cyrillic
 * title or a Cyrillic bank name would silently stop matching a lowercased query.
 */
class SearchTextTest {

    @Test
    fun `folds Cyrillic in both directions`() {
        val folded = SearchText.of("МОБІЛЬНИЙ ОПЕРАТОР", null, null, null, null)

        assertTrue(folded.contains("мобільний"))
    }

    @Test
    fun `spans title note category and card`() {
        val folded = SearchText.of("TORUS", "coffee", "grocery", null, "535129****5783")

        assertTrue(folded.contains("torus"))
        assertTrue(folded.contains("coffee"))
        assertTrue(folded.contains("grocery"))
        assertTrue(folded.contains("535129"))
    }

    @Test
    fun `includes the bank name so typing a bank finds its rows`() {
        val folded = SearchText.of("x", null, null, BankRef("mo", "Monobank"), null)

        assertTrue(folded.contains("monobank"))
    }

    @Test
    fun `includes a user-chosen bank name in whatever script it was typed in`() {
        // The point of resolving the name at all: a generated code like "bank-010203" is
        // not something anyone can type, and SQL could not fold this name either.
        val folded = SearchText.of("x", null, null, BankRef("bank-010203", "ПриватБанк"), null)

        assertTrue(folded.contains("приватбанк"))
    }

    @Test
    fun `a row with no bank contributes no bank text at all`() {
        // "Manual" is a display word, not something the user would search for, so it must
        // not land in the haystack.
        assertEquals("lunch", SearchText.of("Lunch", null, null, null, null))
    }

    @Test
    fun `an unknown bank contributes its code so the row is still findable`() {
        val folded = SearchText.of("x", null, null, BankRef("bank-gone", "bank-gone"), null)

        assertTrue(folded.contains("bank-gone"))
    }

    @Test
    fun `blank fields contribute nothing`() {
        assertEquals("lunch", SearchText.of("Lunch", "", null, null, "  "))
    }

    @Test
    fun `a blank bank name does not leave a double space in the haystack`() {
        val folded = SearchText.of("Lunch", null, null, BankRef("mo", "  "), null)

        assertFalse(folded.contains("  "))
    }
}
