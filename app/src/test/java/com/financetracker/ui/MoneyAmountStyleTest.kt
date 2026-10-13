package com.financetracker.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tabular figures, so a column of amounts lines up and recomposition does not shuffle it.
 *
 * Merging rather than overwriting: a caller may already have font features of its own
 * (`smcp` for small caps), and silently dropping them to make numbers line up would be
 * one feature fixing itself by breaking another.
 */
class MoneyAmountStyleTest {

    @Test
    fun `money figures get tabular digits`() {
        val style = TextStyle(fontSize = 16.sp)

        assertEquals("tnum", withTabularFigures(style).fontFeatureSettings)
    }

    @Test
    fun `existing font features survive`() {
        val style = TextStyle(fontFeatureSettings = "smcp")

        assertEquals("smcp, tnum", withTabularFigures(style).fontFeatureSettings)
    }

    @Test
    fun `the rest of the style is untouched`() {
        val style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)

        val tabular = withTabularFigures(style)

        assertEquals(style.fontSize, tabular.fontSize)
        assertEquals(style.fontWeight, tabular.fontWeight)
    }
}
