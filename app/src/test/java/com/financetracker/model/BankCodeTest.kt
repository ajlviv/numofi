package com.financetracker.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BankCodeTest {

    @Test
    fun `labels the banks this app supports`() {
        assertEquals("Monobank", BankCode.label(BankCode.MONOBANK))
        assertEquals("Ukrsibbank", BankCode.label(BankCode.UKRSIBBANK))
        assertEquals("PrivatBank", BankCode.label(BankCode.PRIVATBANK))
    }

    @Test
    fun `every supported bank reaches the picker that reads CHOICES`() {
        assertEquals(listOf("mo", "uk", "pb"), BankCode.CHOICES)
    }

    @Test
    fun `a row with no bank code was entered by hand`() {
        assertEquals("Manual", BankCode.label(null))
    }

    @Test
    fun `an unrecognised code is shown verbatim rather than hidden`() {
        assertEquals("xx", BankCode.label("xx"))
    }

    @Test
    fun `codes are normalised so casing and padding cannot create two chips`() {
        assertEquals(BankCode.UKRSIBBANK, BankCode.normalize(" UK "))
        assertEquals(BankCode.MONOBANK, BankCode.normalize("mo"))
        assertEquals(BankCode.PRIVATBANK, BankCode.normalize(" PB "))
    }

    @Test
    fun `an unknown code normalises to null rather than inventing one`() {
        assertNull(BankCode.normalize("paypal"))
    }

    @Test
    fun `search text folds Cyrillic in both directions`() {
        val folded = SearchText.of("МОБІЛЬНИЙ ОПЕРАТОР", null, null, null, null)
        assertTrue(folded.contains("мобільний"))
    }

    @Test
    fun `search text spans title note category and card`() {
        val folded = SearchText.of("TORUS", "coffee", "grocery", null, "535129****5783")
        assertTrue(folded.contains("torus"))
        assertTrue(folded.contains("coffee"))
        assertTrue(folded.contains("grocery"))
        assertTrue(folded.contains("535129"))
    }

    @Test
    fun `search text includes the bank label so a bank name finds its rows`() {
        assertTrue(SearchText.of("x", null, null, BankCode.MONOBANK, null).contains("monobank"))
    }

    @Test
    fun `blank fields contribute nothing`() {
        assertEquals("lunch", SearchText.of("Lunch", "", null, null, "  "))
    }
}
