package com.hakim3691.bta.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sharding tests for the combined-stream subscription.
 *
 * A universe discovered from the whole exchange is larger than Binance's
 * per-connection stream cap, and the stream names travel in the URL - so the
 * subscription has to be split without losing or duplicating a single ticker.
 */
class WebSocketShardTest {

    @Test
    fun `a whole-exchange universe shards under both limits`() {
        // 1374 trading symbols is the live exchangeInfo size.
        val symbols = (1..1374).map { "SYM$it" }
        val shards = BinanceWebSocketClient.shard(symbols)

        assertEquals(3, shards.size)
        assertTrue(shards.all { it.size <= BinanceWebSocketClient.MAX_STREAMS_PER_SOCKET })
        assertTrue(shards.all { it.size <= BinanceWebSocketClient.MAX_COMBINED_STREAMS })
        // Nothing lost, nothing duplicated, order preserved.
        assertEquals(symbols, shards.flatten())
    }

    @Test
    fun `a small universe stays on one socket`() {
        val shards = BinanceWebSocketClient.shard(List(325) { "SYM$it" })
        assertEquals(1, shards.size)
        assertEquals(325, shards[0].size)
    }

    @Test
    fun `exact multiples do not produce an empty shard`() {
        val symbols = List(1000) { "SYM$it" }
        val shards = BinanceWebSocketClient.shard(symbols)
        assertEquals(2, shards.size)
        assertTrue(shards.none { it.isEmpty() })
        assertEquals(symbols, shards.flatten())
    }
}
