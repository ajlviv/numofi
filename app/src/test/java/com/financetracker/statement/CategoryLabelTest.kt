package com.financetracker.statement

import com.financetracker.util.CategoryLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Categories are stored as machine keys, so the display layer has to translate them.
 * A raw `mcc_` key leaking into the UI is the failure this guards against.
 */
class CategoryLabelTest {

    @Test
    fun `maps a known merchant category code to a readable label`() {
        assertEquals("Groceries", CategoryLabel.label("mcc_5499"))
    }

    @Test
    fun `maps codes covered by a range`() {
        assertEquals("Restaurant", CategoryLabel.label("mcc_5812"))
        assertEquals("Fuel", CategoryLabel.label("mcc_5541"))
    }

    @Test
    fun `falls back to the range for an unknown code in a known range`() {
        // 5735 is not named exactly but sits in the home and electronics range.
        assertEquals("Home & electronics", CategoryLabel.label("mcc_5735"))
    }

    @Test
    fun `never returns a raw mcc key`() {
        val codes = listOf(1, 99, 1234, 9999, 5499, 7997)
        codes.forEach { code ->
            val label = CategoryLabel.label("mcc_$code")
            assertEquals("mcc_ prefix must not be shown for $code", false, label.startsWith("mcc_"))
        }
    }

    @Test
    fun `labels imported rows`() {
        assertEquals("Imported", CategoryLabel.label("imported"))
    }

    @Test
    fun `labels the fallback category`() {
        assertEquals("Other", CategoryLabel.label("other"))
    }

    @Test
    fun `prettifies a free text category`() {
        assertEquals("Groceries", CategoryLabel.label("groceries"))
        assertEquals("Home Rent", CategoryLabel.label("home_rent"))
    }

    @Test
    fun `handles null and blank categories`() {
        assertEquals("Uncategorised", CategoryLabel.label(null))
        assertEquals("Uncategorised", CategoryLabel.label("  "))
    }

    @Test
    fun `handles a malformed code without crashing`() {
        assertEquals("Other", CategoryLabel.label("mcc_abc"))
    }

    @Test
    fun `is case insensitive about the prefix`() {
        assertEquals("Groceries", CategoryLabel.label("MCC_5499"))
    }

    @Test
    fun `keeps a canonical label unchanged so a second pass is a no-op`() {
        // Rows are stored under the canonical English label. Running one back through label()
        // must return it byte-identically: prettify would otherwise produce "Fast Food" and
        // "Home & Furniture", which match neither the resource table nor the icon table.
        val canonical = listOf(
            "Fast food", "Home & furniture", "Travel & transport", "Groceries",
            "Bar & nightlife", "Toys & games", "Phone & data", "Accounting & legal",
            "Imported", "Other", "Investments"
        )
        canonical.forEach { label ->
            assertEquals(label, CategoryLabel.label(label))
        }
    }

    @Test
    fun `a canonical label still resolves to its translation`() {
        // Idempotence is not just cosmetic: the resolved label is what resource() and the icon
        // table are keyed by, so returning the canonical form is what makes both hit.
        assertTrue(CategoryLabel.resource("Fast food") != 0)
        assertTrue(CategoryLabel.resource("Home & furniture") != 0)
    }
}
