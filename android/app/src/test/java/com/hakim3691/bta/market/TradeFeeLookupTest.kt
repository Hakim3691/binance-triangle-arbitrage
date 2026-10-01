package com.hakim3691.bta.market

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The account fee lookup failed silently, and it failed in the worst possible
 * way: it returned null for a key whose permissions had just been verified as
 * good, and the log said "no API key or no tradeFee row" - which is false on
 * both counts. So a real refusal (missing permission, region block, rejected
 * key) was indistinguishable from a fee row that simply does not exist for the
 * pair, and the app quietly kept its 0.10% guess while the UI showed the key
 * as working.
 *
 * These pin the two outcomes apart: a body with no row parses to null, and a
 * refused request carries Binance's own reason out as an exception the existing
 * diagnosis already knows how to classify.
 */
class TradeFeeLookupTest {

    private fun clientReturning(code: Int, body: String) = BinanceRestClient(
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("mock")
                    .body(body.toResponseBody())
                    .build()
            })
            .build(),
        { "key" },
        { "secret" }
    )

    private val okBody = """
        {"tradeFee":[{"symbol":"BTCUSDT","makerCommission":"0.001","takerCommission":"0.000750"}]}
    """.trimIndent()

    @Test
    fun `a taker commission row is parsed out of a successful response`() {
        val rate = clientReturning(200, okBody).parseTradeFee(okBody, "BTCUSDT")
        // 0.075%: the BNB-discount rate. This is the whole point of the
        // lookup - it is materially different from the 0.10% fallback, and it
        // decides whether a marginal triangle clears three legs of fees.
        assertEquals(0.00075, rate!!, 1e-9)
    }

    @Test
    fun `a body without the requested symbol parses to null, not an exception`() {
        val body = """{"tradeFee":[{"symbol":"ETHUSDT","takerCommission":"0.001"}]}"""
        // "This account has no row for that pair" is a real, benign outcome and
        // must stay a null so the caller can log it as such.
        assertNull(clientReturning(200, body).parseTradeFee(body, "BTCUSDT"))
    }

    @Test
    fun `a body with no tradeFee array at all parses to null`() {
        val body = """{"code":0,"msg":"success"}"""
        assertNull(clientReturning(200, body).parseTradeFee(body, "BTCUSDT"))
    }

    @Test
    fun `a refused request throws with binance's own reason instead of returning null`() = runBlocking {
        // -2015 is what Binance answers for a key it will not accept. The old
        // code returned null here, and the caller reported "no tradeFee row".
        val refused = clientReturning(400, """{"code":-2015,"msg":"Invalid API-key, IP, or permissions for action."}""")
        try {
            val rate = refused.accountTakerCommission("BTCUSDT")
            fail("expected a BinanceApiException, got $rate")
        } catch (e: BinanceApiException) {
            assertEquals(-2015, e.code)
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("permissions"))
            // And it classifies as a key problem rather than a missing row.
            assertTrue(RestDiagnosis.describe(e).contains("rejected the API key"))
        }
    }

    @Test
    fun `a no-key call short-circuits without a request`() = runBlocking {
        // Without credentials there is nothing to sign, and no row to find. It
        // must not be reported as a lookup that happened and came back empty.
        val anonymous = BinanceRestClient(
            OkHttpClient.Builder()
                .addInterceptor(Interceptor { _ -> throw AssertionError("no request expected") })
                .build(),
            { "" },
            { "" }
        )
        assertNull(anonymous.accountTakerCommission("BTCUSDT"))
    }
}
