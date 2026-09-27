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
        val format = NumberFormat.getCurrencyInstance(Locale.getDefault())
        val currency = currencyOf(currencyCode)
        if (currency != null) format.currency = currency
        format.maximumFractionDigits = format.currency.defaultFractionDigits
        format.minimumFractionDigits = format.currency.defaultFractionDigits
        return format.format(amount)
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
