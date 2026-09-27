package com.financetracker.data.statement

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor

/**
 * Turns a raw statement table into [StatementRow]s.
 *
 * Bank exports are not standardised, so columns are located by matching the header row
 * against known aliases (Ukrainian, Russian and English) rather than by position.
 * Date-only values are read in the device's timezone, which is what a wall-clock date
 * on a statement means.
 */
object StatementParser {

    private const val MAX_HEADER_SCAN_ROWS = 25

    fun parse(table: List<List<String>>): StatementParseOutcome {
        if (table.none { it.any { cell -> cell.isNotBlank() } }) {
            return StatementParseOutcome.Failed(StatementImportFailure.EMPTY_FILE)
        }

        val header = findHeader(table)
            ?: return StatementParseOutcome.Failed(StatementImportFailure.NO_HEADER_ROW, sampleOf(table))

        if (header.map.date == null) {
            return StatementParseOutcome.Failed(
                StatementImportFailure.NO_DATE_COLUMN,
                "Header read as: ${header.cells.joinToString(" | ")}"
            )
        }
        if (header.map.amount == null && header.map.debit == null && header.map.credit == null) {
            return StatementParseOutcome.Failed(
                StatementImportFailure.NO_AMOUNT_COLUMN,
                "Header read as: ${header.cells.joinToString(" | ")}"
            )
        }

        val rows = mutableListOf<StatementRow>()
        var skipped = 0

        for (i in header.index + 1 until table.size) {
            val cells = table[i]
            if (cells.all { it.isBlank() }) continue

            val row = toStatementRow(cells, header, i)
            if (row == null) skipped++ else rows.add(row)
        }

        if (rows.isEmpty()) return StatementParseOutcome.Failed(StatementImportFailure.EMPTY_FILE)

        return StatementParseOutcome.Parsed(
            StatementParseResult(
                rows = rows,
                skippedRows = skipped,
                columns = DetectedColumns(
                    headerRowIndex = header.index,
                    date = header.map.date!!,
                    description = header.map.description,
                    amount = header.map.amount ?: header.map.credit ?: header.map.debit ?: 0,
                    currency = header.map.currency,
                    balance = header.map.balance,
                    amountsAreMinorUnits = header.map.minorUnits
                )
            )
        )
    }

    private fun toStatementRow(cells: List<String>, header: Header, index: Int): StatementRow? {
        val date = header.map.date?.let { cellAt(cells, it) }?.let(::parseDate) ?: return null
        val amount = resolveAmount(cells, header) ?: return null

        return StatementRow(
            timestamp = date,
            description = header.map.description
                ?.let { cellAt(cells, it) }
                .orEmpty(),
            amount = if (header.map.minorUnits) amount.movePointLeft(SMALLEST_UNIT_PLACES) else amount,
            currencyCode = header.map.currency
                ?.let { cellAt(cells, it) }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.uppercase(),
            balance = header.map.balance
                ?.let { cellAt(cells, it) }
                ?.let(::parseDecimal)
                ?.let { if (header.map.minorUnits) it.movePointLeft(SMALLEST_UNIT_PLACES) else it },
            cardLabel = header.map.card
                ?.let { cellAt(cells, it) }
                ?.trim()
                ?.takeIf { it.isNotEmpty() },
            rowIndex = index
        )
    }

    /**
     * Prefers a single signed amount column; otherwise nets the debit/credit pair that
     * some statements use instead.
     */
    private fun resolveAmount(cells: List<String>, header: Header): BigDecimal? {
        header.map.amount?.let { cellAt(cells, it) }
            ?.let(::parseDecimal)
            ?.let { return it }

        val debit = header.map.debit?.let { cellAt(cells, it) }?.let(::parseDecimal)
        val credit = header.map.credit?.let { cellAt(cells, it) }?.let(::parseDecimal)
        if (debit == null && credit == null) return null
        return (credit ?: BigDecimal.ZERO).subtract(debit ?: BigDecimal.ZERO)
    }

    private fun cellAt(cells: List<String>, index: Int): String? =
        cells.getOrNull(index)?.takeIf { it.isNotBlank() }

    // ---------------------------------------------------------------- header lookup

    private class Header(
        val index: Int,
        val map: ColumnMap,
        val cells: List<String>
    )

    private data class ColumnMap(
        val date: Int?,
        val amount: Int?,
        val debit: Int?,
        val credit: Int?,
        val description: Int?,
        val currency: Int?,
        val balance: Int?,
        val card: Int?,
        val minorUnits: Boolean
    )

