package com.financetracker.model

/**
 * Which bank a transaction came from.
 *
 * A single column rather than a relation: the set of banks is closed and tiny, and every
 * consumer needs a label rather than a joined row. A null code means the row was typed by
 * hand, which is the only way to create a transaction with no bank behind it.
 */
object BankCode {

    const val MONOBANK = "mo"
    const val UKRSIBBANK = "uk"
    const val PRIVATBANK = "pb"

    /** Offered in the import preview and the filter chips, in this order. */
    val CHOICES = listOf(MONOBANK, UKRSIBBANK, PRIVATBANK)

    fun label(code: String?): String = when (code) {
        MONOBANK -> "Monobank"
        UKRSIBBANK -> "Ukrsibbank"
        PRIVATBANK -> "PrivatBank"
        null -> "Manual"
        // Never silently blank an unknown code: it would be indistinguishable from
        // "Manual", which is a real, different thing.
        else -> code
    }

    /**
     * Canonicalises a code that came from a file or a filter chip. Anything unrecognised
     * becomes null, so a typo or a stale value cannot conjure a bank of its own.
     */
    fun normalize(raw: String?): String? =
        raw?.trim()?.lowercase()?.takeIf { it in CHOICES }
}
