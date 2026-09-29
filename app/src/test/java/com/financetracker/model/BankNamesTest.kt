package com.financetracker.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Turning a stored bank code into something worth showing.
 *
 * This is what `BankCode.label()` used to do, except the set of banks is data now, so the
 * name comes from a map rather than a `when`. The fallback rules are the part that matters:
 * they are what stop a dangling reference from looking like a hand-entered row.
 */
class BankNamesTest {

    private val known = mapOf(
        "mo" to "Monobank",
        "uk" to "Ukrsibbank",
        "bank-010203" to "ПриватБанк"
    )

    @Test
    fun `a known code resolves to the name the user gave it`() {
        assertEquals("Sense Bank", BankNames.display("bank-1", mapOf("bank-1" to "Sense Bank")))
    }

    @Test
    fun `the seeded names resolve to what the app has always shown`() {
        assertEquals("Monobank", BankNames.display(BankCode.MONOBANK, known))
        assertEquals("Ukrsibbank", BankNames.display(BankCode.UKRSIBBANK, known))
    }

    @Test
    fun `a user-chosen name resolves in any script`() {
        assertEquals("ПриватБанк", BankNames.display("bank-010203", known))
    }

    @Test
    fun `a row with no bank was entered by hand`() {
        assertEquals("Manual", BankNames.display(null, known))
    }

    @Test
    fun `a code that is not in the list is shown verbatim rather than hidden`() {
        // Never blank, and never "Manual": an archived bank, or one a row was written
        // against by a build that knew banks this one does not, must stay visibly
        // different from a row the user typed by hand.
        assertEquals("bank-gone", BankNames.display("bank-gone", known))
    }

    @Test
    fun `an empty list does not make every code look like a hand-entered row`() {
        assertEquals("mo", BankNames.display("mo", emptyMap()))
    }

    @Test
    fun `the search reference is null exactly when there is no bank`() {
        assertNull(BankNames.ref(null, known))
        assertEquals(BankRef("mo", "Monobank"), BankNames.ref("mo", known))
    }

    @Test
    fun `the search reference keeps the code and resolves the name`() {
        // Both are carried so a write path cannot pass a code where a name belongs.
        assertEquals(BankRef("bank-010203", "ПриватБанк"), BankNames.ref("bank-010203", known))
        assertEquals(BankRef("bank-gone", "bank-gone"), BankNames.ref("bank-gone", known))
    }
}
