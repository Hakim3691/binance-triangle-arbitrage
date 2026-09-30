package com.hakim3691.bta.core

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A triangle that fills one leg and then fails leaves a real position on the
 * exchange. These tests pin that the app either puts it back itself or says so
 * loudly enough that the operator closes it by hand.
 */
class UnwindOnFailureTest {

    private class Executor : TradeExecutor {
        val placed = mutableListOf<Triple<String, Double, String>>()
        /** Indices in [placed] whose order should be rejected. */
        val rejectAt = mutableSetOf<Int>()

        /**
         * Per-ticker mids, because the leg recalculation divides by the price:
         * with a BTC-sized book on BNBETH the recalculated BNB quantity lands
         * below the symbol's 2-decimal dust and rounds to zero.
         */
        private val mids = mapOf("ETHBTC" to 21_000.0, "BNBETH" to 600.0, "BNBBTC" to 600.0)

        private fun book(mid: Double) = DepthSnapshot(
            bids = LinkedHashMap<Double, Double>().apply {
                for (i in 0 until 10) put(mid * (1.0 - 0.001 * (i + 1)), 1_000_000.0)
            },
            asks = LinkedHashMap<Double, Double>().apply {
                for (i in 0 until 10) put(mid * (1.0 + 0.001 * (i + 1)), 1_000_000.0)
            },
            eventTime = 1000
        )

        override suspend fun placeMarketOrder(ticker: String, quantity: Double, method: String): OrderResponse {
            val index = placed.size
            placed.add(Triple(ticker, quantity, method))
            if (index in rejectAt) return OrderResponse.failed()
            return OrderResponse(
                orderId = index + 1L,
                executedQty = quantity,
                cummulativeQuoteQty = quantity * (mids[ticker] ?: 1.0),
                fills = emptyList()
            )
        }

        override fun getSortedDepth(ticker: String): DepthSnapshot = book(mids[ticker] ?: 1.0)
    }

    private fun trade(): Trade = Trade(
        Relationship("SELL", "ETHBTC", "ETH", "BTC", 5),
        Relationship("BUY", "BNBETH", "BNB", "ETH", 2),
        Relationship("SELL", "BNBBTC", "BNB", "BTC", 6),
        TradeSymbols("BTC", "ETH", "BNB")
    )

    private fun calculated(): CalculatedPosition {
        val t = trade()
        val c = CalculatedPosition(
            trade = t,
            ab = LegCalculation(quantity = 0.01),
            bc = LegCalculation(quantity = 0.1),
            ca = LegCalculation(quantity = 0.1),
            a = AssetLedger(spent = 0.01, earned = 0.0101, delta = 0.0001),
            b = AssetLedger(),
            c = AssetLedger()
        )
        c.percent = 0.5
        c.usedDepth = CalculationNode.TradeDepthSnapshot(
            DepthSnapshot(emptyMap(), emptyMap(), 1000),
            DepthSnapshot(emptyMap(), emptyMap(), 1000),
            DepthSnapshot(emptyMap(), emptyMap(), 1000)
        )
        return c
    }

    @Before
    fun setUp() {
        ExecutionConfig.strategy = "linear"
        ExecutionConfig.preFlightCheckEnabled = false
        ExecutionConfig.executionDeadlineMs = 0
        ExecutionConfig.feePercent = 0.0
        ExecutionConfig.profitThreshold = 0.0
    }

    @After
    fun tearDown() {
        ExecutionConfig.preFlightCheckEnabled = true
        ExecutionConfig.executionDeadlineMs = 0
    }

    @Test
    fun `a rejected middle leg unwinds the leg that already filled`() = runTest {
        val ex = Executor().apply { rejectAt += 1 } // AB fills, BC rejected
        val exec = ArbitrageExecution(ex)

        val state = exec.executeCalculatedPosition(calculated())

        assertTrue(state.abComplete)
        assertFalse(state.bcComplete)
        assertFalse(state.caComplete)
        assertTrue("the filled leg must be reversed", state.unwound)
        assertFalse(state.requiresManualClose)
        // AB, the rejected BC attempt, then the reversal of AB. No CA order
        // may be placed after BC failed - that would compound the imbalance.
        assertEquals(3, ex.placed.size)
        assertEquals("ETHBTC", ex.placed[0].first)
        assertEquals("BNBETH", ex.placed[1].first)
        assertEquals("ETHBTC", ex.placed[2].first)
        assertEquals(
            if (ex.placed[0].third == "SELL") "BUY" else "SELL",
            ex.placed[2].third
        )
    }

    @Test
    fun `a rejected final leg unwinds both filled legs`() = runTest {
        val ex = Executor().apply { rejectAt += 2 } // AB and BC fill, CA rejected
        val exec = ArbitrageExecution(ex)

        val state = exec.executeCalculatedPosition(calculated())

        assertTrue(state.abComplete && state.bcComplete)
        assertFalse(state.caComplete)
        assertTrue(state.unwound)
        assertFalse(state.requiresManualClose)
        // AB, BC, the rejected CA attempt, then BC reversed and AB reversed.
        assertEquals(
            listOf("ETHBTC", "BNBETH", "BNBBTC", "BNBETH", "ETHBTC"),
            ex.placed.map { it.first }
        )
    }

    @Test
    fun `a failed unwind raises the manual-close alarm and names the asset`() = runTest {
        // AB fills, BC rejected, and every reversal after that also fails.
        val ex = Executor().apply { rejectAt += listOf(1, 2) }
        val exec = ArbitrageExecution(ex)

        val state = exec.executeCalculatedPosition(calculated())

        assertTrue("the operator must be told", state.requiresManualClose)
        assertFalse(state.unwound)
        assertTrue(
            "the stranded asset must be named, got " + state.strandedAssets,
            state.strandedAssets.isNotEmpty()
        )
        // The reversal of leg AB was ETHBTC, so what stays open is BTC.
        assertTrue(
            "stranded assets were " + state.strandedAssets,
            state.strandedAssets.contains("BTC")
        )
    }

    @Test
    fun `a fully rejected triangle leaves nothing to unwind`() = runTest {
        val ex = Executor().apply { rejectAt += 0 }
        val exec = ArbitrageExecution(ex)

        val state = exec.executeCalculatedPosition(calculated())

        assertFalse(state.abComplete)
        assertFalse(state.requiresManualClose)
        assertFalse(state.unwound)
        // A triangle that cannot start is not continued.
        assertEquals(1, ex.placed.size)
        assertEquals("ETHBTC", ex.placed[0].first)
    }

    @Test
    fun `a clean triangle is untouched`() = runTest {
        val ex = Executor()
        val exec = ArbitrageExecution(ex)

        val state = exec.executeCalculatedPosition(calculated())

        assertEquals(ExecutionState.Status.COMPLETED, state.status)
        assertEquals(3, ex.placed.size)
        assertFalse(state.requiresManualClose)
    }
}
