package com.financetracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one place a typed amount becomes a number, so the one place the three forms can disagree
 * about. The cases below are what a Ukrainian numeric keypad actually sends, plus the cases that
 * must keep working: an empty box, a pasted en-US amount, and text that is not a number at all.
 */
class AmountInputTest {

    private fun parsed(text: String) = AmountInput.parse(text)

    @Test
    fun `a single separator is a decimal point, whatever follows it`() {
        // The one genuinely ambiguous input, pinned so a later change cannot quietly pick the
        // other reading. "1,234" is 1.234 and not 1234: a single separator meant a decimal point
        // before this function existed, and a price of 1.234 is a real thing to type.
        assertEquals(1.234, parsed("1,234")!!, 1e-9)
        assertEquals(1.234, parsed("1.234")!!, 1e-9)
    }

    @Test
    fun `a repeated separator of one kind is grouping, not a decimal point`() {
        // Where the KDoc used to contradict itself: it stated the rule as "the rightmost is the
        // decimal point" and then used this very string as an example of reading correctly.
        // Grouping is the only reading that leaves 1 234 567 alone, so grouping is what it does.
        assertEquals(1234567.0, parsed("1,234,567")!!, 1e-9)
        assertEquals(1234567.0, parsed("1.234.567")!!, 1e-9)
    }

    @Test
    fun `malformed repeated separators are read as grouping rather than refused`() {
        // "12,50,50" is not valid grouping — no group is the wrong width — but it still parses,
        // to a number far larger than anything typed. Recorded here because it is a real
        // consequence of the grouping reading above, and the alternative (validating every
        // group's width) is a larger decision than this function has made on its own.
        assertEquals(125050.0, parsed("12,50,50")!!, 1e-9)
    }

    @Test
    fun `a comma is a decimal separator`() {
        // The whole reason this exists. A phone set to Ukrainian puts a comma on the numeric
        // keyboard's decimal key, and the two forms that called toDoubleOrNull() refused it.
        assertEquals(1234.56, parsed("1234,56")!!, 0.0)
        assertEquals(0.5, parsed("0,5")!!, 0.0)
        assertEquals(1200.0, parsed("1200,")!!, 0.0)
    }

    @Test
    fun `a full stop is a decimal separator, and the two never disagree`() {
        assertEquals(1234.56, parsed("1234.56")!!, 0.0)
        assertEquals(parsed("1234,56")!!, parsed("1234.56")!!, 0.0)
    }

    @Test
    fun `grouping marks carry no value`() {
        // Keyboard grouping is a thin or non-breaking space; a pasted amount uses a comma or a
        // full stop. None of them may end up multiplied into the amount.
        assertEquals(1234.56, parsed("1 234,56")!!, 0.0)
        assertEquals(1234.56, parsed("1\u00A0234,56")!!, 0.0)
        assertEquals(1234.56, parsed("1\u202F234,56")!!, 0.0)
        assertEquals(1234.56, parsed("1,234.56")!!, 0.0)
        assertEquals(1234.56, parsed("1.234,56")!!, 0.0)
    }

    @Test
    fun `a repeated separator groups rather than truncating`() {
        // "1,234,567" is 1234567 everywhere; reading the last comma as a decimal point would
        // silently turn a million into a thousandth of one.
        assertEquals(1234567.0, parsed("1,234,567")!!, 0.0)
        assertEquals(1234567.0, parsed("1.234.567")!!, 0.0)
        assertEquals(1234567.89, parsed("1.234.567,89")!!, 0.0)
        assertEquals(1234567.89, parsed("1,234,567.89")!!, 0.0)
    }

    @Test
    fun `a leading sign is kept`() {
        // No form offers a negative amount, but a parser that dropped the sign would turn a
        // mistyped refund into a payment rather than letting the form refuse it.
        assertEquals(-1234.56, parsed("-1234,56")!!, 0.0)
        assertEquals(1234.56, parsed("+1234,56")!!, 0.0)
    }

    @Test
    fun `whitespace around an amount is ignored`() {
        assertEquals(1234.56, parsed("  1234,56  ")!!, 0.0)
    }

    @Test
    fun `nothing is null rather than zero`() {
        // Null is "not a number", which the forms refuse a save on; zero is a number they
        // accept and then refuse on their own terms. Collapsing the two would let an empty box
        // save as nothing.
        listOf(null, "", "   ", ",", ".", "..", ",,", "-", "abc", "12abc", "1,2,3.4.5,6")
            .forEach { text ->
                assertNull("'$text' should not parse", AmountInput.parse(text))
            }
    }
}