    /**
     * Scans the first rows for the best-matching header. Statements often start with a
     * title and account/period block before the real header.
     *
     * A row only has to look like a header (a date or an amount) to be considered, so
     * the caller can report a precise "no amount column" instead of a vague failure
     * when the date was found but the amount was not.
     */
    private fun findHeader(table: List<List<String>>): Header? {
        var best: Header? = null
        var bestScore = 0

        for (rowIndex in 0 until minOf(table.size, MAX_HEADER_SCAN_ROWS)) {
            val cells = table[rowIndex]
            val map = matchColumns(cells)
            val hasAmount = map.amount != null || (map.debit != null && map.credit != null)
            if (map.date == null && !hasAmount) continue

            val score = listOfNotNull(
                map.date, map.amount, map.description, map.currency, map.balance, map.debit, map.credit
            ).size
            if (score > bestScore) {
                bestScore = score
                best = Header(rowIndex, map, cells)
            }
        }
        return best
    }

    /** First few non-blank rows, for diagnostics when nothing looks like a header. */
    private fun sampleOf(table: List<List<String>>): String =
        table.take(3)
            .map { it.joinToString(" | ") { cell -> cell.ifBlank { "-" } } }
            .joinToString(" / ")

    private fun matchColumns(cells: List<String>): ColumnMap {
        var date: Int? = null
        var amount: Int? = null
        var debit: Int? = null
        var credit: Int? = null
        var description: Int? = null
        var currency: Int? = null
        var balance: Int? = null
        var card: Int? = null
        var minorUnits = false

        cells.forEachIndexed { index, cell ->
            val normalized = normalize(cell)
            if (normalized.isEmpty() || isRejected(normalized)) return@forEachIndexed

            when {
                best(date, DATE_ALIASES, normalized) -> date = index
                best(amount, AMOUNT_ALIASES, normalized) -> {
                    amount = index
                    if (mentionsSmallestUnit(normalized)) minorUnits = true
                }
                best(debit, DEBIT_ALIASES, normalized) -> {
                    debit = index
                    if (mentionsSmallestUnit(normalized)) minorUnits = true
                }
                best(credit, CREDIT_ALIASES, normalized) -> {
                    credit = index
                    if (mentionsSmallestUnit(normalized)) minorUnits = true
                }
                best(description, DESCRIPTION_ALIASES, normalized) -> description = index
                best(currency, CURRENCY_ALIASES, normalized) -> currency = index
                best(balance, BALANCE_ALIASES, normalized) -> balance = index
                best(card, CARD_ALIASES, normalized) -> card = index
            }
        }
        return ColumnMap(date, amount, debit, credit, description, currency, balance, card, minorUnits)
    }

    /** Exact alias match wins over substring match, so a narrow header beats a vague one. */
    private fun best(current: Int?, aliases: Set<String>, normalized: String): Boolean {
        if (current != null) return false
        return aliases.any { it == normalized } || aliases.any { it.length >= 4 && normalized.contains(it) }
    }

    /** Commission, tax and interest columns also contain "amount"-like words. */
    private fun isRejected(normalized: String): Boolean =
        REJECTED_FRAGMENTS.any { it.length >= 4 && normalized.contains(it) }

    private fun mentionsSmallestUnit(normalized: String): Boolean =
        SMALLEST_UNIT_HINTS.any { normalized.contains(it) }

    private fun normalize(cell: String): String =
        cell.lowercase(Locale.ROOT).filter { it.isLetter() }

    // ---------------------------------------------------------------- value parsing

    private val DATE_ALIASES = setOf(
        "дата", "date", "датаоперації", "датаоперации", "датавремя", "operationdate",
        "transactiondate", "даттранзакції", "time", "час"
    )

    private val AMOUNT_ALIASES = setOf(
        "сума", "amount", "сумаоперації", "сумаоперации", "transactionamount",
        "operationamount", "amountuah"
    )

    private val DEBIT_ALIASES = setOf(
        "дебет", "debit", "витрати", "списання", "зняття", "withdrawal", "expense"
    )
    private val CREDIT_ALIASES = setOf(
        "кредит", "credit", "надходження", "поповнення", "депозит", "deposit", "income"
    )

    private val DESCRIPTION_ALIASES = setOf(
        "опис", "description", "призначення", "назначение", "операція", "операция", "operation",
        "деталі", "details", "коментар", "comment", "note", "notes", "memo", "примітка",
        "назва", "title", "контрагент", "counterparty", "merchant", "отримувач", "получатель"
    )

    private val CURRENCY_ALIASES = setOf(
        "валюта", "currency", "кодвалюти", "currencycode"
    )

    private val BALANCE_ALIASES = setOf(
        "баланс", "balance", "остаток", "залишок", "closingbalance", "availablebalance"
    )

