package com.hakim3691.bta.security

import okhttp3.OkHttpClient
import com.hakim3691.bta.market.BinanceRestClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3: the app-controlled key custody model. One stored key, read-only
 * usage in paper mode, read-write in live mode, and a display layer that can
 * never leak the secret.
 */
class KeyPolicyTest {

    @Test
    fun `mask shows first and last four only`() {
        assertEquals("abcd••••wxyz", KeyPolicy.mask("abcd12345678wxyz"))
    }

    @Test
    fun `mask never returns the full key`() {
        for (length in 1..64) {
            val key = "K".repeat(length)
            val masked = KeyPolicy.mask(key)
            if (masked != null) assertFalse("leaked at length $length", masked.contains(key))
        }
    }

    @Test
    fun `short and blank keys mask to bullets or null`() {
        assertEquals("••••", KeyPolicy.mask("short"))
        assertNull(KeyPolicy.mask(""))
        assertNull(KeyPolicy.mask("   "))
    }

    @Test
    fun `restrictions parse from the data wrapper`() = runTest {
        val client = BinanceRestClient(OkHttpClient(), { "k" }, { "s" })
        val parsed = client.parseApiRestrictions("""{"data":{"canRead":true,"canTrade":false}}""")
        assertTrue(parsed.canRead)
        // An explicitly false canTrade is what must block live arming.
        assertFalse(parsed.canTrade)
    }

    @Test
    fun `restrictions parse from a bare object`() = runTest {
        val client = BinanceRestClient(OkHttpClient(), { "k" }, { "s" })
        val parsed = client.parseApiRestrictions("""{"canRead":true,"canTrade":true,"canWithdraw":true}""")
        assertTrue(parsed.canRead)
        assertTrue(parsed.canTrade)
    }

    @Test
    fun `absent restriction fields default permissive`() = runTest {
        val client = BinanceRestClient(OkHttpClient(), { "k" }, { "s" })
        val parsed = client.parseApiRestrictions("""{"uid":12345}""")
        assertTrue(parsed.canRead)
        assertTrue(parsed.canTrade)
    }

    @Test
    fun `market order is refused before any network when write access is off`() = runTest {
        // Paper mode constructs its client exactly like this. The refusal must
        // be local and immediate: no request is built, nothing is signed.
        val client = BinanceRestClient(
            OkHttpClient.Builder().build(),
            { "key" }, { "secret" },
            writeAccess = { false }
        )
        val error = runCatching { client.marketOrder("BTCUSDT", 0.001, "BUY") }
            .exceptionOrNull()
        assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
        assertTrue(
            (error as IllegalStateException).message!!.contains("read-only")
        )
    }
}
