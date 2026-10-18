package com.financetracker.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic every palette assertion in this package rests on.
 *
 * The ratios pinned here are hand-computed from the WCAG 2.1 formula, so a broken
 * [contrastRatio] cannot make the palette tests pass by returning 21 for everything.
 */
class ContrastTest {

    @Test
    fun `black on white is the full 21 to 1`() {
        assertEquals(21f, contrastRatio(Color.Black, Color.White), 0.01f)
        assertEquals(21f, contrastRatio(Color.White, Color.Black), 0.01f)
    }

    @Test
    fun `a colour against itself is 1 to 1`() {
        assertEquals(1f, contrastRatio(Color(0xFF2E7D32), Color(0xFF2E7D32)), 0.01f)
    }

    @Test
    fun `a known pair matches its hand-computed ratio`() {
        // #2E7D32 on white is 5.13:1 — the income green the light theme has always used,
        // and the figure the MoneyAmount docs quote for the mustard.
        assertEquals(5.13f, contrastRatio(Color(0xFF2E7D32), Color.White), 0.01f)
        // #66BB6A on #1C1C1C is 7.21:1 — the dark-mode income green, just over the AAA bar.
        assertEquals(7.21f, contrastRatio(Color(0xFF66BB6A), Color(0xFF1C1C1C)), 0.01f)
    }

    @Test
    fun `the app's dark surfaces are recognised as dark`() {
        assertTrue(Color(0xFF121212).isDark())
        assertTrue(Color(0xFF1C1C1C).isDark())
    }

    @Test
    fun `the app's light surfaces are not`() {
        assertFalse(Color.White.isDark())
        assertFalse(Color(0xFFFAFAFA).isDark())
        // The threshold is on relative luminance rather than the sRGB channel, so a card
        // well short of white is still a light one: #CCCCCC sits at 0.60.
        assertFalse(Color(0xFFCCCCCC).isDark())
    }

    @Test
    fun `the two bars are the ones WCAG names`() {
        assertEquals(4.5f, CONTRAST_AA, 0.001f)
        assertEquals(7f, CONTRAST_AAA, 0.001f)
    }
}
