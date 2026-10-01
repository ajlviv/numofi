package com.financetracker.data.rates

import okhttp3.ResponseBody
import retrofit2.http.GET

/**
 * NBU official exchange rates. No key, no auth, no quota worth the name.
 *
 * Chosen over `/bank/currency` on `MonobankApi`, which is public too and was already declared
 * there unused. It publishes the bank's own retail rates, and the two pairs this app needs —
 * USD/UAH and EUR/UAH — come back carrying no `rateCross` at all. A caller would have to pick
 * `rateBuy` or `rateSell` and systematically under- or over-state every foreign holding; the
 * measured spread was 0.9% on USD. NBU publishes one official rate per currency per day, so
 * there is no side to choose.
 */
interface NbuApi {

    /**
     * The raw body, as [ResponseBody] and **not** as `String`.
     *
     * A `String` return type does not mean "hand me the body": Retrofit runs it through the
     * Gson converter, which tries to read the response as a JSON string literal and dies on a
     * top-level array with `Expected a string but was BEGIN_ARRAY at line 1 column 2`. NBU
     * answers `200` with exactly that array, so the request succeeds and the parse fails —
     * which reached the user as "could not reach NBU", because every failure collapsed into one
     * message. `ResponseBody` skips the converters entirely.
     *
     * The body is a flat array of about forty rows of which two are wanted, and Gson would
     * build every field of every row before the parser could drop any of it. Reading it here
     * keeps the choice of what to keep inside [NbuRatesParser], where it is tested. The caller
     * must close it.
     *
     * `?json` is required, not a preference: without it the same URL answers with XML and the
     * parser would fail the same way for a different reason. `NbuApiContractTest` pins both.
     */
    @GET("NBUStatService/v1/statdirectory/exchange?json")
    suspend fun exchangeRates(): ResponseBody

    companion object {
        const val BASE_URL = "https://bank.gov.ua/"
    }
}
