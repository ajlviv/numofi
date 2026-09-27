package com.financetracker.data.statement

import com.financetracker.model.BankCode

/**
 * Works out which bank produced a parsed statement, from the column titles it uses.
 *
 * This is a convenience, not an authority: the caller always lets the user override the
 * answer, and an unrecognised file resolves to null rather than defaulting to the first
 * entry. Filing a statement under the wrong bank would quietly corrupt the one thing
 * these filters exist to show.
 */
object BankDetector {

    /**
     * Column titles unique to one bank's export.
     *
     * Any single entry is enough to identify the bank, so each has to be distinctive on
     * its own. "Опис операції" cannot be used for that: both the Ukrsibbank and PrivatBank
     * exports carry it, and a table with only that column says nothing about who sent it.
     */
    private val MARKERS = mapOf(
        BankCode.UKRSIBBANK to setOf("сумаввалютірахунку"),
        BankCode.PRIVATBANK to setOf("валютатранзакції", "сумаввалютітранзакції")
    )

    /**
     * How many rows to consider, counted from the top.
     *
     * The header is not always the first row with anything in it. The Ukrsibbank grid
     * prepends a header of its own making, but a spreadsheet export carries the file's own
     * header wherever the bank chose to put it, often under a title. The markers are long
     * enough multi-word titles that scanning data rows cannot match one by accident.
     */
    private const val HEADER_SEARCH_DEPTH = 25

    fun detect(table: List<List<String>>): String? =
        table.take(HEADER_SEARCH_DEPTH).firstNotNullOfOrNull(::match)

    private fun match(row: List<String>): String? {
        val titles = row.map { it.lowercase().filter(Char::isLetter) }.toSet()
        return MARKERS.entries.firstOrNull { (_, markers) -> markers.any { it in titles } }?.key
    }
}
