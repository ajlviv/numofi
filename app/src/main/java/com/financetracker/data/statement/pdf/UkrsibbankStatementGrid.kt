package com.financetracker.data.statement.pdf

import java.math.BigDecimal

/**
 * Turns a positioned-text dump of a Ukrsibbank card-account statement into the plain
 * table the existing [com.financetracker.data.statement.StatementParser] understands,
 * so dates, decimal parsing and duplicate detection stay in one place.
 *
 * ### Why this is not a text-scraper
 * A PDF stores glyphs at coordinates, not table cells. Reading the text linearly
 * interleaves columns: in the sample statement the card table's authorisation codes,
 * merchant names and both amount columns collapse into unreadable lines. Rows are
 * recovered from geometry instead, using the column bands the statement's header declares.
 *
 * ### The two amount columns
 * Statements carry `Сума` (the operation, in the operation currency) *and*
 * `Сума в валюті рахунку` (the same movement in the account currency). For a foreign
 * purchase these differ by the exchange rate - the sample shows USD -142.80 against
 * UAH -6 378.88, a 45x difference. The account-currency column is used, because the
 * ledger and dashboard are kept in the account currency, which is also what the bank
 * sync path reports. The operation amount is not silently dropped: each row is labelled
 * with the currency its stored amount is actually denominated in.
 *
 * Sections that are not settled movements are skipped on purpose: `Поточні блокування`
 * holds pending authorisations that would double-count once they settle, and `Разом`
 * lines are per-table totals.
 */
object UkrsibbankStatementGrid {

    /** The normalised table, or an empty list when the file is not a statement. */
    fun build(source: PdfCharSource): List<List<String>> {
        val pages = PositionedText.pages(source.read())
        if (pages.isEmpty()) return emptyList()

        // PDFBox reports y in the page's own direction, which is not always bottom-up:
        // a rotated page makes it run top-down, as this statement does. Assuming an
        // orientation silently detaches every wrapped description, so both readings are
        // tried and the one that actually yields rows wins. A table title gates row
        // collection, so the wrong reading produces almost nothing.
        val bottomUp = pages.flatten()
        val topDown = pages.map { it.reversed() }.flatten()

        val forward = attempt(bottomUp)
        val reverse = attempt(topDown)
        return if (reverse.size > forward.size) reverse else forward
    }

    private fun attempt(lines: List<PdfLine>): List<List<String>> =
        Builder(findAccountCurrency(lines)).parse(lines)

    // -------------------------------------------------------------------- utilities

    /**
     * The account currency, taken from the balance line above the tables ("71 417.79 UAH").
     *
     * The code often shares a run with the figure it belongs to, so the trailing code is
     * matched inside the run rather than as a separate one. Requiring a digit in front of
     * it keeps tariff-plan wording such as "ALL INCLUSIVE DE LUXE" from being read as a
     * currency.
     */
    private fun findAccountCurrency(lines: List<PdfLine>): String {
        val firstTable = lines.indexOfFirst {
            val t = it.text
            t.contains(ACCOUNT_TABLE) || t.contains(CARD_TABLE)
        }
        val above = if (firstTable >= 0) lines.subList(0, firstTable) else lines

        for (line in above) {
            for (run in line.runs) {
                TRAILING_CURRENCY.find(run.text)?.let { return it.groupValues[1] }
            }
        }
        return FALLBACK_ACCOUNT_CURRENCY
    }

    /** Dot-underline rules are drawing artefacts rather than text. */
    private fun isSeparator(text: String): Boolean {
        val visible = text.filterNot { it.isWhitespace() }
        if (visible.isEmpty()) return true
        return visible.count { it == '_' } >= visible.length * SEPARATOR_RATIO
    }

    private fun decimal(raw: String): BigDecimal? =
        raw.replace(" ", "")
            .replace(" ", "")
            .replace(",", ".")
            .toBigDecimalOrNull()

    // ------------------------------------------------------------------- the parser

    private class Builder(private val accountCurrency: String) {

        private val rows = mutableListOf<List<String>>()
        private var insideTable = false
        private var insideIgnoredSection = false

        /**
         * The masked number from the banner above the rows being read, e.g.
         * "535129****5783". Cleared only when a *different* table starts, because the
         * statement repeats a table's title at every page break.
         */
        private var sectionCard: String? = null

        /** Which of the two tables is being read, so a repeated title is not a new section. */
        private var currentTable: Table? = null

        /**
         * The row currently being assembled. A statement prints a long purpose across the
         * lines *below* its amounts, so a row is only emitted once the next one starts;
         * emitting on sight would truncate every wrapped description.
         */
        private var pending: Row? = null

        fun parse(lines: List<PdfLine>): List<List<String>> {
            lines.forEach(::consume)
            flush()
            return if (rows.isEmpty()) emptyList() else listOf(HEADER) + rows
        }

