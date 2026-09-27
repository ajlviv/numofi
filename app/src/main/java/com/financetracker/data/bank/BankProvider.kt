package com.financetracker.data.bank

/**
 * Credentials for talking to a bank. Kept separate from [BankProvider] because the
 * credential shape differs per provider and per environment:
 *
 * - [PersonalToken] is a long-lived static token the user pastes in. Fine for
 *   development and personal use, but most providers forbid using it to serve
 *   other people's accounts.
 * - [ServiceProvider] is the OAuth-based flow intended for published third-party
 *   apps. It requires provider-side approval and a request-signing key pair.
 */
sealed interface BankAuth {
    data class PersonalToken(val token: String) : BankAuth
    data class ServiceProvider(val keyId: String, val signingKeyAlias: String) : BankAuth
}

/**
 * A bank the app can talk to. Implementations are registered in [BankProviderRegistry]
 * and selected by the user in Settings.
 */
interface BankProvider {

    /** Stable identifier persisted in settings. Do not change once shipped. */
    val id: String

    /** Name shown in the bank picker. */
    val displayName: String

    /**
     * Whether this provider can work with the credentials currently configured.
     * Lets the UI hide providers the user has not set up yet.
     */
    suspend fun isConfigured(auth: BankAuth?): Boolean

    /** Fetches the user's accounts/cards. */
    suspend fun getAccounts(auth: BankAuth): List<BankAccount>

    /**
     * Fetches transactions for one account in `[from, to]`, epoch millis.
     * Providers cap the window; the caller should chunk accordingly.
     */
    suspend fun getTransactions(
        auth: BankAuth,
        accountId: String,
        from: Long,
        to: Long
    ): List<BankTransaction>

    /**
     * Minimum gap this provider requires between statement requests. The sync engine
     * waits this long between calls rather than letting the API reject the request.
     * Defaults to 0 for providers without a documented limit.
     */
    val minStatementIntervalMillis: Long get() = 0L
}

/**
 * Thrown when a provider rejects a call because too many were made too quickly.
 * [retryAfterMillis] uses the provider's own hint when it sends one.
 */
class BankRateLimitException(
    val retryAfterMillis: Long,
    message: String
) : Exception(message)

/** Resolves a provider by its [BankProvider.id]. */
class BankProviderRegistry(providers: List<BankProvider>) {

    private val byId: Map<String, BankProvider> = providers.associateBy { it.id }

    val all: List<BankProvider> = providers

    fun get(id: String?): BankProvider? = id?.let { byId[it] }
}
