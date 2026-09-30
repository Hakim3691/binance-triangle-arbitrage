package com.hakim3691.bta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Triangle discovery tests, vectorized against the original MarketCache.js
 * createTrade/getRelationship semantics.
 */
class MarketCacheTest {

    private fun symbols(): List<SymbolInfo> = listOf(
        SymbolInfo("ETHBTC", "TRADING", "ETH", "BTC", listOf(SymbolFilter("LOT_SIZE", minQty = "0.00001"))),
        SymbolInfo("BNBETH", "TRADING", "BNB", "ETH", listOf(SymbolFilter("LOT_SIZE", minQty = "0.01000000"))),
        SymbolInfo("BNBBTC", "TRADING", "BNB", "BTC", listOf(SymbolFilter("LOT_SIZE", minQty = "0.01000000"))),
        SymbolInfo("BTCUSDT", "TRADING", "BTC", "USDT", listOf(SymbolFilter("LOT_SIZE", minQty = "0.00000100"))),
        SymbolInfo("ETHUSDT", "TRADING", "ETH", "USDT", listOf(SymbolFilter("LOT_SIZE", minQty = "0.00010000"))),
        SymbolInfo("LTCBTC", "TRADING", "LTC", "BTC", listOf(SymbolFilter("LOT_SIZE", minQty = "0.00100000"))),
        SymbolInfo("LTCUSDT", "TRADING", "LTC", "USDT", listOf(SymbolFilter("LOT_SIZE", minQty = "0.00100000"))),
        SymbolInfo("BREAKSYMBOL", "BREAK", "BRK", "USDT", listOf(SymbolFilter("LOT_SIZE", minQty = "1.00000000"))),
        SymbolInfo("BRKUSDT", "BREAK", "BRK", "USDT", listOf(SymbolFilter("LOT_SIZE", minQty = "1.00000000")))
    )

    @Test
    fun `discovers triangles with correct methods for every direction`() {
        val cache = MarketCache()
        val result = cache.initialize(symbols(), setOf("BTC"))

        // De-duplication keeps the canonically ordered traversal (b < c), so
        // BTC-BNB-ETH survives and its mirror BTC-ETH-BNB is dropped.
        val btcEthBnb = result.trades.find { it.id == "BTC-BNB-ETH" }
        assertNotNull(btcEthBnb)
        // getRelationship(BTC, BNB): BTCBNB absent, BNBBTC present -> BUY on BNBBTC
        // getRelationship(BNB, ETH): BNBETH present -> SELL on BNBETH
        // getRelationship(ETH, BTC): ETHBTC present -> SELL on ETHBTC
        assertEquals("BUY", btcEthBnb!!.ab.method)
        assertEquals("BNBBTC", btcEthBnb.ab.ticker)
        assertEquals("SELL", btcEthBnb.bc.method)
        assertEquals("BNBETH", btcEthBnb.bc.ticker)
        assertEquals("SELL", btcEthBnb.ca.method)
        assertEquals("ETHBTC", btcEthBnb.ca.ticker)

        // dustDecimals derived from minQty: indexOf('1') in the LOT_SIZE
        // minQty string, minus 1.
        // ETHBTC "0.00001"     -> indexOf('1') = 6 -> 5
        // BNBBTC "0.01000000" -> indexOf('1') = 3 -> 2
        assertEquals(2, btcEthBnb.ab.dustDecimals)
        assertEquals(5, btcEthBnb.ca.dustDecimals)
    }

    @Test
    fun `non-trading symbols are excluded`() {
        val cache = MarketCache()
        val result = cache.initialize(symbols(), setOf("BRK"))
        assertEquals(0, result.trades.size)
    }

    @Test
    fun `whitelist restricts discovered triangles`() {
        val all = MarketCache(whitelist = emptySet()).initialize(symbols(), setOf("BTC"))
        val filtered = MarketCache(whitelist = setOf("BTC", "LTC", "USDT"))
            .initialize(symbols(), setOf("BTC"))

        assertTrue(filtered.trades.size < all.trades.size)
        assertTrue(filtered.trades.all { t ->
            t.symbol.a in setOf("BTC", "LTC", "USDT") &&
                t.symbol.b in setOf("BTC", "LTC", "USDT") &&
                t.symbol.c in setOf("BTC", "LTC", "USDT")
        })
    }

    @Test
    fun `execution template gates method types`() {
        val buyOnly = MarketCache(executionTemplate = listOf("BUY", "BUY", "BUY"))
            .initialize(symbols(), setOf("BTC"))
        buyOnly.trades.forEach { t ->
            assertEquals("BUY", t.ab.method)
            assertEquals("BUY", t.bc.method)
            assertEquals("BUY", t.ca.method)
        }
        // BTC-BNB-ETH is BUY,SELL,SELL -> excluded by the BUY-only template
        assertNull(buyOnly.trades.find { it.id == "BTC-BNB-ETH" })
    }

