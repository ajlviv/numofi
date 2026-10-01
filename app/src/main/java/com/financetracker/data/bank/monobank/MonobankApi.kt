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
 *
 * `/bank/currency` was declared here once and never called, and has been removed rather than
 * left as a spare. It is public and would work, but it publishes monobank's own retail rates:
 * the USD/UAH and EUR/UAH pairs carry no `rateCross`, so a caller has to pick `rateBuy` or
 * `rateSell` and systematically under- or over-state every foreign holding — the measured
 * spread was 0.9% on USD. Its `rateCross` was also a non-null `Double`, so the pairs that do
 * lack it would have deserialized to `0.0` without error. Rates come from NBU instead; see
 * `data/rates/NbuApi.kt`.
 */
interface MonobankApi {

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
