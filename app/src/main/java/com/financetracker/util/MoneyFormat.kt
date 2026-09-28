package com.financetracker.util

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Formats amounts in the currency the transaction is actually denominated in.
 *
 * [NumberFormat.getCurrencyInstance] picks its currency from the device locale, which
 * renders a UAH amount as `$12.00` on a phone set to en-US. Every amount carries its own
 * ISO 4217 code, so the code is passed explicitly here.
 */
object MoneyFormat {

    /**
     * Only for rows written before the currency column existed, and only if the device
     * reports no usable currency at all.
     */
    private const val FALLBACK = "USD"

    /** Used only when the device reports no usable currency at all. */
    private const val DEFAULT_FRACTION_DIGITS = 2

    /** Keeps the symbol on the same line as the amount it belongs to. */
    private val NBSP: Char = 0xA0.toChar()

    /**
     * Currencies whose native symbol the JDK does not know for an English locale.
     *
     * `Currency.getSymbol` renders UAH as the bare letters "UAH" on an en-US device, because
     * CLDR has no symbol for that pair, but a Ukrainian user expects "₴". Only the entries
     * where the JDK genuinely comes up empty are listed; every other currency uses whatever
     * the JDK reports, so this cannot drift from [format].
     */
    private val NATIVE_SYMBOLS = mapOf("UAH" to "₴")

    fun format(amount: Double, currencyCode: String?): String {
        val digits = currencyOf(currencyCode)?.defaultFractionDigits ?: DEFAULT_FRACTION_DIGITS
        val number = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }.format(amount)
        val symbol = symbolOf(currencyCode)
        // The symbol trails the amount in every locale, rather than taking the position the
        // JDK's currency pattern would give it. A leading "₴" reads as a digit at a glance,
        // and one that moves with the device language is one more thing to relearn. Grouping
        // and the decimal separator still come from the locale, so the same amount is still
        // "1 234,50" in Kyiv and "1,234.50" on an en-US phone.
        //
        // The separator is a non-breaking space so the symbol cannot wrap onto the next line
        // by itself, which on a narrow row would leave a lone "₴" under the amount.
        return if (symbol.isBlank()) number else "$number$NBSP$symbol"
    }

    /**
     * The symbol for the currency an amount is actually denominated in, for use where
     * there is no room for a full formatted amount.
     *
     * Always returns something printable: a code the device does not know, or a row written
     * before the currency column existed, degrades to the device's own currency rather than
     * rendering an empty string.
     */
    fun symbolOf(currencyCode: String?): String {
        val code = normalize(currencyCode)
        if (code == null) return deviceSymbol() ?: FALLBACK
        NATIVE_SYMBOLS[code]?.let { return it }
        val currency = runCatching { Currency.getInstance(code) }.getOrNull()
            ?: return deviceSymbol() ?: code
        return runCatching { currency.getSymbol(Locale.getDefault()) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: code
    }

    /**
     * A formatted amount taken apart so the currency symbol can be styled on its own.
     *
     * The parts sum back to the formatted string, which is what makes this safe to derive
     * rather than assemble: nothing here decides where a symbol belongs or how a number is
     * grouped, both of which the JDK already does correctly per locale.
     */
    data class Text(
        val prefix: String,
        val symbol: String,
        val suffix: String
    ) {
        val plain: String get() = prefix + symbol + suffix
    }

    /**
     * The formatted amount split around its currency symbol.
     *
     * Placement is locale-dependent and is not second-guessed here: en-US renders
     * "₴1,234.50" and uk-UA renders "1 234,50 ₴", so the symbol is located in whatever
     * [format] produced. A symbol the formatter did not actually use leaves [Text.symbol]
     * empty and the amount renders as plain text, so a mismatch degrades instead of throwing.
     */
    fun text(amount: Double, currencyCode: String?): Text {
        val formatted = format(amount, currencyCode)
        val symbol = symbolOf(currencyCode)
        val at = if (symbol.isEmpty()) -1 else formatted.indexOf(symbol)
        if (at < 0) return Text(formatted, "", "")
        return Text(
            prefix = formatted.substring(0, at),
            symbol = symbol,
            suffix = formatted.substring(at + symbol.length)
        )
    }

    private fun normalize(currencyCode: String?): String? =
        currencyCode?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }

    private fun deviceSymbol(): String? =
        runCatching { Currency.getInstance(Locale.getDefault()).getSymbol(Locale.getDefault()) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun currencyOf(currencyCode: String?): Currency? {
        val code = normalize(currencyCode)
        return code?.let { runCatching { Currency.getInstance(it) }.getOrNull() }
            ?: runCatching { Currency.getInstance(Locale.getDefault()) }.getOrNull()
            ?: runCatching { Currency.getInstance(FALLBACK) }.getOrNull()
    }
}
