package com.hakim3691.bta.scanner

import com.hakim3691.bta.core.AssetLedger
import com.hakim3691.bta.core.CalculationNode
import com.hakim3691.bta.core.DepthSnapshot
import com.hakim3691.bta.core.ExecutionConfig
import com.hakim3691.bta.core.InvestmentSpec
import com.hakim3691.bta.core.LegCalculation
import com.hakim3691.bta.core.MarketCache
import com.hakim3691.bta.core.OrderResponse
import com.hakim3691.bta.core.Relationship
import com.hakim3691.bta.core.SymbolFilter
import com.hakim3691.bta.core.SymbolInfo
import com.hakim3691.bta.core.TradeExecutor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Integration test: exchangeInfo -> triangle discovery -> depth-based analysis
 * -> execution against a scripted executor over a synthetic multi-symbol book.
 */
class IntegrationScanTest {

    private class RecordingExecutor(var depthProvider: (String) -> DepthSnapshot) : TradeExecutor {
        val orders = mutableListOf<Triple<String, Double, String>>()
        var nextResponse: OrderResponse? = null
        override suspend fun placeMarketOrder(ticker: String, quantity: Double, method: String): OrderResponse {
            orders.add(Triple(ticker, quantity, method))
            return nextResponse ?: OrderResponse(1, quantity, quantity * 100, emptyList())
        }
        override fun getSortedDepth(ticker: String): DepthSnapshot = depthProvider(ticker)
    }

    private fun universe(): List<SymbolInfo> = listOf(
        SymbolInfo("ETHBTC", "TRADING", "ETH", "BTC", listOf(SymbolFilter("LOT_SIZE", minQty = "0.00001000"))),
        SymbolInfo("BNBBTC", "TRADING", "BNB", "BTC", listOf(SymbolFilter("LOT_SIZE", minQty = "0.01000000"))),
        SymbolInfo("BNBETH", "TRADING", "BNB", "ETH", listOf(SymbolFilter("LOT_SIZE", minQty = "0.01000000")))
    )

    private fun books(): Map<String, DepthSnapshot> {
        val ethbtc = DepthSnapshot(
            bids = linkedMapOf(0.08 to 5.0, 0.079 to 5.0),
            asks = linkedMapOf(0.0801 to 5.0, 0.0802 to 5.0),
            eventTime = 1_000
        )
        val bnbbtc = DepthSnapshot(
            bids = linkedMapOf(0.0122 to 10.0, 0.0121 to 10.0),
            asks = linkedMapOf(0.0123 to 10.0, 0.0124 to 10.0),
            eventTime = 1_000
        )
        val bnbeth = DepthSnapshot(
            bids = linkedMapOf(0.1524 to 50.0, 0.1520 to 50.0),
            asks = linkedMapOf(0.1528 to 50.0, 0.1530 to 50.0),
            eventTime = 1_000
        )
        return mapOf("ETHBTC" to ethbtc, "BNBBTC" to bnbbtc, "BNBETH" to bnbeth)
    }

    @Before
    fun reset() {
        ExecutionConfig.feePercent = 0.0
        ExecutionConfig.profitThreshold = 0.0
        ExecutionConfig.ageThresholdMs = 10_000
        ExecutionConfig.cap = 0
        ExecutionConfig.strategy = "linear"
        // These tests drive one hand-picked traversal order through the whole
        // pipeline, so mirrored-triangle de-duplication is turned off here and
        // covered on its own in MarketCacheTest.
        ExecutionConfig.dedupeMirroredTriangles = false
        ExecutionConfig.preFlightCheckEnabled = false
        InvestmentSpec.DEFAULTS.clear()
        InvestmentSpec.DEFAULTS["BTC"] = InvestmentSpec("BTC", 0.1, 0.3, 0.1)
    }

    @After
    fun restoreDefaults() {
        ExecutionConfig.dedupeMirroredTriangles = true
        ExecutionConfig.preFlightCheckEnabled = true
    }

    @Test
    fun `triangle discovery through execution produces three market orders`() = runTest {
        val cache = MarketCache()
        val result = cache.initialize(universe(), setOf("BTC"))
        val trade = result.trades.find { it.id == "BTC-ETH-BNB" }
        assertTrue(trade != null)

        val snapshot = CalculationNode.TradeDepthSnapshot(
            books().getValue("ETHBTC"),
            books().getValue("BNBETH"),
            books().getValue("BNBBTC")
        )
        val calculated = CalculationNode.optimize(trade!!, snapshot)
        assertTrue(calculated.usedDepth === snapshot)

        val executor = RecordingExecutor { books().getValue(it) }
        val exec = com.hakim3691.bta.core.ArbitrageExecution(executor)
        val state = exec.executeCalculatedPosition(calculated)

        assertEquals(com.hakim3691.bta.core.ExecutionState.Status.COMPLETED, state.status)
        assertEquals(3, executor.orders.size)
        assertEquals("ETHBTC", executor.orders[0].first)
        assertEquals("BNBETH", executor.orders[1].first)
        assertEquals("BNBBTC", executor.orders[2].first)
        // methods align with the triangle: BUY ETHBTC, BUY BNBETH, SELL BNBBTC
        assertEquals("BUY", executor.orders[0].third)
        assertEquals("BUY", executor.orders[1].third)
        assertEquals("SELL", executor.orders[2].third)
    }

    @Test
    fun `optimize picks the best investment step`() = runTest {
        val cache = MarketCache()
        val result = cache.initialize(universe(), setOf("BTC"))
        val trade = result.trades.first()
        val snapshot = CalculationNode.TradeDepthSnapshot(
            books().getValue("ETHBTC"),
            books().getValue("BNBETH"),
            books().getValue("BNBBTC")
        )
        val best = CalculationNode.optimize(trade, snapshot)

        // Recompute each step and confirm the best percent equals max over steps
        var maxPercent = Double.NEGATIVE_INFINITY
        var q = InvestmentSpec.DEFAULTS["BTC"]!!.min
        while (q <= InvestmentSpec.DEFAULTS["BTC"]!!.max) {
            maxPercent = maxOf(maxPercent, CalculationNode.calculate(q, trade, snapshot).percent)
            q += InvestmentSpec.DEFAULTS["BTC"]!!.step
        }
        assertEquals(maxPercent, best.percent, 0.0)
    }
}