    /**
     * A card column is titled differently by every bank and only two are in evidence here.
     * Kept narrow on purpose: [REJECTED_FRAGMENTS] already blocks headers such as
     * "Номер картки", and a loose "card" match would collide with those.
     */
    private val CARD_ALIASES = setOf("картка", "карта", "card", "cardnumber")

    /**
     * Columns that read like the ones we want but carry different data. Kept specific
     * on purpose: a broad "card" or "code" fragment would also reject legitimate
     * headers such as "Код валюти".
     */
    private val REJECTED_FRAGMENTS = setOf(
        "комис", "коміс", "commission", "fee", "відсот", "процент", "interest",
        "налог", "tax", "взаимоо", "ліміт", "limit", "iban", "номеркартки", "otp"
    )

    private val SMALLEST_UNIT_HINTS = setOf("коп", "копій", "копі", "minor", "cent")

    /** Decimal places to shift when a statement reports amounts in kopecks. */
    private const val SMALLEST_UNIT_PLACES = 2

    private val DATE_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd",
        "dd.MM.yyyy HH:mm:ss",
        "dd.MM.yyyy HH:mm",
        "dd.MM.yyyy",
        "dd/MM/yyyy HH:mm:ss",
        "dd/MM/yyyy",
        "dd-MM-yyyy",
        "yyyy/MM/dd",
        "d MMM yyyy",
        "d MMMM yyyy",
        "MMM d, yyyy"
    )

    /**
     * Accepts an Excel serial date or one of the common textual layouts. Values without
     * a zone are wall-clock times, resolved in the device's timezone.
     */
    fun parseDate(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        text.toDoubleOrNull()?.let { serial ->
            if (serial in EXCEL_SERIAL_MIN..EXCEL_SERIAL_MAX) return fromExcelSerial(serial)
        }

        for (pattern in DATE_PATTERNS) {
            val formatter = DateTimeFormatter.ofPattern(pattern, Locale.ROOT)
            runCatching { return java.time.OffsetDateTime.parse(text, formatter).toInstant().toEpochMilli() }
            runCatching {
                return LocalDateTime.parse(text, formatter)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
            runCatching {
                return LocalDate.parse(text, formatter)
                    .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        }
        return null
    }

    /** Excel's day zero is 1899-12-30; the serial carries the time as a fraction. */
    private fun fromExcelSerial(serial: Double): Long {
        val days = floor(serial).toLong()
        val fraction = serial - floor(serial)
        val date = EXCEL_EPOCH.plusDays(days)
        val secondsOfDay = (fraction * 86_400).toLong()
        return date.atStartOfDay(ZoneId.systemDefault())
            .plusSeconds(secondsOfDay)
            .toInstant()
            .toEpochMilli()
    }

    private val EXCEL_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)
    private const val EXCEL_SERIAL_MIN = 20_000.0 // 1954
    private const val EXCEL_SERIAL_MAX = 80_000.0 // 2118

    /**
     * Tolerant decimal reader. Handles thousands separators, a comma used as the
     * decimal mark, currency symbols and parenthesised negatives.
     */
    fun parseDecimal(raw: String): BigDecimal? {
        var text = raw.trim()
            .replace('\u00A0', ' ') // non-breaking space
            .replace('\u202F', ' ') // narrow no-break space
            .replace('\u2009', ' ') // thin space
            .replace("−", "-") // minus sign
            .replace(" ", "")

        var negative = false
        if (text.startsWith("(") && text.endsWith(")")) {
            negative = true
            text = text.substring(1, text.length - 1)
        }
        if (text.startsWith("-")) {
            negative = true
            text = text.substring(1)
        }
        if (text.startsWith("+")) text = text.substring(1)

        text = text.filter { it.isDigit() || it == '.' || it == ',' }
        if (text.isEmpty()) return null

        val separator = maxOf(text.lastIndexOf('.'), text.lastIndexOf(','))
        val value: BigDecimal = if (separator < 0) {
            text.toBigDecimalOrNull() ?: return null
        } else {
            val whole = text.substring(0, separator).filter { it.isDigit() }
            val fraction = text.substring(separator + 1).filter { it.isDigit() }
            // A lone separator followed by exactly three digits is thousands grouping,
            // not a decimal part, for the two-decimal currencies these banks use.
            val isGrouping = fraction.length == 3 && text.count { it == '.' || it == ',' } == 1
            val integerPart = whole.ifEmpty { "0" }
            if (isGrouping) {
                integerPart.toBigDecimalOrNull() ?: return null
            } else if (fraction.isEmpty()) {
                integerPart.toBigDecimalOrNull() ?: return null
            } else {
                "$integerPart.$fraction".toBigDecimalOrNull() ?: return null
            }
        }
        return if (negative) value.negate() else value
    }
}