        private fun consume(line: PdfLine) {
            val text = line.text
            // Dot rules are decoration; they neither start nor end a row.
            if (isSeparator(text)) return

            when {
                text.contains(ACCOUNT_TABLE) || text.contains(CARD_TABLE) -> {
                    flush()
                    insideTable = true
                    insideIgnoredSection = false
                    val table = if (text.contains(CARD_TABLE)) Table.CARD else Table.ACCOUNT
                    // A table title is reprinted at every page break, so treating each one
                    // as a fresh section would drop the card banner halfway down a long
                    // card table. Only a genuinely different table ends the section. The
                    // account table reports account-level movements and has no banner.
                    if (table != currentTable) sectionCard = null
                    currentTable = table
                    return
                }
                text.contains(HOLDS_SECTION) -> {
                    flush()
                    insideIgnoredSection = true
                    return
                }
            }
            if (!insideTable || insideIgnoredSection) return
            if (text.trimStart().startsWith(TOTAL_PREFIX)) {
                flush()
                return
            }

            val dateRun = line.runs.firstOrNull { it.x < DATE_END }
            if (dateRun != null) {
                // Column headers and per-card banners ("MASTERCARD PLATINUM 535129****5783")
                // also begin in the date band, so anything that is not a date closes the
                // row above without starting one.
                flush()
                if (DATE.matches(dateRun.text)) {
                    pending = rowFrom(line, dateRun.text)
                } else {
                    // A banner names the card for every row that follows it in this section.
                    MASKED_PAN.find(text)?.let { sectionCard = it.groupValues[1] }
                }
                return
            }

            // Nothing in the date band: a wrapped continuation of the row above.
            val current = pending ?: return
            continuationOf(line)?.let { current.description.append(' ').append(it) }
        }

        private fun flush() {
            val row = pending ?: return
            pending = null

            val amount = row.accountAmount ?: row.operationAmount ?: return
            val currency = when {
                row.operationAmount != null && row.operationAmount == amount ->
                    row.operationCurrency ?: accountCurrency
                else -> accountCurrency
            }
            val description = row.description.toString().replace(WHITESPACE, " ").trim()
            if (description.isEmpty()) return
            rows += listOf(row.date, description, amount.toPlainString(), currency, row.card.orEmpty())
        }

        private fun continuationOf(line: PdfLine): String? = line.runs
            .filter { it.x >= SETTLED_DATE_END && it.x < MIDDLE_END }
            .filterNot { AUTH_CODE.matches(it.text) }
            .joinToString(" ") { it.text }
            .takeIf { it.isNotBlank() }

        private fun rowFrom(line: PdfLine, date: String): Row {
            var operationCurrency: String? = null
            var operationAmount: BigDecimal? = null
            var accountAmount: BigDecimal? = null
            val description = StringBuilder()

            for (run in line.runs) {
                when {
                    run.x < SETTLED_DATE_END -> Unit // operation and settlement dates
                    run.x < MIDDLE_END ->
                        // The card table's authorisation code shares the description band.
                        if (!AUTH_CODE.matches(run.text)) {
                            if (description.isNotEmpty()) description.append(' ')
                            description.append(run.text)
                        }
                    run.x < CURRENCY_END ->
                        operationCurrency = run.text.takeIf { CURRENCY_CODE.matches(it) }
                    run.x < OPERATION_AMOUNT_END -> operationAmount = decimal(run.text)
                    else -> accountAmount = decimal(run.text)
                }
            }

            return Row(
                date,
                description,
                operationCurrency,
                operationAmount,
                accountAmount,
                sectionCard
            )
        }

    }

    private enum class Table { ACCOUNT, CARD }

    private class Row(
        val date: String,
        val description: StringBuilder,
        val operationCurrency: String?,
        val operationAmount: BigDecimal?,
        val accountAmount: BigDecimal?,
        /** The card in force where this row was printed, or null for account movements. */
        val card: String?
    )

    // ------------------------------------------------------------------------ bands

    /**
     * Column bands in points, measured from the statement's own header row. Both
     * transaction tables place their date, currency and amount columns identically; only
     * the description shifts, because the card table adds an authorisation-code column.
     */
    private const val DATE_END = 85f
    private const val SETTLED_DATE_END = 138f
    private const val MIDDLE_END = 418f
    private const val CURRENCY_END = 450f
    private const val OPERATION_AMOUNT_END = 511f

    private const val ACCOUNT_TABLE = "Операції за картковим рахунком"
    private const val CARD_TABLE = "Операції за платіжними картками"
    private const val HOLDS_SECTION = "Поточні блокування"
    private const val TOTAL_PREFIX = "Разом"
    private const val FALLBACK_ACCOUNT_CURRENCY = "UAH"
    private const val SEPARATOR_RATIO = 0.35

    private val DATE = Regex("""^\d{2}\.\d{2}\.\d{4}$""")
    private val CURRENCY_CODE = Regex("""^[A-Z]{3}$""")
    private val AUTH_CODE = Regex("""^\d{6}$""")
    private val TRAILING_CURRENCY = Regex("""[\d.,]\s*([A-Z]{3})\s*$""")
    private val WHITESPACE = Regex("""\s+""")

    private val HEADER = listOf(
        "Дата операції", "Опис операції", "Сума в валюті рахунку", "Валюта", "Картка"
    )

    /**
     * The masked number inside a card banner. Deliberately anchored on the asterisks: a
     * loose digit run would also match dates and amounts, and a wrong card label is worse
     * than no label at all.
     */
    private val MASKED_PAN = Regex("""(\d{4,6}\*{2,}\d{2,4})""")
}
