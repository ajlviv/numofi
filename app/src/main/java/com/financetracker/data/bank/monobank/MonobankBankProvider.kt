package com.financetracker.data.bank.monobank

import com.financetracker.data.bank.BankAccount
import com.financetracker.data.bank.BankAuth
import com.financetracker.data.bank.BankProvider
import com.financetracker.data.bank.BankRateLimitException
import com.financetracker.data.bank.BankTransaction
import com.financetracker.data.bank.Iso4217
import retrofit2.HttpException
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monobank implementation of [BankProvider].
 *
 * Monobank's public API is token-based only: there is no OAuth flow on the personal
 * API, so a [BankAuth.PersonalToken] is the only credential this provider can use.
 * Distributing an app that serves other users' accounts requires Monobank's
 * Service Provider API instead, which needs provider-side approval and request
 * signing; see [BankAuth.ServiceProvider].
 */
@Singleton
class MonobankBankProvider @Inject constructor(
    private val api: MonobankApi
) : BankProvider {

    override val id: String = ID
    override val displayName: String = "Monobank"

    override suspend fun isConfigured(auth: BankAuth?): Boolean =
        auth is BankAuth.PersonalToken && auth.token.isNotBlank()

    override suspend fun getAccounts(auth: BankAuth): List<BankAccount> {
        val token = auth.requireToken()
        return api.getClientInfo(token).accounts.orEmpty().mapNotNull { it.toBankAccount() }
    }

    override suspend fun getTransactions(
        auth: BankAuth,
        accountId: String,
        from: Long,
        to: Long
    ): List<BankTransaction> {
        val token = auth.requireToken()
        val fromSeconds = from / 1000
        val toSeconds = to / 1000
        require(toSeconds - fromSeconds <= MAX_STATEMENT_WINDOW_SECONDS) {
            "Monobank allows at most ${MAX_STATEMENT_WINDOW_SECONDS}s per statement request"
        }

        return try {
            api.getStatement(token, accountId, fromSeconds, toSeconds)
                .mapNotNull { it.toBankTransaction(accountId) }
        } catch (e: HttpException) {
            if (e.code() == HTTP_TOO_MANY_REQUESTS) {
                throw BankRateLimitException(
                    retryAfterMillis = e.retryAfterMillis(),
                    message = "Monobank rate limit reached. Wait " +
                        "${e.retryAfterMillis() / 1000}s before requesting more statements."
                )
            }
            throw e
        }
    }

    /**
     * Personal tokens are limited to one statement request per 60 seconds. Monobank
     * documents the limit but sends no machine-readable retry hint on this endpoint,
     * so the full interval is used as the backoff.
     */
    override val minStatementIntervalMillis: Long = MIN_STATEMENT_INTERVAL_MILLIS

    private fun HttpException.retryAfterMillis(): Long =
        response()?.headers()?.get("Retry-After")?.toLongOrNull()?.times(1000)
            ?: MIN_STATEMENT_INTERVAL_MILLIS

    private fun BankAuth.requireToken(): String = when (this) {
        is BankAuth.PersonalToken -> token
        is BankAuth.ServiceProvider ->
            throw UnsupportedOperationException(
                "Monobank's personal API does not support OAuth; use a personal token"
            )
    }

    private fun MonobankAccount.toBankAccount(): BankAccount? {
        val accountId = id ?: return null
        val currency = currencyCode ?: DEFAULT_CURRENCY
        return BankAccount(
            id = accountId,
            bankId = sendId,
            name = maskedPan?.firstOrNull() ?: iban ?: accountId,
            type = type.orEmpty(),
            currencyCode = currency,
            balance = balance?.let { Iso4217.toMajorUnits(it, currency) } ?: BigDecimal.ZERO,
            creditLimit = creditLimit?.let { Iso4217.toMajorUnits(it, currency) },
            maskedPan = maskedPan.orEmpty(),
            iban = iban
        )
    }

    private fun StatementItemResponse.toBankTransaction(accountId: String): BankTransaction? {
        val transactionId = id ?: return null
        val currency = currencyCode ?: DEFAULT_CURRENCY
        val amountInMinor = amount ?: return null
        return BankTransaction(
            id = transactionId,
            accountId = accountId,
            timestamp = (time ?: return null) * 1000,
            description = description.orEmpty(),
            amount = Iso4217.toMajorUnits(amountInMinor, currency),
            currencyCode = currency,
            balanceAfter = balance?.let { Iso4217.toMajorUnits(it, currency) },
            comment = comment,
            counterName = counterName,
            isHold = hold ?: false,
            mcc = mcc
        )
    }

    companion object {
        const val ID = "monobank"
        const val BASE_URL = "https://api.monobank.ua/"

        /** 31 days + 1 hour, per the API docs. */
        const val MAX_STATEMENT_WINDOW_SECONDS = 2_682_000L

        /** Personal-token rate limit: one statement request per 60 seconds. */
        const val MIN_STATEMENT_INTERVAL_MILLIS = 60_000L

        private const val DEFAULT_CURRENCY = 980 // UAH
        private const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
