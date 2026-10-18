package com.financetracker.ui.theme

import androidx.compose.ui.graphics.Color
import com.financetracker.model.CategorySlice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chart palette, asserted rather than admired.
 *
 * These are the ratios the app claims in its design: AA for every hue on a light surface,
 * AAA for every hue and the neutral on a dark one. Colour is only an aid — the legend names
 * each slice — so nothing here is about beauty; it is about a wedge and its swatch staying
 * visible against the card in either theme, and about "Other" being unmistakably not-a-hue.
 */
class CategoryPaletteTest {

    private val lightSurfaces = listOf(Color.White, Color(0xFFFAFAFA))
    private val darkSurfaces = listOf(Color(0xFF1C1C1C), Color(0xFF121212))

    @Test
    fun `there are six hues in each theme and no two are the same colour`() {
        assertEquals(6, CategoryPalette.Light.size)
        assertEquals(6, CategoryPalette.Dark.size)
        assertEquals(6, CategoryPalette.Light.toSet().size)
        assertEquals(6, CategoryPalette.Dark.toSet().size)
        // And no hue is shared across themes either: a light value must not reappear dark.
        assertEquals(12, (CategoryPalette.Light + CategoryPalette.Dark).toSet().size)
    }

    @Test
    fun `every light hue clears AA on every light surface`() {
        for (hue in CategoryPalette.Light) {
            for (surface in lightSurfaces) {
                assertTrue(
                    "light hue ${hue.hex()} on ${surface.hex()} is ${contrastRatio(hue, surface)}",
                    contrastRatio(hue, surface) >= CONTRAST_AA
                )
            }
        }
    }

    @Test
    fun `every dark hue clears AAA on every dark surface`() {
        for (hue in CategoryPalette.Dark) {
            for (surface in darkSurfaces) {
                assertTrue(
                    "dark hue ${hue.hex()} on ${surface.hex()} is ${contrastRatio(hue, surface)}",
                    contrastRatio(hue, surface) >= CONTRAST_AAA
                )
            }
        }
    }

    @Test
    fun `the neutral clears the bar on its own surfaces and is grey in both themes`() {
        for (surface in lightSurfaces) {
            assertTrue(contrastRatio(CategoryPalette.LightNeutral, surface) >= CONTRAST_AA)
        }
        for (surface in darkSurfaces) {
            assertTrue(contrastRatio(CategoryPalette.DarkNeutral, surface) >= CONTRAST_AAA)
        }
        assertTrue(isAchromatic(CategoryPalette.LightNeutral))
        assertTrue(isAchromatic(CategoryPalette.DarkNeutral))
    }

    @Test
    fun `the neutral is not one of the hues`() {
        for (hue in CategoryPalette.Light) {
            assertNotEquals(hue, CategoryPalette.LightNeutral)
        }
        for (hue in CategoryPalette.Dark) {
            assertNotEquals(hue, CategoryPalette.DarkNeutral)
        }
    }

    @Test
    fun `a named slice takes the palette in order`() {
        val slices = listOf(
            CategorySlice("Groceries", 300.0, 60.0),
            CategorySlice("Fuel", 200.0, 40.0)
        )

        val colors = displayColors(slices, otherLabel = "Other", onDark = false)

        assertEquals(listOf(CategoryPalette.Light[0], CategoryPalette.Light[1]), colors)
    }

    @Test
    fun `the Other row takes the neutral whatever position it lands in`() {
        // The breakdown sorts by amount, so a dominant Other leads the legend — and must not
        // inherit the first hue as though it were the top category.
        val slices = listOf(
            CategorySlice("Other", 900.0, 90.0),
            CategorySlice("Fuel", 100.0, 10.0)
        )

        val colors = displayColors(slices, otherLabel = "Other", onDark = false)

        assertEquals(
            listOf(CategoryPalette.LightNeutral, CategoryPalette.Light[0]),
            colors
        )
    }

    @Test
    fun `a dark surface takes the dark palette and the dark neutral`() {
        val slices = listOf(
            CategorySlice("Other", 100.0, 50.0),
            CategorySlice("Fuel", 100.0, 50.0)
        )

        val colors = displayColors(slices, otherLabel = "Other", onDark = true)

        assertEquals(listOf(CategoryPalette.DarkNeutral, CategoryPalette.Dark[0]), colors)
    }

    @Test
    fun `a palette overrun repeats rather than running off the end`() {
        // displaySlices folds at six named categories, so this needs a caller's own cap to
        // reach; the point is that it degrades to a repeating palette instead of a crash.
        val slices = (1..9).map { CategorySlice("c$it", 1.0, 0.0) }

        val colors = displayColors(slices, otherLabel = "Other", onDark = false)

        assertEquals(9, colors.size)
        assertEquals(CategoryPalette.Light[5], colors[5])
        assertEquals(CategoryPalette.Light[0], colors[6])
    }

    /** A grey has no channel standing out: the spread between its widest and narrowest. */
    private fun isAchromatic(color: Color): Boolean {
        val channels = listOf(color.red, color.green, color.blue)
        return channels.max() - channels.min() <= 8f / 255f
    }

    private fun Color.hex(): String =
        "#%02X%02X%02X".format((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
}
