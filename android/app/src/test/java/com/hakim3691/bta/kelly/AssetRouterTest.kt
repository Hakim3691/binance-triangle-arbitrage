package com.hakim3691.bta.kelly

import com.hakim3691.bta.core.DepthSnapshot
import com.hakim3691.bta.paper.PaperTradingEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 4b: converting an asset to USDT through the live pair graph when no
 * direct book exists - the settlement path an all-asset universe requires.
 */
class AssetRouterTest {

    private lateinit var paper: PaperTradingEngine
    private lateinit var router: AssetRouter

    /**
     * ZK has no ZK/USDT market: the only way out of ZK is ZK/BTC -> BTC/USDT.
     * The two-hop fill must be priced from the real books, not a mid estimate.
     */
    private fun books(): Map<String, DepthSnapshot> = mapOf(
        "ZKBTC" to DepthSnapshot(
            bids = linkedMapOf(0.00001 to 100_000.0),
            asks = linkedMapOf(0.0000101 to 100_000.0),
            eventTime = 1_000
        ),
        "BTCUSDT" to DepthSnapshot(
            bids = linkedMapOf(50000.0 to 500.0),
            asks = linkedMapOf(50001.0 to 500.0),
            eventTime = 1_000
        )
    )

    @Before
    fun setup() {
        PaperTradingEngine.paperUniverse.clear()
        PaperTradingEngine.paperUniverse["ZKBTC"] = "ZK" to "BTC"
        PaperTradingEngine.paperUniverse["BTCUSDT"] = "BTC" to "USDT"
        com.hakim3691.bta.core.ExecutionConfig.feePercent = 0.0
        paper = PaperTradingEngine(
            depthProvider = { books()[it] },
            startingBalance = mapOf("USDT" to 1000.0, "ZK" to 100.0),
            latencyMs = 0,
            slippagePercent = 0.0
        )
        router = AssetRouter(paper)
    }

    @Test
    fun `two hop route sells through the intermediate book`() = runTest {
        val usdt = router.convert("ZK", 100.0, "USDT")
        // 100 ZK @ 0.00001 = 0.001 BTC; 0.001 BTC @ 50000 = 50 USDT
        assertEquals(50.0, usdt, 1e-6)
        assertEquals(0.0, paper.balances.getOrDefault("ZK", 0.0), 1e-9)
        assertEquals(1050.0, paper.balances.getOrDefault("USDT", 0.0), 1e-6)
    }

    @Test
    fun `direct hop when a usdt book exists`() = runTest {
        val usdt = router.convert("USDT", 100.0, "USDT")
        assertEquals(100.0, usdt, 1e-9)
    }

    @Test
    fun `no route returns zero and keeps the position`() = runTest {
        PaperTradingEngine.paperUniverse.remove("BTCUSDT")
        val usdt = router.convert("ZK", 100.0, "USDT")
        assertEquals(0.0, usdt, 1e-9)
        // Nothing was sold: the preflight saw the dead middle book.
        assertEquals(100.0, paper.balances.getOrDefault("ZK", 0.0), 1e-9)
    }

    @Test
    fun `empty intermediate book aborts before selling anything`() = runTest {
        val starved = PaperTradingEngine(
            depthProvider = { ticker -> if (ticker == "BTCUSDT") null else books()[ticker] },
            startingBalance = mapOf("USDT" to 1000.0, "ZK" to 100.0),
            latencyMs = 0,
            slippagePercent = 0.0
        )
        val usdt = AssetRouter(starved).convert("ZK", 100.0, "USDT")
        assertEquals(0.0, usdt, 1e-9)
        assertEquals(100.0, starved.balances.getOrDefault("ZK", 0.0), 1e-9)
    }

    @Test
    fun `route is found in either direction of a book`() = runTest {
        // USDT -> BTC (BUY on BTCUSDT, quote side) -> ZK (BUY on ZKBTC, quote
        // side): the graph must be traversable against both base and quote.
        val bought = router.convert("USDT", 900.0, "ZK")
        // ~900 USDT at 50001 USDT/BTC then 0.0000101 BTC/ZK ~= 1782 ZK
        assertTrue(
            "bought $bought ZK with 900 USDT; fills=" +
                paper.fillLog.joinToString { "${it.ticker}:${it.side}:${it.quantity}@${it.price}" },
            bought in 1700.0..1850.0
        )
    }
}