    @Test
    fun `related trades index contains every trade touching a watched ticker`() {
        val cache = MarketCache()
        val result = cache.initialize(symbols(), setOf("BTC"))

        for (ticker in result.watching) {
            val related = result.relatedTrades[ticker]!!
            assertTrue(related.isNotEmpty())
            // Every related trade must actually contain this ticker
            related.forEach { t ->
                assertTrue(
                    listOf(t.ab.ticker, t.bc.ticker, t.ca.ticker).contains(ticker)
                )
            }
            // Related tickers must include all tickers of each related trade
            val relatedTickers = result.relatedTickers[ticker]!!
            related.forEach { t ->
                listOf(t.ab.ticker, t.bc.ticker, t.ca.ticker).forEach { other ->
                    assertTrue(relatedTickers.contains(other))
                }
            }
        }
    }

    @Test
    fun `both traversal directions are discovered by default`() {
        // The mirror crosses the opposite side of the same three books, so it
        // is a different trade with a different return - not an optimizable
        // duplicate. Dropping it silently blinded the scanner to half the
        // profitable opportunities, whichever direction the dislocation ran.
        ExecutionConfig.dedupeMirroredTriangles = false
        val cache = MarketCache()
        val result = cache.initialize(symbols(), setOf("BTC"))
        val ids = result.trades.map { it.id }.toSet()
        result.trades.forEach { t ->
            val mirror = "${t.symbol.a}-${t.symbol.c}-${t.symbol.b}"
            assertTrue("mirror $mirror of ${t.id} must be present", mirror in ids)
        }
        assertEquals(0, cache.resultMirrorsSkipped)
    }

    @Test
    fun `a dislocation in either direction is reachable`() {
        // The reason the mirror matters: identical books cannot tell the two
        // directions apart, but a real market is never balanced. Whichever
        // direction the dislocation favours must exist in the universe.
        ExecutionConfig.dedupeMirroredTriangles = false
        val cache = MarketCache()
        val both = cache.initialize(symbols(), setOf("BTC")).trades.associateBy { it.id }
        assertTrue("BTC-ETH-BNB" in both)
        assertTrue("BTC-BNB-ETH" in both)
        // Same three markets, opposite leg order - i.e. opposite book sides.
        val forward = both.getValue("BTC-ETH-BNB")
        val reverse = both.getValue("BTC-BNB-ETH")
        assertEquals(
            listOf(forward.ab.ticker, forward.bc.ticker, forward.ca.ticker).sorted(),
            listOf(reverse.ab.ticker, reverse.bc.ticker, reverse.ca.ticker).sorted()
        )
        // Both directions start by buying the other asset with BTC, so the
        // direction difference shows on the middle leg: forward buys BNBETH,
        // reverse sells it. Same tickers, opposite book sides.
        assertTrue(forward.bc.method != reverse.bc.method)
        ExecutionConfig.dedupeMirroredTriangles = true
    }

    @Test
    fun `opt-in de-duplication halves the count and accounts for every mirror`() {
        val fullCache = MarketCache()
        ExecutionConfig.dedupeMirroredTriangles = false
        val full = fullCache.initialize(symbols(), setOf("BTC"))
        val dedupeCache = MarketCache()
        ExecutionConfig.dedupeMirroredTriangles = true
        val deduped = dedupeCache.initialize(symbols(), setOf("BTC"))
        ExecutionConfig.dedupeMirroredTriangles = false

        assertEquals(full.trades.size, deduped.trades.size + dedupeCache.resultMirrorsSkipped)
        assertEquals(full.trades.size, deduped.trades.size * 2)
    }

    @Test
    fun `de-duplication is off by default`() {
        assertFalse(ExecutionConfig.dedupeMirroredTriangles)
    }

    @Test
    fun `both orientations use the same three markets`() {
        ExecutionConfig.dedupeMirroredTriangles = false
        val cache = MarketCache()
        val both = cache.initialize(symbols(), setOf("BTC"))
        ExecutionConfig.dedupeMirroredTriangles = true
        val one = cache.initialize(symbols(), setOf("BTC"))
        ExecutionConfig.dedupeMirroredTriangles = false

        val btcBnbEth = both.trades.find { it.id == "BTC-BNB-ETH" }!!
        val mirror = both.trades.find { it.id == "BTC-ETH-BNB" }!!
        assertEquals(
            listOf(mirror.ab.ticker, mirror.bc.ticker, mirror.ca.ticker).sorted(),
            listOf(btcBnbEth.ab.ticker, btcBnbEth.bc.ticker, btcBnbEth.ca.ticker).sorted()
        )
        // Opt-in de-duplication keeps the canonical direction only.
        assertNull(one.trades.find { it.id == "BTC-ETH-BNB" })
    }

    @Test
    fun `multi-base discovery covers each base`() {
        val result = MarketCache().initialize(symbols(), setOf("BTC", "USDT"))
        val bases = result.trades.map { it.symbol.a }.toSet()
        assertTrue(bases.contains("BTC"))
        assertTrue(bases.contains("USDT"))
    }
}
