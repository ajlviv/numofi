package com.financetracker.data.bank.monobank

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Monobank Open API.
 *
 * Docs: https://api.monobank.ua/docs/index.html
 *
 * Rate limits (per the docs): `/personal/client-info` and `/personal/statement/...`
 * are each limited to one call per 60 seconds, and a statement may span at most
 * 31 days + 1 hour. [MonobankBankProvider] enforces the window limit; callers are
 * responsible for the 60s rate limit.
 */
interface MonobankApi {

    @GET("bank/currency")
    suspend fun getCurrencies(): List<CurrencyInfoResponse>

    @GET("bank/sync")
    suspend fun sync(): BankSyncResponse

    @GET("personal/client-info")
    suspend fun getClientInfo(
        @Header("X-Token") token: String
    ): ClientInfoResponse

    @POST("personal/webhook")
    suspend fun setWebhook(
        @Header("X-Token") token: String,
        @Query("webHookUrl") webHookUrl: String
    )

    /**
     * `account` accepts an account id, a bank id, or "0" for the default account.
     * `from`/`to` are Unix seconds.
     */
    @GET("personal/statement/{account}/{from}/{to}")
    suspend fun getStatement(
        @Header("X-Token") token: String,
        @Path("account") account: String,
        @Path("from") from: Long,
        @Path("to") to: Long
    ): List<StatementItemResponse>
}
