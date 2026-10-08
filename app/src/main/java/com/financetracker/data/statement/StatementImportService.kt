package com.financetracker.data.statement

import com.financetracker.data.TransactionDao
import com.financetracker.data.backup.BackupReason
import com.financetracker.data.backup.BackupRequests
import com.financetracker.model.BankNames
import com.financetracker.model.SearchText
import com.financetracker.model.TransactionEntity
import com.financetracker.model.TransactionType
import com.financetracker.model.TransferDirection
import com.financetracker.model.TransferPairing
import com.financetracker.repository.BankRepository
import com.financetracker.util.CategorySuggestion
import kotlinx.coroutines.flow.first
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
    private val transactionDao: TransactionDao,
    private val bankRepository: BankRepository,
    private val backupRequests: BackupRequests
) {

    data class Result(
        val imported: Int,
        val duplicatesSkipped: Int,
        /** Lines that matched something bank sync had already stored. */
        val alreadySynced: Int
    )

    /**
     * Writes statement rows into local storage.
     *
     * @param categories the user's category list, used only for suggestions.
     * @param categoryOverrides per-row choices from the import preview, by row index. Absent
     * means "use the suggestion": [com.financetracker.util.CategorySuggestion] over the row's
     * title and the stored `imported` key, with the title history of rows already in the
     * ledger as the fallback. Overrides and one-off typed values are stored verbatim and
     * never added to the settings list.
     */
    suspend fun import(
        userId: String,
        rows: List<StatementRow>,
        categories: List<String> = emptyList(),
        categoryOverrides: Map<Int, String> = emptyMap()
    ): Result {
        val existing = transactionDao.getExternalIdsForUser(userId).toMutableSet()
        val content = ContentIndex.load(transactionDao, userId, rows)
        // Read once for the whole file rather than per row: the haystack needs the bank's
        // name so the rows stay findable by it, and that lookup is the same for every line.
        val bankNames = bankRepository.names.first()
        // One read for the whole file as well: past titles and their categories, so each
        // row's suggestion can prefer what the user chose last time for the same wording.
        // The fold is shared with the preview so the two agree on what is suggested.
        val history = CategorySuggestion.foldHistory(
            transactionDao.getTitleCategoryPairs(userId)
        )

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

            val category = categoryOverrides[row.rowIndex]
                ?: CategorySuggestion.suggest(CATEGORY_IMPORTED, row.description, categories, history)
            transactionDao.insert(row.toEntity(userId, fingerprint, bankNames, category))
            content.add(row.bankCode, row)
            imported++
        }
        // Asked for once, after the file rather than per row: the burst of requests a row-at-a-
        // time import would produce collapses into a single extra upload anyway, and saying
        // why is what lets the status line report "after an import" rather than just "now".
        //
        // Not asked for when nothing was written, because a file that was entirely duplicates
        // has not changed the data and the backup already in Drive is still correct.
        // Run even when every line was a duplicate, because a re-import is how a table written
        // before this behaviour existed gets the transfers in it recognised. The count of
        // imported rows says nothing about whether the ledger changed: the counterpart of an
        // already-stored leg is usually in a different file the user opens later, and pairing
        // relabels both halves.
        val paired = recogniseTransfers(userId, rows)

        // Asked for once, after the file rather than per row: the burst of requests a row-at-a-
        // time import would produce collapses into a single extra upload anyway, and saying
        // why is what lets the status line report "after an import" rather than just "now".
        //
        // Not asked for when nothing was written, because a file that was entirely duplicates
        // has not changed the data and the backup already in Drive is still correct. A relabelled
        // pair is a change to what this device believes, so it counts as a write even though no
        // row was inserted — otherwise the file in Drive would keep describing the old types.
        if (imported > 0 || paired > 0) {
            backupRequests.requestUpload(BackupReason.IMPORT)
        }
        return Result(imported, duplicates, alreadySynced)
    }

    /**
     * Relabels the two legs of a transfer that arrived in different statements.
     *
     * A transfer between the user's own accounts is one movement the banks report twice: a
     * debit on the account it left and a credit on the one it reached. Left as an income and
     * an expense they inflate both figures on the dashboard. Bank sync has recognised these
     * since it was written, but the two legs there come from one provider's response and land
     * within minutes — a statement puts them in different files a user imports at different
     * times, hours apart, so the sync's window finds none of them.
     *
     * The rows are read back from the table rather than kept from the insert loop, because the
     * counterpart is usually not in this file at all: the sending bank's statement and the
     * receiving bank's are separate documents, and whichever the user opens second is the one
     * that completes the pair. The range is the file's own span widened by the window, so a
     * leg stored by an earlier import is in reach without reading the whole history.
     *
     * A leg already relabelled is a [TransactionType.TRANSFER], which [TransferPairing] does
     * not pair, so a repeated import is a no-op here and cannot re-pair a finished pair.
     *
     * Returns how many pairs were relabelled, which is what tells the caller whether the
     * ledger changed.
     */
    private suspend fun recogniseTransfers(userId: String, rows: List<StatementRow>): Int {
        val from = rows.minOfOrNull { it.timestamp } ?: return 0
        val to = rows.maxOfOrNull { it.timestamp } ?: return 0

        val stored = transactionDao.getInRange(
            userId,
            from - TransferPairing.STATEMENT_WINDOW_MILLIS,
            to + TransferPairing.STATEMENT_WINDOW_MILLIS
        )
        val pairs = TransferPairing.pairsWithin(stored, TransferPairing.STATEMENT_WINDOW_MILLIS)
        for (pair in pairs) {
            transactionDao.update(pair.inbound.asTransfer(TransferDirection.IN))
            transactionDao.update(pair.outbound.asTransfer(TransferDirection.OUT))
        }
        return pairs.size
    }

    private fun TransactionEntity.asTransfer(direction: TransferDirection) =
        copy(type = TransactionType.TRANSFER, transferDirection = direction)

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

    private fun StatementRow.toEntity(
        userId: String,
        externalId: String,
        bankNames: Map<String, String>,
        category: String = CATEGORY_IMPORTED
    ) = TransactionEntity(
        userId = userId,
        title = description.ifBlank { "Imported transaction" },
        amount = absoluteAmount.toDouble(),
        type = if (isExpense) TransactionType.EXPENSE else TransactionType.INCOME,
        category = category,
        timestamp = timestamp,
        note = null,
        externalId = externalId,
        source = namespace,
        // Statements from a UA bank usually omit the column, so default rather than
        // leaving the row without a currency to render.
        currencyCode = currencyCode?.takeIf { it.isNotBlank() }?.uppercase() ?: DEFAULT_CURRENCY,
        bankCode = bankCode,
        cardLabel = cardLabel?.trim()?.takeIf { it.isNotEmpty() },
        searchText = SearchText.of(
            description,
            null,
            category,
            BankNames.ref(bankCode, bankNames),
            cardLabel
        )
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
