package com.financetracker.data.bank

import com.financetracker.data.TransactionDao
import com.financetracker.data.bank.monobank.MonobankBankProvider
import com.financetracker.data.bank.Iso4217
import com.financetracker.model.BankCode
import com.financetracker.model.BankNames
import com.financetracker.model.SearchText
import com.financetracker.model.TransactionEntity
import com.financetracker.model.TransactionType
import com.financetracker.repository.BankRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Imports bank statements into local storage.
 *
 * History is walked backwards in provider-sized windows so the most recent data is
 * available first, and rows are keyed by the bank's own id so re-running a sync over
 * an overlapping period is a no-op rather than a duplication.
 *
 * Some providers rate-limit statement requests, so the engine paces itself to
 * [BankProvider.minStatementIntervalMillis] and reports the wait through [SyncProgress]
 * rather than letting requests fail. A rate limit that still slips through surfaces as
 * [BankRateLimitException] with any rows imported so far already committed.
 */
@Singleton
class BankSyncService @Inject constructor(
    private val transactionDao: TransactionDao,
    private val bankRepository: BankRepository
) {

    data class Result(
        val accounts: Int,
        val imported: Int,
        val skippedDuplicates: Int
    )

    /**
     * @param completedRequests statement requests finished so far.
     * @param totalRequests statement requests this run will make.
     * @param imported transactions written so far.
     * @param cooldownMillis time left before the next request; 0 while working.
     */
    data class SyncProgress(
        val completedRequests: Int,
        val totalRequests: Int,
        val imported: Int,
        val cooldownMillis: Long
    )

    /**
     * Pulls transactions for every account exposed by [auth] in `[from, to]`.
     *
     * The caller decides the range. Passing the gap since the previous sync keeps this
     * to a single request in the steady state, which matters because providers throttle
     * and each extra request would otherwise cost a pacing delay.
     *
     * @throws BankRateLimitException if the provider throttles despite pacing.
     */
    suspend fun sync(
        provider: BankProvider,
        auth: BankAuth,
        userId: String,
        from: Long,
        to: Long,
        onProgress: (SyncProgress) -> Unit = {}
    ): Result {
        val accounts = provider.getAccounts(auth)
        val windows = windows(provider, from, to)
        val totalRequests = windows.size * accounts.size

        var imported = 0
        var skipped = 0
        var completedRequests = 0
        var lastRequestAt = 0L

        val existing = transactionDao.getExternalIdsForUser(userId).toMutableSet()
        // Read once for the whole sync: every row this writes carries Monobank, and the
        // haystack needs that bank's name so the rows stay findable by it.
        val bankNames = bankRepository.names.first()

        for ((windowFrom, windowTo) in windows) {
            for (account in accounts) {
                val cooldown = cooldownRemaining(provider, completedRequests, lastRequestAt)
                if (cooldown > 0) {
                    // Tick so the UI countdown actually moves instead of jumping.
                    var remaining = cooldown
                    while (remaining > 0) {
                        onProgress(
                            SyncProgress(completedRequests, totalRequests, imported, remaining)
                        )
                        delay(COUNTDOWN_TICK_MILLIS)
                        remaining -= COUNTDOWN_TICK_MILLIS
                    }
                }

                val fetched = provider.getTransactions(auth, account.id, windowFrom, windowTo)
                lastRequestAt = monotonicMillis()
                completedRequests++

                for (bankTx in fetched) {
                    // Namespaced by provider so two banks cannot collide on ids.
                    val externalId = "${provider.id}_${bankTx.id}"
                    if (externalId in existing) {
                        skipped++
                        continue
                    }
                    transactionDao.insert(
                        toEntity(
                            bankTx,
                            userId,
                            provider.id,
                            BankCode.MONOBANK,
                            account,
                            bankNames
                        )
                    )
                    existing.add(externalId)
                    imported++
                }

                onProgress(SyncProgress(completedRequests, totalRequests, imported, 0))
            }
        }

        return Result(accounts = accounts.size, imported = imported, skippedDuplicates = skipped)
    }

    /**
     * Splits `[from, to]` into provider-sized steps, newest first, so the most recent
     * data is available even if a later step fails.
     */
    private fun windows(provider: BankProvider, from: Long, to: Long): List<Pair<Long, Long>> {
        val stepMillis = windowLengthMillis(provider)
        if (to <= from) return emptyList()

        val result = mutableListOf<Pair<Long, Long>>()
        var windowEnd = to
        while (windowEnd > from) {
            val windowStart = max(from, windowEnd - stepMillis)
            result.add(windowStart to windowEnd)
            windowEnd = windowStart
        }
        return result
    }

    private fun cooldownRemaining(
        provider: BankProvider,
        completedRequests: Int,
        lastRequestAt: Long
    ): Long {
        val interval = provider.minStatementIntervalMillis
        if (interval <= 0L || completedRequests == 0) return 0L
        return (lastRequestAt + interval) - monotonicMillis()
    }

    /**
     * Steps conservatively below the provider's own cap, leaving room for clock skew
     * so a window that measures exactly at the limit is never rejected.
     */
    private fun windowLengthMillis(provider: BankProvider): Long {
        val capSeconds = when (provider) {
            is MonobankBankProvider -> MonobankBankProvider.MAX_STATEMENT_WINDOW_SECONDS
            else -> DEFAULT_WINDOW_SECONDS
        }
        return (capSeconds - WINDOW_SAFETY_MARGIN_SECONDS) * 1000L
    }

    private fun monotonicMillis(): Long = System.nanoTime() / NANOS_PER_MILLI

    private fun toEntity(
        bankTx: BankTransaction,
        userId: String,
        source: String,
        bankCode: String,
        account: BankAccount,
        bankNames: Map<String, String>
    ): TransactionEntity {
        // The masked number is what the user recognises on a statement; the account name is
        // the fallback when the provider withholds it. Both are stored as disclosed, never
        // assembled from anything the bank did not publish.
        val card = account.maskedPan.filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { account.name }
        val title = bankTx.description.ifBlank { bankTx.counterName.orEmpty() }
        val category = bankTx.mcc?.let { "mcc_$it" } ?: CATEGORY_OTHER
        return TransactionEntity(
            userId = userId,
            title = title,
            // Banks report spending as negative; the model stores a positive magnitude
            // and carries direction in `type`.
            amount = bankTx.absoluteAmount.toDouble(),
            type = if (bankTx.isExpense) TransactionType.EXPENSE else TransactionType.INCOME,
            category = category,
            timestamp = bankTx.timestamp,
            note = bankTx.comment,
            externalId = "${source}_${bankTx.id}",
            source = source,
            currencyCode = Iso4217.symbolOf(bankTx.currencyCode),
            bankCode = bankCode,
            cardLabel = card,
            searchText = SearchText.of(
                title,
                bankTx.comment,
                category,
                BankNames.ref(bankCode, bankNames),
                card
            )
        )
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
        const val DEFAULT_WINDOW_SECONDS = 2_592_000L
        const val WINDOW_SAFETY_MARGIN_SECONDS = 3_600L
        const val COUNTDOWN_TICK_MILLIS = 1_000L
        const val NANOS_PER_MILLI = 1_000_000L

        const val CATEGORY_OTHER = "other"
    }
}
