package com.financetracker.ui.theme

import androidx.compose.ui.graphics.Color
import com.financetracker.model.CategorySlice

/**
 * The donut's colours: six hues, one per named category, plus one neutral for whatever the
 * breakdown folded into "Other".
 *
 * Six rather than twelve because colour is only an aid — the legend names every slice — and
 * past about six the eye stops matching a wedge to a row anyway. The same six *families* in
 * the same order in both variants, so a category keeps its colour when the theme flips: only
 * the lightness moves, and it moves far enough to keep each value above the bar on the
 * surface it is drawn on.
 *
 * The split by surface luminance is forced by arithmetic, not taste. No single colour clears
 * 4.5:1 on white and 3:1 on `#1C1C1C` at once — that needs a relative luminance both below
 * 0.18 and above 0.23 — so a palette that is high-contrast in both themes has to be two
 * palettes. The light values clear AA (4.5:1) on the lightest surface the card can be; the
 * dark values clear AAA (7:1) on the darkest.
 *
 * [CategoryPaletteTest] asserts every one of those ratios, so a re-tint that drops a hue
 * below its bar fails the build rather than a reader's eye.
 */
object CategoryPalette {

    /** Burnt orange, rose, purple, deep blue, teal, olive. */
    val Light = listOf(
        Color(0xFFBF360C),
        Color(0xFFAD1457),
        Color(0xFF6A1B9A),
        Color(0xFF01579B),
        Color(0xFF00695C),
        Color(0xFF7A6C00)
    )

    /** The same six families, lightened until each clears AAA on `#121212` and `#1C1C1C`. */
    val Dark = listOf(
        Color(0xFFFFB74D),
        Color(0xFFF48FB1),
        Color(0xFFD1C4E9),
        Color(0xFF90CAF9),
        Color(0xFF80DEEA),
        Color(0xFFA5D6A7)
    )

    /**
     * The one colour for everything that was folded away.
     *
     * Grey on purpose and in both themes: "Other" is not a category, it is the absence of
     * one, and giving it a hue would present the residue as a seventh peer of the named
     * slices. Grey is also the one thing it cannot be mistaken for among six saturated
     * families.
     */
    val LightNeutral = Color(0xFF616161)
    val DarkNeutral = Color(0xFFBDBDBD)

    /** The palette for a surface of the given darkness, largest hue first in legend order. */
    fun primary(onDark: Boolean): List<Color> = if (onDark) Dark else Light

    /** The colour "Other" wears on a surface of the given darkness. */
    fun neutral(onDark: Boolean): Color = if (onDark) DarkNeutral else LightNeutral
}

/**
 * One colour per displayed slice, in legend order.
 *
 * A row carrying [otherLabel] takes the neutral, whatever position it landed in — the
 * breakdown sorts by amount, so a dominant "Other" leads the legend and must not inherit the
 * first hue as though it were the top category. Everything else takes the palette in order,
 * so the largest named category always wears the first hue.
 *
 * The modulo is belt-and-braces: with [com.financetracker.model.displaySlices] folding at six
 * named categories the palette is never overrun, but a caller passing its own cap gets a
 * repeating palette rather than an index out of bounds.
 */
fun displayColors(
    slices: List<CategorySlice>,
    otherLabel: String,
    onDark: Boolean
): List<Color> {
    val palette = CategoryPalette.primary(onDark)
    val neutral = CategoryPalette.neutral(onDark)
    var next = 0
    return slices.map { slice ->
        if (slice.label == otherLabel) neutral else palette[next++ % palette.size]
    }
}
