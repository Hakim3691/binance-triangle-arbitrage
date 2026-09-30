package com.hakim3691.bta.live

import com.hakim3691.bta.market.BinanceRestClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two live-order safety properties that stop a lost response from
 * becoming a silent double position, and a drifted clock from failing every
 * order: ids are chosen before the request is sent, and signed timestamps
 * ride the measured server offset.
 */
class LiveOrderSafetyTest {

    @Test
    fun `client order ids are unique and prefixed`() {
        val a = LiveTradingSession.clientOrderId("ETHBTC", 1_000L, 1L)
        val b = LiveTradingSession.clientOrderId("ETHBTC", 1_000L, 2L)
        assertNotEquals(a, b)
        assertEquals("bta-ethbtc-1000-1", a)
    }

    @Test
    fun `signed timestamps carry the measured server offset`() {
        val client = BinanceRestClient(okhttp3.OkHttpClient(), { "k" }, { "s" })
        assertEquals(0L, client.serverTimeOffsetMs)
        // Binance reports itself 1500ms ahead of the device.
        val local = System.currentTimeMillis()
        client.updateServerTimeOffset(local + 1500, local)
        val skew = client.signedTimestamp() - System.currentTimeMillis()
        // ~1500ms ahead, plus or minus the time this test spent executing.
        assertTrue("offset was $skew", skew in 1400..1600)
        // Repeated measurements are smoothed, not replaced.
        client.updateServerTimeOffset(local, local)
        val smoothed = client.serverTimeOffsetMs
        assertTrue("smoothed $smoothed must sit between 0 and 1500", smoothed in 1..1499)
    }
}
