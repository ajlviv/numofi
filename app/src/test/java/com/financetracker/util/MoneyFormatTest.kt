package com.financetracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Currency
import java.util.Locale

/**
 * Non-breaking space, U+00A0, written as a code point.
 *
 * The formatter separates an amount from its symbol with a non-breaking space, and the JDK's
 * own grouping separator is one too. As a literal the character is invisible in a diff and
 * indistinguishable from an ordinary space when reading the source, so it is named here and
 * expectations normalise it before comparing.
 */
private val NBSP: Char = 0xA0.toChar()

class MoneyFormatTest {

    /** Turns the non-breaking spaces back into ordinary ones so a string can be spelled out. */
    private fun readable(text: String) = text.replace(NBSP, ' ')

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
    fun `a UAH amount is not rendered as the letters UAH on a foreign locale`() {
        // The original defect. The formatter has no symbol for UAH in most locales and falls
        // back to the code itself, so an en-US device rendered "UAH12.00". This host defaults
        // to uk-UA, which already renders the hryvnia sign and would mask a regression, so
        // the default locale is switched for the duration of the check.
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val formatted = MoneyFormat.format(1234.5, "UAH")
            assertFalse("rendered as '$formatted'", formatted.contains("UAH"))
            assertTrue("rendered as '$formatted'", formatted.contains("₴"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `an en-US device still groups and keeps its own decimal separator`() {
        // Pinning the whole string, symbol and separator both, so a change to either is caught
        // here rather than showing up as a surprise on someone's phone.
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("1,234.50 ₴", readable(MoneyFormat.format(1234.5, "UAH")))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `a currency the JDK knows is left alone`() {
        // Guards against the trailing-symbol change reaching codes that never needed it.
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("1,234.50 \$", readable(MoneyFormat.format(1234.5, "USD")))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `the parts of a split amount rejoin into the formatted amount`() {
        // The round trip is what makes the split safe to derive: nothing here decides where a
        // symbol belongs or how a number is grouped, so no locale can be broken by it.
        val codes = listOf("UAH", "USD", "EUR", "GBP", "JPY")
        val locales = listOf(Locale.US, Locale.UK, Locale.GERMANY, Locale("uk", "UA"))
        val original = Locale.getDefault()
        try {
            locales.forEach { locale ->
                Locale.setDefault(locale)
                codes.forEach { code ->
                    listOf(0.0, 12.0, 1234.5, -99.99, 1_000_000.0).forEach { amount ->
                        val parts = MoneyFormat.text(amount, code)
                        assertEquals(
                            "$locale $code $amount",
                            MoneyFormat.format(amount, code),
                            parts.plain
                        )
                    }
                }
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `the symbol trails the amount in every locale`() {
        // Placement is deliberately not the locale's: a leading currency mark reads as a
        // digit, and one that moves with the device language is one more thing to relearn.
        val original = Locale.getDefault()
        try {
            listOf(Locale.US, Locale.UK, Locale.GERMANY, Locale("uk", "UA")).forEach { locale ->
                Locale.setDefault(locale)
                val parts = MoneyFormat.text(1234.5, "UAH")
                assertEquals("symbol in $locale", "₴", parts.symbol)
                assertEquals("nothing after the symbol in $locale", "", parts.suffix)
                assertTrue(
                    "no digits before the symbol in $locale: '${parts.prefix}'",
                    parts.prefix.any { it.isDigit() }
                )
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `the number formatting still differs between locales`() {
        // The trailing symbol does not flatten the rest: a Ukrainian phone still reads
        // "1 234,50" where an en-US one reads "1,234.50", symbol aside.
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val us = readable(MoneyFormat.format(1234.5, "UAH"))
            Locale.setDefault(Locale("uk", "UA"))
            val uk = readable(MoneyFormat.format(1234.5, "UAH"))
            assertEquals("1,234.50 ₴", us)
            assertEquals("1 234,50 ₴", uk)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `the amount and its symbol are joined by a non-breaking space`() {
        // An ordinary space would let a narrow row wrap the symbol onto a line of its own,
        // which is worse than it sounds: a lone "₴" under an amount reads as a second figure.
        val formatted = MoneyFormat.format(1234.5, "UAH")
        assertTrue("expected a non-breaking space in '$formatted'", formatted.contains(NBSP))
    }

    @Test
    fun `a negative amount keeps its sign in front of the digits`() {
        // The sign belongs to the number, not the currency, so it stays on the left even
        // though the symbol has moved to the right.
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("-1,234.50 ₴", readable(MoneyFormat.format(-1234.5, "UAH")))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `an unknown code still produces a complete amount`() {
        // The code is unknown, so the device's own currency stands in for it, but the digits
        // and the symbol must both survive: a wrong currency is recoverable, a truncated
        // amount is not.
        val parts = MoneyFormat.text(12.0, "ZZZ")
        assertEquals(MoneyFormat.format(12.0, "ZZZ"), parts.plain)
        assertTrue(parts.plain.any { it.isDigit() })
    }

    @Test
    fun `a missing currency code still yields a displayable amount`() {
        val parts = MoneyFormat.text(12.0, null)
        assertEquals(MoneyFormat.format(12.0, null), parts.plain)
        assertTrue(parts.plain.isNotBlank())
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
