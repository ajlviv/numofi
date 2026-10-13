package com.financetracker.ui.transaction

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one secondary line under a row's title: `[note] - [account] - [merchant]`.
 *
 * The merchant segment is conditional on the title — when the title already *is* the
 * merchant, repeating it in the subtitle would read `NOVUS - Monobank • 4111 - NOVUS`.
 * The repetition only ever helps when the title fell back to the category, and that is
 * exactly the case the third segment exists for.
 */
class TransactionRowSubtitleTest {

    @Test
    fun `all three parts are joined in order`() {
        assertEquals(
            "For rent - Monobank • 4111****2222 - NOVUS",
            composeRowSubtitle(
                title = "Groceries",
                note = "For rent",
                account = "Monobank • 4111****2222",
                merchant = "NOVUS"
            )
        )
    }

    @Test
    fun `a merchant already in the title is not repeated`() {
        assertEquals(
            "For rent - Monobank • 4111****2222",
            composeRowSubtitle(
                title = "NOVUS",
                note = "For rent",
                account = "Monobank • 4111****2222",
                merchant = "NOVUS"
            )
        )
    }

    @Test
    fun `missing parts are skipped, not printed as gaps`() {
        assertEquals(
            "Monobank",
            composeRowSubtitle(title = "NOVUS", note = null, account = "Monobank", merchant = "NOVUS")
        )
        assertEquals(
            "NOVUS",
            composeRowSubtitle(title = "Groceries", note = "", account = null, merchant = "NOVUS")
        )
        assertEquals(
            "For rent - Monobank",
            composeRowSubtitle(title = "NOVUS", note = "For rent", account = "Monobank", merchant = null)
        )
    }

    @Test
    fun `nothing to say is an empty line, not a separator`() {
        assertEquals("", composeRowSubtitle("NOVUS", null, null, "NOVUS"))
        assertEquals("", composeRowSubtitle("NOVUS", "", "  ", null))
    }

    @Test
    fun `parts are trimmed before joining`() {
        assertEquals(
            "note - Monobank",
            composeRowSubtitle(title = "NOVUS", note = "  note  ", account = " Monobank ", merchant = "NOVUS")
        )
    }
}
