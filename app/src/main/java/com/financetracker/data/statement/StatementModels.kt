package com.financetracker.data.statement

import java.math.BigDecimal

/**
 * One line from a bank statement, normalised into the same shape the sync engine
 * produces so both paths can share the import/dedup code.
 */
data class StatementRow(
    val timestamp: Long,
    val description: String,
    /** Signed: negative means money left the account. */
    val amount: BigDecimal,
    val currencyCode: String?,
    val balance: BigDecimal?,
    val rowIndex: Int,
    /**
     * The card this movement belongs to, when the statement names one. Null is normal and
     * is never replaced with a guess: an unattributable row is better than a row filed
     * under the wrong card.
     */
    val cardLabel: String? = null,
    /**
     * Which bank produced the file, see [com.financetracker.model.BankCode]. Filled in by
     * [BankDetector] and overridable by the user in the import preview.
     */
    val bankCode: String? = null
) {
    val isExpense: Boolean get() = amount.signum() < 0
    val absoluteAmount: BigDecimal get() = amount.abs()
}

/** Which spreadsheet column fed each field, so the UI can explain the mapping. */
data class DetectedColumns(
    val headerRowIndex: Int,
    val date: Int,
    val description: Int?,
    val amount: Int,
    val currency: Int?,
    val balance: Int?,
    val amountsAreMinorUnits: Boolean
)

data class StatementParseResult(
    val rows: List<StatementRow>,
    val skippedRows: Int,
    val columns: DetectedColumns
) {
    val fromTimestamp: Long get() = rows.minOf { it.timestamp }
    val toTimestamp: Long get() = rows.maxOf { it.timestamp }
}

enum class StatementImportFailure {
    UNREADABLE_FILE,
    UNSUPPORTED_FORMAT,
    EMPTY_FILE,
    NO_HEADER_ROW,
    NO_DATE_COLUMN,
    NO_AMOUNT_COLUMN,
    TOO_LARGE
}

sealed interface StatementParseOutcome {
    data class Parsed(val result: StatementParseResult) : StatementParseOutcome

    /**
     * [detail] carries what the reader actually saw, so an unexpected column layout can
     * be diagnosed from the message instead of needing the file to debug.
     */
    data class Failed(
        val failure: StatementImportFailure,
        val detail: String? = null
    ) : StatementParseOutcome
}
