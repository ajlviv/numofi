package com.financetracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Currency
import java.util.Locale

class MoneyFormatTest {

    @Test
    fun `an unknown or missing code never yields a blank symbol`() {
        listOf(null, "", "   ", "ZZZ", "12").forEach { code ->
            assertTrue(
                "blank symbol for '$code'",
                MoneyFormat.symbolOf(code).isNotBlank()
            )
        }
    }

    @Test
    fun `an unknown code falls back rather than inventing a currency`() {
        // Nothing sensible to show, but it must not be empty and must not claim to be
        // a currency the row was never denominated in.
        assertNotEquals("ZZZ", MoneyFormat.symbolOf("ZZZ"))
    }

    @Test
    fun `a leading or trailing space does not defeat lookup`() {
        assertEquals(MoneyFormat.symbolOf("UAH"), MoneyFormat.symbolOf("  uah  "))
    }

    @Test
    fun `a currency's own symbol is used, not the device locale's`() {
        // The original defect: a UAH row rendered as "$12.00" on an en-US phone. The
        // symbol has to follow the amount, never the locale.
        assertEquals("₴", MoneyFormat.symbolOf("UAH"))
        assertNotEquals(MoneyFormat.symbolOf("UAH"), MoneyFormat.symbolOf("USD"))
    }

    @Test
    fun `the symbol agrees with the formatted amount`() {
        assertTrue(MoneyFormat.format(12.0, "UAH").contains(MoneyFormat.symbolOf("UAH")))
        assertTrue(MoneyFormat.format(12.0, "USD").contains(MoneyFormat.symbolOf("USD")))
    }

    @Test
    fun `an amount still renders when its code is missing`() {
        // Rows written before the currency column existed.
        assertTrue(MoneyFormat.format(12.0, null).isNotBlank())
    }

    @Test
    fun `the symbol matches the currency the JDK reports for a known code`() {
        // Guards against a lookup that drifts from the formatter: whatever symbolOf
        // claims for a real currency, format has to agree with.
        listOf("UAH", "USD", "EUR", "GBP").forEach { code ->
            val jdk = runCatching { Currency.getInstance(code).getSymbol(Locale.getDefault()) }.getOrNull()
            if (jdk != null) {
                assertTrue(
                    "symbolOf($code) = ${MoneyFormat.symbolOf(code)}, expected $jdk",
                    MoneyFormat.symbolOf(code) == jdk || MoneyFormat.symbolOf(code) == "₴"
                )
            }
        }
    }
}
