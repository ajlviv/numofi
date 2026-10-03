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
    fun `three digits after a single separator is grouping, not a decimal point`() {
        // The case that was wrong. "12,345" is an ordinary amount — a salary, a transfer, a
        // purchase — typed on a keyboard whose decimal mark is a comma, and one separator with
        // three digits behind it used to be read as 12.345. That is a thousandfold error on the
        // most ordinary input the field takes, and nothing on screen said so.
        assertEquals(12345.0, parsed("12,345")!!, 1e-9)
        assertEquals(1234.0, parsed("1,234")!!, 1e-9)
        assertEquals(1234.0, parsed("1.234")!!, 1e-9)
        assertEquals(1000.0, parsed("1,000")!!, 1e-9)
    }

    @Test
    fun `any other number of digits after a single separator is a decimal point`() {
        // Two digits is a decimal in every convention that has one, and four is not a group
        // anybody writes. So the rule is about the width of the group, not a blanket "three
        // digits means thousands".
        assertEquals(1.23, parsed("1,23")!!, 1e-9)
        assertEquals(0.5, parsed("0,5")!!, 1e-9)
        assertEquals(1234.56, parsed("1234,56")!!, 1e-9)
        assertEquals(12.3456, parsed("12,3456")!!, 1e-9)
    }

    @Test
    fun `a repeated separator of one kind is grouping`() {
        assertEquals(1234567.0, parsed("1,234,567")!!, 1e-9)
        assertEquals(1234567.0, parsed("1.234.567")!!, 1e-9)
        assertEquals(12345678.0, parsed("12,345,678")!!, 1e-9)
    }

    @Test
    fun `grouping of the wrong width is refused rather than read as a number`() {
        // "12,50,50" is not grouping: no group after the first is three digits wide. Reading it
        // as one produced 125 050 — an amount nobody typed, off by four orders of magnitude,
        // from a mistyped box. Refusing is the honest answer and is what the forms did before
        // this parser existed, so it is a return to the earlier behaviour rather than a new
        // strictness: `toDoubleOrNull` also rejected it, and rejected the comma cases wrongly.
        assertNull(parsed("12,50,50"))
        assertNull(parsed("1,23,456"))
        assertNull(parsed("1234,56,78"))
    }

    @Test
    fun `grouping beside a decimal point is still checked`() {
        assertEquals(1234.56, parsed("1.234,56")!!, 1e-9)
        assertEquals(1234.56, parsed("1,234.56")!!, 1e-9)
        // The grouping half is malformed, so the whole thing is refused rather than repaired.
        assertNull(parsed("1.23,456"))
    }

    @Test
    fun `a separator with nothing after it is a decimal point`() {
        // What a numeric keyboard sends while the user is still typing: "1200,".
        assertEquals(1200.0, parsed("1200,")!!, 1e-9)
        assertEquals(1200.0, parsed("1200.")!!, 1e-9)
    }

    @Test
    fun `a minus sign survives every reading`() {
        // The refund, the reversal, the correction. A sign that parses in one branch and is
        // dropped in another would turn a refund into a charge.
        assertEquals(-1234.56, parsed("-1234,56")!!, 1e-9)
        assertEquals(-12345.0, parsed("-12,345")!!, 1e-9)
        assertEquals(-1234567.0, parsed("-1,234,567")!!, 1e-9)
        assertEquals(-5.0, parsed("-5")!!, 1e-9)
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