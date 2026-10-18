package com.financetracker.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * WCAG 2.1 contrast ratio between two opaque colours, from 1.0 (invisible) to 21.0 (black
 * on white).
 *
 * It exists so the palette can be *asserted* rather than eyeballed. Every colour the app
 * prints a figure in is checked against the surface it lands on by the tests in this package,
 * and those checks go through this function so a re-tint cannot quietly drop a value below
 * the bar the app claims to hold.
 */
fun contrastRatio(foreground: Color, background: Color): Float {
    val fg = foreground.luminance()
    val bg = background.luminance()
    val lighter = maxOf(fg, bg)
    val darker = minOf(fg, bg)
    return (lighter + 0.05f) / (darker + 0.05f)
}

/**
 * WCAG's floor for ordinary body text, and the floor this app holds anywhere colour carries
 * meaning — including amounts, which are read at 14–16sp and so do not qualify as "large
 * text" (the 3:1 allowance starts at 18.66sp bold).
 */
const val CONTRAST_AA = 4.5f

/**
 * WCAG's "enhanced" ratio, 7:1. The target for figures printed on a dark surface, where it
 * is reachable without washing the hue out — and the target the dark palettes are asserted
 * against.
 */
const val CONTRAST_AAA = 7f

/**
 * True when this colour is a surface the app paints dark.
 *
 * The same 0.5 luminance test [com.financetracker.ui.MoneyAmount] already uses to pick the
 * mustard symbol colour, hoisted here so the accents and the chart palette decide the same
 * way. It is decided from the palette rather than from `isSystemInDarkTheme()` because the
 * app's dark mode is a setting the user picks: a phone in system light mode with the app
 * forced to Dark must get the dark-surface colours.
 */
fun Color.isDark(): Boolean = luminance() < 0.5f
