package com.financetracker.data.statement

import com.financetracker.data.TransactionDao
import com.financetracker.model.SearchText
import com.financetracker.model.TransactionEntity
import com.financetracker.model.TransactionType
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes statement rows into local storage.
 *
 * Statements carry no stable transaction id, so a row's identity is derived from its
 * content. Two different kinds of duplicate have to be handled:
 *
 *  - the same file imported twice, which the SHA-256 fingerprint catches exactly;
 *  - a line that bank sync already stored, where the row has a bank id like
 *    `monobank_123` and the statement line has no id at all. Fingerprints cannot see
 *    this, so a content match is also made against rows already in the period.
 */
@Singleton
class StatementImportService @Inject constructor(
    private val transactionDao: TransactionDao
) {

    data class Result(
        val imported: Int,
        val duplicatesSkipped: Int,
        /** Lines that matched something bank sync had already stored. */
        val alreadySynced: Int
    )

    suspend fun import(userId: String, rows: List<StatementRow>): Result {
        val existing = transactionDao.getExternalIdsForUser(userId).toMutableSet()
        val content = ContentIndex.load(transactionDao, userId, rows)

        var imported = 0
        var duplicates = 0
        var alreadySynced = 0

        for (row in rows) {
            val fingerprint = fingerprint(row)

            // Strongest signal first: the row is byte-identical to one already stored.
            if (!existing.add(fingerprint)) {
                duplicates++
                continue
            }

            // Otherwise it may still be the same payment that bank sync recorded.
            if (content.matches(row.bankCode, row)) {
                alreadySynced++
                continue
            }

            transactionDao.insert(row.toEntity(userId, fingerprint))
            content.add(row.bankCode, row)
            imported++
        }
        return Result(imported, duplicates, alreadySynced)
    }

    /**
     * Amount is quantised to kopecks so that two exports of the same transaction
     * fingerprint identically despite differing decimal precision.
     *
     * The bank is part of the identity rather than just a prefix: the same payment
     * recorded at two banks is two real rows, and leaving it out would collapse them.
     */
    private fun fingerprint(row: StatementRow): String {
        val canonical = listOf(
            row.bankCode.orEmpty(),
            row.timestamp.toString(),
            row.amount.setScale(SMALLEST_UNIT_SCALE, RoundingMode.HALF_UP).toPlainString(),
            row.currencyCode.orEmpty(),
            row.description.trim().lowercase()
        ).joinToString("|")

        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return "${row.namespace}_$SOURCE_PREFIX${digest.joinToString("") { "%02x".format(it) }}"
    }

    private fun StatementRow.toEntity(userId: String, externalId: String) = TransactionEntity(
        userId = userId,
        title = description.ifBlank { "Imported transaction" },
        amount = absoluteAmount.toDouble(),
        type = if (isExpense) TransactionType.EXPENSE else TransactionType.INCOME,
        category = CATEGORY_IMPORTED,
        timestamp = timestamp,
        note = null,
        externalId = externalId,
        source = namespace,
        // Statements from a UA bank usually omit the column, so default rather than
        // leaving the row without a currency to render.
        currencyCode = currencyCode?.takeIf { it.isNotBlank() }?.uppercase() ?: DEFAULT_CURRENCY,
        bankCode = bankCode,
        cardLabel = cardLabel?.trim()?.takeIf { it.isNotEmpty() },
        searchText = SearchText.of(description, null, CATEGORY_IMPORTED, bankCode, cardLabel)
    )

    /**
     * Matches statement lines against stored rows by content.
     *
     * A match needs the same amount and direction, plus one of:
     *  - the same instant, when the statement carries a time of day; or
     *  - the same calendar day and the same description, when it does not.
     *
     * Requiring the description in the date-only case is what keeps two genuinely
     * different same-day payments of an identical amount from being merged.
     *
     * Matching is scoped to a bank. The same payment cannot really settle at two
     * institutions, and treating it as one would file a second bank's statement line as
     * "already synced" and drop it. When either side's bank is unknown the comparison
     * stays permissive, so an unrecognised file is still protected from double counting.
     */
    private class ContentIndex {
        private val byInstant = mutableSetOf<Entry>()
        private val byDayAndTitle = mutableSetOf<Entry>()

        fun add(bank: String?, row: StatementRow) {
            byInstant += Entry(bank, key(row.timestamp, row.absoluteAmount, row.isExpense))
            byDayAndTitle += Entry(
                bank,
                key(startOfDay(row.timestamp), row.absoluteAmount, row.isExpense, row.description)
            )
        }

        fun matches(bank: String?, row: StatementRow): Boolean =
            Entry(bank, key(row.timestamp, row.absoluteAmount, row.isExpense)) in byInstant ||
                Entry(
                    bank,
                    key(startOfDay(row.timestamp), row.absoluteAmount, row.isExpense, row.description)
                ) in byDayAndTitle

        private data class Entry(val bank: String?, val key: String) {
            /** Equal when the banks are known and identical, or when either is unknown. */
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Entry || other.key != key) return false
                return bank == null || other.bank == null || bank == other.bank
            }

            override fun hashCode(): Int = key.hashCode()
        }

        /**
         * Amounts are rendered at a fixed scale so a value read as `120.5` from a stored
         * Double compares equal to `120.50` from a statement.
         */
        private fun key(vararg parts: Any?): String =
            parts.joinToString("|") { part ->
                when (part) {
                    is BigDecimal -> part.setScale(MONEY_SCALE, RoundingMode.HALF_UP).toPlainString()
                    else -> part?.toString().orEmpty()
                }
            }

        companion object {
            suspend fun load(dao: TransactionDao, userId: String, rows: List<StatementRow>): ContentIndex {
                val index = ContentIndex()

                val from = rows.minOfOrNull { it.timestamp } ?: return index
                val to = rows.maxOfOrNull { it.timestamp } ?: return index

                // Widen the window so a statement and a synced row that straddle the
                // boundary still line up on the same day.
                val stored = dao.getInRange(userId, from - DAY_MILLIS, to + DAY_MILLIS)
                stored.forEach { entity ->
                    // Stored rows keep a positive magnitude with the direction in `type`,
                    // whereas a statement line is signed, so the sign has to be restored
                    // here or every expense would look like a credit.
                    val magnitude = entity.amount.toBigDecimal()
                    val signed = if (entity.type == TransactionType.EXPENSE) magnitude.negate() else magnitude
                    index.add(
                        entity.bankCode,
                        StatementRow(
                            timestamp = entity.timestamp,
                            description = entity.title,
                            amount = signed,
                            currencyCode = entity.currencyCode,
                            balance = null,
                            rowIndex = 0
                        )
                    )
                }
                return index
            }

            private const val DAY_MILLIS = 86_400_000L
            private const val MONEY_SCALE = 2

            fun startOfDay(timestamp: Long): Long = Instant.ofEpochMilli(timestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }
    }

    /**
     * The bank a statement file belongs to, or [UNKNOWN_BANK] when it was not established.
     * Declared inside the class so it can read the companion's constants.
     */
    private val StatementRow.namespace: String get() = bankCode ?: UNKNOWN_BANK

    private companion object {
        const val SOURCE_PREFIX = "import_"
        const val CATEGORY_IMPORTED = "imported"
        const val SMALLEST_UNIT_SCALE = 2
        const val DEFAULT_CURRENCY = "UAH"

        /**
         * Namespace for a file whose bank could not be established. Still distinct from any
         * real bank, so those rows group together in the filter instead of disappearing.
         */
        const val UNKNOWN_BANK = "unknown"
    }
}
