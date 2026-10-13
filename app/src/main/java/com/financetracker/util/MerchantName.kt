package com.financetracker.util

import java.util.Locale

/**
 * Turns a raw transaction title into a merchant name a person can read.
 *
 * Titles are stored verbatim from bank statements, where the description field is a
 * payment *purpose*: boilerplate wording (`ОПЛАТА ЗА ТОВАРІВ/ПОСЛУГ`), document codes
 * (`C3000135`) and the merchant scattered somewhere inside it. The cleanup therefore runs
 * at render time and never rewrites a stored row — the same rule as [CategoryLabel]
 * applying labels only at display time, and the reason this needs no migration.
 *
 * The other half of the problem is a title that has no merchant in it at all: a
 * hand-typed `три` or `ууу`. [isMeaningful] rejects those so the caller can fall back to
 * the category rather than print junk as the one line the user scans for.
 *
 * Every function here is pure and idempotent — `clean` on its own output changes
 * nothing — which is what makes it a plain JVM test rather than a screen concern.
 */
object MerchantName {

    /**
     * Statement purpose wording, matched as whole phrases so that a shop actually named
     * "Оплата" is not eaten by the pattern meant for the boilerplate around it.
     *
     * Letter runs use `\p{L}` rather than `\w`: Java's `\w` is ASCII-only, so `товар\w*`
     * would stop before the `ів` in `товарів` and never reach the `/послуг` that makes
     * the phrase matchable.
     *
     * Longest/most specific first — `оплата за товари/послуги` must be consumed before
     * the bare `товари/послуги` can leave its `оплата за` hanging.
     */
    private val BOILERPLATE = listOf(
        """покупк[аи]\s+товар[\p{L}]*\s+та\s+послуг[\p{L}]*""",
        """оплат[аи]\s+за\s+товар[\p{L}]*\s*/\s*послуг[\p{L}]*""",
        """оплат[аи]\s+за\s+товар[\p{L}]*\s+та\s+послуг[\p{L}]*""",
        """оплат[аи]\s+товар[\p{L}]*\s*/\s*послуг[\p{L}]*""",
        """оплат[аи]\s+послуг(?![\p{L}])""",
        """товар[\p{L}]*\s+та\s+послуг[\p{L}]*""",
        """товар[\p{L}]*\s*/\s*послуг[\p{L}]*""",
        """переказ\s+за\s+призначенням""",
        """payment\s+for\s+goods\s*/?\s+services""",
        """purchase\s+of\s+goods\s+and\s+services""",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    /**
     * Document codes rather than content: a letter run against five or more digits
     * (`C3000135`), a `№`-prefixed number, and any five-digit run on its own — which is
     * a reference number in this position, because a merchant name made of five digits
     * has already failed [isMeaningful] anyway.
     */
    private val CODES = listOf(
        Regex("""№\s*\d+"""),
        Regex("""[\p{L}]\d{5,}"""),
        Regex("""\b\d{5,}\b"""),
    )

    private val SEGMENT_SEPARATORS = Regex("""[/;\n\r|]""")

    /** Punctuation and separators that wrap a name rather than belong to it. */
    private val WRAPPING = Regex(
        """^[.,;:!?()"'“”\[\]\-–—/|\s]+|[.,;:!?()"'“”\[\]\-–—/|\s]+$"""
    )

    private val WHITESPACE = Regex("""\s+""")

    /**
     * Words that are real words but never a name in this position. Kept deliberately
     * short: this is a junk list, and every entry added to it is a word a real merchant
     * could one day be called.
     */
    private val JUNK = setOf("три", "два", "тест", "test", "n/a", "none", "null", "unknown")

    /**
     * @return the merchant as written inside the title, or null when nothing but
     * boilerplate, codes and separators was there.
     */
    fun clean(raw: String): String? {
        var value = raw.replace(WHITESPACE, " ")
        BOILERPLATE.forEach { value = it.replace(value, " ") }
        CODES.forEach { value = it.replace(value, " ") }
        value = value.replace(WHITESPACE, " ")
        return value
            .split(SEGMENT_SEPARATORS)
            .map { it.replace(WRAPPING, "").trim() }
            .firstOrNull { it.isNotBlank() }
    }

    /**
     * Whether a cleaned candidate can stand in as a display name.
     *
     * Three rules, in the order they reject: fewer than three letters means there was no
     * name there (`ab`, `12345`, `n/a`); a single repeated letter is typing stalling, not
     * naming (`ууу`); and the [JUNK] list catches the short words a person types instead
     * of a title. Three letters is the floor rather than four because `АТБ` and `AKS`
     * are exactly three and are names.
     */
    fun isMeaningful(candidate: String): Boolean {
        val value = candidate.trim()
        if (value.count { it.isLetter() } < 3) return false
        val lower = value.lowercase(Locale.ROOT)
        if (lower in JUNK) return false
        if (lower.filter { it.isLetter() }.toSet().size < 2) return false
        return true
    }

    /**
     * The name to print on a row: the cleaned merchant when there is one, otherwise the
     * category — lazily, so a composable can resolve its `@StringRes` only on the path
     * that actually needs it.
     */
    fun displayName(rawTitle: String, categoryLabel: () -> String): String =
        clean(rawTitle)?.takeIf { isMeaningful(it) } ?: categoryLabel()
}
