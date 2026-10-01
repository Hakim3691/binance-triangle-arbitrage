package com.hakim3691.bta.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Every REST failure used to render as the same unusable state: the dashboard
 * said ERROR, the credentials panel said "not checked", and the only detail
 * was buried in a log the user had to export. These pin the classifications
 * that make the difference between a wrong key, a blocked region and a dead
 * network visible - each of which has a completely different fix.
 */
class RestDiagnosisTest {

    @Test
    fun `http 451 is reported as a region block, not a key problem`() {
        // The exact response Binance returns from a restricted jurisdiction,
        // on every endpoint, signed or not.
        val body = """{"code":0,"msg":"Service unavailable from a restricted location " +
            "according to 'b. Eligibility' in https://www.binance.com/en/terms."}"""
        val described = RestDiagnosis.describe(BinanceApiException(451, "[HTTP 451] $body"))

        assertTrue(described, described.contains("blocked this network's region"))
        assertTrue(described, described.contains("451"))
        // The user must not be sent hunting for a credential problem.
        assertTrue("must not blame the key", !described.contains("rejected the API key"))
        // The raw jurisdiction notice is not helpfully displayed.
        assertTrue("must not echo the raw body", !described.contains("Eligibility"))
    }

    @Test
    fun `http 401 is reported as a rejected key`() {
        val described = RestDiagnosis.describe(BinanceApiException(401, "[HTTP 401] invalid key"))
        assertTrue(described, described.contains("rejected the API key"))
    }

    @Test
    fun `rate limit and clock skew are named`() {
        assertTrue(
            RestDiagnosis.describe(BinanceApiException(429, "too many requests"))
                .contains("rate limit")
        )
        // -1021 arrives with the code in the body; the text is matched too so
        // the clock diagnosis survives a code that only shows up in the message.
        assertTrue(
            RestDiagnosis.describe(BinanceApiException(-1021, "Timestamp for this request is outside of the recvWindow"))
                .contains("clock")
        )
        assertTrue(
            RestDiagnosis.describe(BinanceApiException(400, "Timestamp ... outside of the recvWindow"))
                .contains("clock")
        )
    }

    @Test
    fun `a rejected key is named by binance's own code`() {
        // -2015 is what Binance actually returns for a bad key/secret; with the
        // HTTP status it was previously misreported as a generic 400.
        val described = RestDiagnosis.describe(BinanceApiException(-2015, "Invalid API-key, IP, or permissions for action."))
        assertTrue(described, described.contains("rejected the API key"))
    }

    @Test
    fun `unknown host is reported as a missing connection, not a Binance fault`() {
        val described = RestDiagnosis.describe(UnknownHostException("api.binance.com"))
        assertTrue(described, described.contains("no working internet connection"))
    }

    @Test
    fun `transport failures are distinguished from each other`() {
        assertTrue(
            RestDiagnosis.describe(SocketTimeoutException("timeout")).contains("Timed out")
        )
        assertTrue(
            RestDiagnosis.describe(ConnectException("refused")).contains("refused")
        )
        assertTrue(
            RestDiagnosis.describe(SSLHandshakeException("handshake failed")).contains("TLS")
        )
    }

    @Test
    fun `an unknown binance status still surfaces the status code`() {
        val described = RestDiagnosis.describe(BinanceApiException(418, "I'm a teapot"))
        assertTrue(described, described.contains("418"))
    }

    @Test
    fun `an unrecognised status still echoes Binance's message`() {
        // Deliberate: for a non-classified error the server's own wording is
        // the only diagnostic available, so it is passed through.
        val described = RestDiagnosis.describe(BinanceApiException(-1013, "Filter failure: MIN_NOTIONAL"))
        assertTrue(described, described.contains("-1013"))
        assertTrue(described, described.contains("MIN_NOTIONAL"))
    }

    @Test
    fun `a null message still yields something readable`() {
        // NetworkOnMainThreadException carries no message at all, so the panel
        // rendered a bare "NetworkOnMainThreadException:" with nothing after it.
        val described = RestDiagnosis.describe(IllegalStateException())
        assertEquals("IllegalStateException", described)
    }

    @Test
    fun `key status defaults to no problem`() {
        val status = com.hakim3691.bta.security.KeyStatus(
            stored = true, maskedKey = "abcd••••wxyz",
            canRead = true, canSpotTrade = true,
            feePercent = 0.001, checkedAtMs = 0L
        )
        assertEquals(null, status.problem)
    }
}
