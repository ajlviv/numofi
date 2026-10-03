package com.financetracker.util

/**
 * Turns what somebody typed on a numeric keypad into a number.
 *
 * Three forms take an amount — the transaction form, the bond trade form and the schedule form —
 * and two of them called `toDoubleOrNull()` on the raw text. That is the JDK's idea of a number,
 * not a phone's: a Ukrainian decimal keyboard offers a comma, so "1234,56" was refused with
 * nothing on screen saying why, while the same amount typed with a full stop was accepted. The
 * bond form had grown its own `replace(',', '.')` to work around it, which is the shape the
 * second copy was going to take.
 *
 * Grouping is dropped rather than parsed: a space (or a non-breaking space) groups digits in the
 * Ukrainian format ("1 234,56") and a comma or full stop groups them in the en-US one
 * ("1,234.56"), and none of those marks carries a value [Double] can hold. What survives decides
 * the decimal separator:
 *
 * - one separator of one kind is the decimal point ("1234,56", "1234.56");
 * - both kinds present, the rightmost is the decimal point and the other was grouping
 *   ("1.234,56");
 * - one kind present more than once, they were all grouping ("1,234,567").
 *
 * The one genuinely ambiguous case is a single separator followed by exactly three digits:
 * "1.234" is either 1.234 or 1234, depending on which convention the typist had in mind. It is
 * read as a decimal point, because that is what a single separator meant before this existed and
 * a price of 1.234 is a real thing to type.
 *
 * The cost of that last rule is that repeated separators are taken as grouping without checking
 * the group widths, so "12,50,50" parses to 125050 rather than being refused. Validating the
 * widths is a stricter rule than this function claims; the looser one is recorded in
 * `AmountInputTest` so the behaviour is pinned rather than incidental.
 */
object AmountInput {

    /**
     * Marks that group digits, all three kinds of space: ordinary, non-breaking (U+00A0) and
     * narrow non-breaking (U+202F). Which one arrives depends on the keyboard, and the app's own
     * [MoneyFormat] emits the first of them.
     */
    private const val GROUPING = " \u00A0\u202F"

    /**
     * The amount the text denotes, or null when it denotes nothing.
     *
     * Null is "not a number", not "zero": the forms refuse a save on null and accept an entered
     * zero as a quantity they will refuse for their own reasons, so the two must not be confused
     * here.
     */
    fun parse(text: String?): Double? {
        val cleaned = text?.filterNot { it in GROUPING }.orEmpty()
        if (cleaned.isEmpty()) return null

        val commas = cleaned.count { it == ',' }
        val dots = cleaned.count { it == '.' }
        val normalized = when {
            commas == 0 && dots == 0 -> cleaned
            commas == 1 && dots == 0 -> cleaned.replace(',', '.')
            commas == 0 && dots == 1 -> cleaned
            // Both kinds, or one kind more than once: the rightmost separator is the decimal
            // point and the rest were grouping. Position is what decides it, because that is
            // what a reader's eye does with "1.234,56".
            commas > 0 && dots > 0 -> {
                val decimalAt = maxOf(cleaned.lastIndexOf(','), cleaned.lastIndexOf('.'))
                val decimalMark = cleaned[decimalAt]
                val groupingMark = if (decimalMark == ',') '.' else ','
                cleaned
                    .replace(groupingMark.toString(), "")
                    .replace(decimalMark, '.')
            }
            commas == 0 -> cleaned.replace(".", "")
            else -> cleaned.replace(",", "")
        }
        return normalized.toDoubleOrNull()
    }
}