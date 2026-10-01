package com.financetracker.data.rates

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import kotlin.coroutines.Continuation

/**
 * Pins the shape of the NBU request, because nothing else could.
 *
 * Every other test in this package feeds [NbuRatesParser] a hand-written JSON string, so they
 * pass whatever URL is declared and whatever return type it declares — including a wrong one.
 * Both halves of that went wrong here in turn and neither showed up until a real device was
 * involved:
 *
 * - the annotation was missing `?json`, so NBU answered XML and the parse failed;
 * - the return type was `String`, which is not "hand me the body" but "run this through Gson",
 *   and Gson reads a top-level array as a string literal and throws.
 *
 * The second one is why this file builds a real Retrofit with the app's own converter and
 * answers from an interceptor, instead of asserting on the declaration. A declaration check
 * would have passed on a type Retrofit still mangles.
 */
class NbuApiContractTest {

    private val json =
        """[{"r030":840,"txt":"Долар США","rate":41.5431,"cc":"USD","exchangedate":"01.10.2026"}]"""

    private fun api(): NbuApi {
        val canned = Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody(null))
                .build()
        }
        return Retrofit.Builder()
            .baseUrl(NbuApi.BASE_URL)
            .client(OkHttpClient.Builder().addInterceptor(canned).build())
            // The same factory NetworkModule installs. Without it the test would prove nothing:
            // the bug is what Gson does to the body, not what Retrofit does without it.
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NbuApi::class.java)
    }

    @Test
    fun `the body arrives unparsed`() = runBlocking {
        // With a `String` return type this throws `Expected a string but was BEGIN_ARRAY`, even
        // though NBU answered 200 with exactly the array the parser was written for.
        assertEquals(json, api().exchangeRates().use { it.string() })
    }

    @Test
    fun `asks for json because the endpoint defaults to xml`() {
        var requested: String? = null
        val spy = Interceptor { chain ->
            requested = chain.request().url.encodedQuery
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody(null))
                .build()
        }
        runBlocking {
            Retrofit.Builder()
                .baseUrl(NbuApi.BASE_URL)
                .client(OkHttpClient.Builder().addInterceptor(spy).build())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(NbuApi::class.java)
                .exchangeRates()
                .use { }
        }
        assertEquals("json", requested)
    }

    @Test
    fun `the annotation still names the directory on the nbu host`() {
        val get = NbuApi::class.java
            .getMethod("exchangeRates", Continuation::class.java)
            .getAnnotation(retrofit2.http.GET::class.java)
        assertEquals("NBUStatService/v1/statdirectory/exchange?json", get.value)
        assertEquals("https://bank.gov.ua/", NbuApi.BASE_URL)
    }

    @Test
    fun `the parser reads what came back`() {
        // The other end of the same round trip, so this file also proves the canned body is the
        // shape [NbuRatesParser] was written against rather than an array of nothing.
        val parsed = NbuRatesParser.parse(json, 1_757_000_000_000L)
        assertEquals(41.5431, parsed.toUah.getValue("USD"), 1e-9)
    }
}