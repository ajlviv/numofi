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
 * the decimal separator, and the decision is made on **how wide the groups are** rather than on
 * how many separators there are:
 *
 * - a separator followed by three digits, with one to three digits before it, is grouping
 *   ("12,345" is twelve thousand three hundred and forty-five);
 * - any other single separator is the decimal point ("1234,56", "1,23", "0,5");
 * - both kinds present, the rightmost is the decimal point and the other was grouping
 *   ("1.234,56").
 *
 * Grouping is then *checked*, not assumed: the first group is one to three digits and every one
 * after it is exactly three, or the text is refused. "12,50,50" is not a number anybody typed.
 *
 * **Why three digits means grouping.** The earlier rule was that one separator was always a
 * decimal point, which made "12,345" parse as 12.345 — a thousandfold error on the most ordinary
 * input these forms take, on a field whose whole purpose is a Ukrainian decimal comma. Grouping
 * is the far likelier reading of three digits, and the one that costs nothing when guessed
 * wrong, because a unit price is written with two decimals here and a thousand separator is
 * written every day. That is a judgement about this app's amounts, not about numbers: it is
 * recorded here, and pinned in `AmountInputTest`, so it reads as a decision rather than as an
 * accident.
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
        val cleaned = text?.filterNot { it in GROUPING }?.trim() ?: return null
        if (cleaned.isEmpty()) return null

        // A leading sign belongs to the number, not to any group, and is carried through
        // separately so the width rules below never see it. Without this, "-12,345" would be
        // read as grouping of a group that starts with a minus.
        val negative = cleaned.startsWith('-')
        val body = cleaned.removePrefix("-").removePrefix("+")
        if (body.isEmpty()) return null

        val commas = body.count { it == ',' }
        val dots = body.count { it == '.' }
        // Bounded on digits rather than on separators: "12,345,678,901" is four separators and
        // a perfectly ordinary amount, whereas a group width that does not add up is caught by
        // `hasValidGroups` below and refused there.
        if (body.count { it.isDigit() } > MAX_GROUPED_DIGITS) return null
        // Anything that is not a digit or a separator is not an amount. Checked here rather than
        // left to the parse below, so that "12,50,50abc" is refused for what it is instead of
        // failing on the letters.
        if (!body.all { it.isDigit() || it == ',' || it == '.' }) return null

        val normalized = when {
            commas == 0 && dots == 0 -> body

            // Both kinds: the rightmost separator is the decimal point and the other was
            // grouping, because that is what a reader's eye does with "1.234,56".
            commas > 0 && dots > 0 -> {
                val decimalMark = if (body.lastIndexOf(',') > body.lastIndexOf('.')) ',' else '.'
                val groupingMark = if (decimalMark == ',') '.' else ','
                // Split on the original text first: the decimal mark has to be located before the
                // grouping mark is removed, or its position no longer means anything. The group
                // widths are checked while the marks are still there, because "1.23,456" is only
                // distinguishable from "123,456" before the grouping mark is dropped.
                val integerPart = body.substringBefore(decimalMark)
                if (!hasValidGroups(integerPart, groupingMark)) return null
                val fractionPart = body.substringAfter(decimalMark)
                if (!fractionPart.all { it.isDigit() }) return null
                integerPart.replace(groupingMark.toString(), "") + "." + fractionPart
            }

            else -> {
                // Whichever kind is actually here, which is the only one: this branch is
                // reached only when both are not present. Reading it off the count instead
                // would pick a mark the text does not contain.
                val mark = if (commas > 0) ',' else '.'
                // One kind throughout. The single case is the ambiguous one and is decided by the
                // width of what follows; more than one of the same kind is always grouping.
                if (commas + dots == 1 && !isThousandsGroup(body, mark)) {
                    body.replace(mark, '.')
                } else {
                    if (!hasValidGroups(body, mark)) return null
                    body.replace(mark.toString(), "")
                }
            }
        }

        val value = normalized.toDoubleOrNull() ?: return null
        return if (negative) -value else value
    }

    /**
     * Whether a lone [mark] in [body] is a thousands separator rather than a decimal point.
     *
     * A thousands group is one to three digits followed by exactly three, so this asks whether
     * the mark sits in that position. A trailing mark has nothing after it and is a decimal
     * point being typed.
     */
    private fun isThousandsGroup(body: String, mark: Char): Boolean {
        val at = body.indexOf(mark)
        val before = body.substring(0, at)
        val after = body.substring(at + 1)
        return before.length in 1..3 && after.length == GROUP_WIDTH
    }

    /**
     * Whether [text] is a well-formed grouped integer, with [mark] as its grouping separator.
     *
     * The first group may be one to three digits — "1,234,567" and "123,456,789" are both
     * written — and every group after it must be exactly three. Checked before the separators
     * are dropped, because that is the only point at which "1.23,456" and "123,456" are
     * distinguishable. Anything else was not typed as a grouped number, and reading it as one
 * invents digits the user never entered.
 */
private fun hasValidGroups(text: String, mark: Char): Boolean {
    if (text.isEmpty()) return false
    val groups = text.split(mark)
    if (groups.first().length !in 1..GROUP_WIDTH) return false
    if (!groups.first().all { it.isDigit() }) return false
    return groups.drop(1).all { it.length == GROUP_WIDTH && it.all(Char::isDigit) }
}

private const val GROUP_WIDTH = 3

/** One to three digits then any number of three-digit groups: more than any real amount. */
private const val MAX_GROUPED_DIGITS = 15
}