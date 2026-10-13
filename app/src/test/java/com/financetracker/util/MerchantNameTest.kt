package com.financetracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The heuristic that turns a raw statement string into something a person can read as a
 * merchant name.
 *
 * Titles are stored verbatim from bank statements, so the cleanup runs at render time and
 * must never rewrite a stored row. Every case here is a shape that actually reaches the
 * list: boilerplate purpose wording, trailing document codes, and the short junk a user
 * typed into a manual row - `три`, `ууу` - which has no merchant in it at all and must
 * fall through to the category instead.
 */
class MerchantNameTest {

    @Test
    fun `boilerplate purpose wording is stripped`() {
        assertEquals(
            "NOVUS КИЇВ",
            MerchantName.clean("ОПЛАТА ЗА ТОВАРІВ/ПОСЛУГ. NOVUS КИЇВ.")
        )
        assertEquals(
            "АТБ-МАРКЕТ",
            MerchantName.clean("Оплата за товари/послуги - АТБ-МАРКЕТ")
        )
        assertEquals(
            "COMFY",
            MerchantName.clean("ПОКУПКА ТОВАРІВ ТА ПОСЛУГ. COMFY")
        )
    }

    @Test
    fun `document codes are cut`() {
        assertEquals("ПП ГАРМОНІЯ", MerchantName.clean("ПП ГАРМОНІЯ C3000135"))
        assertEquals("УКРПОШТА", MerchantName.clean("УКРПОШТА №123456"))
        assertEquals("AKS", MerchantName.clean("AKS 5000012345"))
    }

    @Test
    fun `the first meaningful segment wins`() {
        assertEquals("FOOD_PLUS", MerchantName.clean("FOOD_PLUS; Київ, пр. 1"))
        assertEquals("MEGA", MerchantName.clean("MEGA | постачальник"))
    }

    @Test
    fun `clean names pass through untouched`() {
        assertEquals("NOVUS", MerchantName.clean("NOVUS"))
        assertEquals("Оплата", MerchantName.clean("Оплата"))
        assertEquals("Сільпо", MerchantName.clean("  Сільпо  "))
    }

    @Test
    fun `blank input has no name`() {
        assertNull(MerchantName.clean(""))
        assertNull(MerchantName.clean("   "))
        assertNull(MerchantName.clean("/// ; |"))
    }

    @Test
    fun `junk titles are not meaningful`() {
        assertFalse(MerchantName.isMeaningful("три"))
        assertFalse(MerchantName.isMeaningful("ууу"))
        assertFalse(MerchantName.isMeaningful("12345"))
        assertFalse(MerchantName.isMeaningful("тест"))
        assertFalse(MerchantName.isMeaningful("n/a"))
        assertFalse(MerchantName.isMeaningful(""))
        assertFalse(MerchantName.isMeaningful("ab"))
    }

    @Test
    fun `real names are meaningful`() {
        assertTrue(MerchantName.isMeaningful("NOVUS"))
        assertTrue(MerchantName.isMeaningful("АТБ"))
        assertTrue(MerchantName.isMeaningful("ПП ГАРМОНІЯ"))
    }

    @Test
    fun `a garbage title falls back to the category`() {
        assertEquals("Groceries", MerchantName.displayName("ууу") { "Groceries" })
        assertEquals("Groceries", MerchantName.displayName("12345") { "Groceries" })
        assertEquals("Groceries", MerchantName.displayName("") { "Groceries" })
    }

    @Test
    fun `a readable title becomes the display name`() {
        assertEquals("NOVUS", MerchantName.displayName("ОПЛАТА ЗА ТОВАРІВ/ПОСЛУГ NOVUS") { "Groceries" })
    }

    @Test
    fun `the category lambda is not called when the merchant is readable`() {
        var asked = false
        val name = MerchantName.displayName("NOVUS") { asked = true; "Groceries" }

        assertEquals("NOVUS", name)
        assertFalse(asked)
    }

    @Test
    fun `cleanup is idempotent`() {
        val samples = listOf(
            "ОПЛАТА ЗА ТОВАРІВ/ПОСЛУГ. NOVUS КИЇВ. C3000135",
            "ПП ГАРМОНІЯ",
            "trip to nowhere",
            "УКРПОШТА №123456"
        )
        for (sample in samples) {
            val once = MerchantName.clean(sample)
            assertEquals(once, once?.let { MerchantName.clean(it) })
        }
    }
}
