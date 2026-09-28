package com.financetracker.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.financetracker.util.MoneyFormat

/**
 * Renders a formatted amount with its currency symbol picked out in its own colour.
 *
 * Set against the digits, the symbol stops reading as the leading digit. "₴1,234.50" is easy
 * to misread at a glance as an amount a third larger than it is, because a symbol the eye has
 * to parse is indistinguishable from one written in the same weight and colour.
 *
 * The cost is one [SpanStyle] on a single [Text]: no extra composable node, no second layout
 * pass, and the annotated string is remembered so it is not rebuilt on every recomposition.
 *
 * @param symbolColor defaults to a mustard that meets contrast against the usual surfaces. It
 * is a parameter rather than being derived, because the balance card draws on a filled
 * background where a dark mustard would be unreadable.
 */
@Composable
fun MoneyAmount(
    amount: Double,
    currencyCode: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = LocalContentColor.current,
    fontWeight: FontWeight? = null,
    prefix: String = "",
    symbolColor: Color = defaultSymbolColor()
) {
    val text = MoneyFormat.text(amount, currencyCode)
    val annotated = remember(text, prefix, symbolColor) {
        buildAnnotatedString {
            append(prefix)
            append(text.prefix)
            if (text.symbol.isNotEmpty()) {
                withStyle(SpanStyle(color = symbolColor)) { append(text.symbol) }
            }
            append(text.suffix)
        }
    }
    Text(
        text = annotated,
        modifier = modifier,
        style = style,
        color = color,
        fontWeight = fontWeight
    )
}

/**
 * Mustard, darkened for a light surface and brightened for a dark one.
 *
 * Not the pure yellow it looks like it should be: yellow on white sits near 1.7:1 contrast
 * and cannot be read, so the light-theme value is a dark mustard near 4.9:1 instead. The
 * brightness of the idea is kept, the legibility of it is what has to survive.
 */
@Composable
private fun defaultSymbolColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFFF2C744) else Color(0xFF8A6D00)
