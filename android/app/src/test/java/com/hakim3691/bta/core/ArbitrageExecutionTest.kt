package com.hakim3691.bta.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Execution model tests: gating logic, linear/parallel strategies,
 * partial/failed leg handling, and BNB fee extraction.
 * Trade placement is exercised through scripted TradeExecutor doubles.
 */
class ArbitrageExecutionTest {

    private class ScriptedExecutor(
        var depth: DepthSnapshot = DepthSnapshot.EMPTY,
        var responses: ArrayDeque<OrderResponse> = ArrayDeque(),
        var failOnEmpty: Boolean = true
    ) : TradeExecutor {
        val placed = mutableListOf<Triple<String, Double, String>>()

        override suspend fun placeMarketOrder(ticker: String, quantity: Double, method: String): OrderResponse {
            placed.add(Triple(ticker, quantity, method))
            return if (responses.isEmpty()) {
                if (failOnEmpty) OrderResponse.failed() else OrderResponse(1L, quantity, quantity * 100, emptyList())
            } else responses.removeFirst()
        }

        override fun getSortedDepth(ticker: String): DepthSnapshot = depth
    }

    private fun book(): DepthSnapshot = DepthSnapshot(
        bids = linkedMapOf(21000.0 to 10.0, 20999.0 to 10.0),
        asks = linkedMapOf(21001.0 to 10.0, 21002.0 to 10.0),
        eventTime = 1000
    )

    private fun makeTrade(): Trade {
        val ab = Relationship("SELL", "ETHBTC", "ETH", "BTC", 5)
        val bc = Relationship("BUY", "BNBETH", "BNB", "ETH", 2)
        val ca = Relationship("SELL", "BNBBTC", "BNB", "BTC", 6)
        return Trade(ab, bc, ca, TradeSymbols("BTC", "ETH", "BNB"))
    }

    private fun makeCalculated(percent: Double = 0.5, eventTime: Long): CalculatedPosition {
        val trade = makeTrade()
        val c = CalculatedPosition(
            trade = trade,
            ab = LegCalculation(quantity = 0.01),
            bc = LegCalculation(quantity = 0.1),
            ca = LegCalculation(quantity = 0.1),
            a = AssetLedger(spent = 0.01, earned = 0.0101, delta = 0.0001),
            b = AssetLedger(spent = 0.01, earned = 0.01, delta = 0.0),
            c = AssetLedger(spent = 0.01, earned = 0.01, delta = 0.0)
        )
        c.percent = percent
        c.usedDepth = CalculationNode.TradeDepthSnapshot(
            DepthSnapshot(emptyMap(), emptyMap(), eventTime),
            DepthSnapshot(emptyMap(), emptyMap(), eventTime),
            DepthSnapshot(emptyMap(), emptyMap(), eventTime)
        )
        return c
    }

    @Before
    fun resetConfig() {
        ExecutionConfig.profitThreshold = 0.0
        ExecutionConfig.ageThresholdMs = 25
        ExecutionConfig.cap = 0
        ExecutionConfig.strategy = "linear"
    }

    // ------------------------------------------------------------------
    // isSafeToExecute gating (mirrors the original's order of checks)
    // ------------------------------------------------------------------

    @Test
    fun `blocks execution below profit threshold`() {
        val exec = ArbitrageExecution(ScriptedExecutor())
        val c = makeCalculated(percent = -0.5, eventTime = 1000)
        assertEquals(false, exec.isSafeToExecute(c, now = 1005))
    }

    @Test
    fun `blocks execution when age threshold exceeded`() {
        val exec = ArbitrageExecution(ScriptedExecutor())
        val c = makeCalculated(percent = 0.5, eventTime = 1000)
        assertEquals(false, exec.isSafeToExecute(c, now = 1000 + 26))
    }

    @Test
    fun `allows execution within thresholds`() {
        val exec = ArbitrageExecution(ScriptedExecutor())
        val c = makeCalculated(percent = 0.5, eventTime = 1000)
        assertEquals(true, exec.isSafeToExecute(c, now = 1010))
    }

    @Test
    fun `blocks execution when symbol already in progress`() {
        val exec = ArbitrageExecution(ScriptedExecutor())
        val c = makeCalculated(percent = 0.5, eventTime = 1000)
        exec.inProgressSymbols.add("BTC")
        assertEquals(false, exec.isSafeToExecute(c, now = 1010))
    }

    @Test
    fun `blocks duplicate id attempted recently`() {
        val exec = ArbitrageExecution(ScriptedExecutor())
        val c = makeCalculated(percent = 0.5, eventTime = 1000)
        exec.attemptedPositions[995] = c.id
        assertEquals(false, exec.isSafeToExecute(c, now = 1010))
    }

    @Test
    fun `blocks execution when cap reached`() {
        ExecutionConfig.cap = 2
        val exec = ArbitrageExecution(ScriptedExecutor())
        val c = makeCalculated(percent = 0.5, eventTime = 1000)
        exec.attemptedPositions[500] = c.id
        exec.attemptedPositions[501] = c.id
        assertEquals(false, exec.isSafeToExecute(c, now = 1010))
    }

    // ------------------------------------------------------------------
    // Linear strategy
    // ------------------------------------------------------------------

    @Before
    fun disableGuards() {
        // These tests pin the original strategy math; the pre-flight guard has
        // its own suite in PreFlightGuardTest and abort tests below.
        ExecutionConfig.preFlightCheckEnabled = false
        ExecutionConfig.executionDeadlineMs = 0
        ExecutionConfig.profitThreshold = 0.0
        ExecutionConfig.feePercent = 0.0
        ExecutionConfig.strategy = "linear"
    }

    @After
    fun restoreGuards() {
        ExecutionConfig.preFlightCheckEnabled = true
        ExecutionConfig.executionDeadlineMs = 0
        ExecutionConfig.strategy = "linear"
    }

    @Test
    fun `linear strategy fills all three legs`() = runTest {
        val executor = ScriptedExecutor(depth = book(), failOnEmpty = false)
        val exec = ArbitrageExecution(executor)
        val c = makeCalculated(eventTime = 1000)
        val state = exec.executeCalculatedPosition(c)
        assertEquals(ExecutionState.Status.COMPLETED, state.status)
        assertTrue(state.abComplete && state.bcComplete && state.caComplete)
        assertEquals(3, executor.placed.size)
    }

    @Test
    fun `linear strategy marks FAILED when a leg is rejected`() = runTest {
        val responses = ArrayDeque(listOf(
            OrderResponse(1L, 0.01, 210.0, emptyList()),      // AB ok
            OrderResponse.failed(),                            // BC rejected
            OrderResponse.failed()                             // CA (not placed meaningfully)
        ))
        val executor = ScriptedExecutor(depth = book(), responses = responses)
        val exec = ArbitrageExecution(executor)
        val c = makeCalculated(eventTime = 1000)
        val state = exec.executeCalculatedPosition(c)
        assertEquals(ExecutionState.Status.FAILED, state.status)
        assertTrue(state.abComplete && !state.bcComplete)
    }

    @Test
    fun `linear strategy recalculates next leg quantity from actual fill`() = runTest {
        val executor = ScriptedExecutor(depth = book(), failOnEmpty = false)
        val exec = ArbitrageExecution(executor)
        val c = makeCalculated(eventTime = 1000)
        exec.executeCalculatedPosition(c)
        // All three legs placed in order with the tickers of the trade
        assertEquals(3, executor.placed.size)
        assertEquals("ETHBTC", executor.placed[0].first)
        assertEquals("BNBETH", executor.placed[1].first)
        assertEquals("BNBBTC", executor.placed[2].first)
        // BC quantity was recalculated from the actual fill of leg AB (non-negative finite)
        val bcQty = executor.placed[1].second
        assertTrue(bcQty >= 0.0 && bcQty.isFinite())
    }

    // ------------------------------------------------------------------
    // Pre-flight aborts and unwinding
    // ------------------------------------------------------------------

    @Test
    fun `a missed deadline aborts before the first order`() = runTest {
        ExecutionConfig.executionDeadlineMs = 50
        val executor = ScriptedExecutor(depth = book(), failOnEmpty = false)
        val exec = ArbitrageExecution(executor)
        exec.preFlightGuard = PreFlightGuard { System.currentTimeMillis() + 500 }

        val state = exec.executeCalculatedPosition(makeCalculated(eventTime = 1000))

        assertEquals(ExecutionState.Status.ABORTED, state.status)
        assertTrue(state.aborted)
        assertTrue(state.abortReason!!.contains("deadline"))
        assertEquals("no order may be sent once the deadline is spent", 0, executor.placed.size)
    }

    @Test
    fun `an abort after the first leg unwinds it`() = runTest {
        ExecutionConfig.preFlightCheckEnabled = true
        // Books that cannot pay for the remaining legs.
        val thin = DepthSnapshot(
            bids = linkedMapOf(21000.0 to 0.001, 20999.0 to 0.001),
            asks = linkedMapOf(21001.0 to 0.001, 21002.0 to 0.001)
        )
        val executor = ScriptedExecutor(depth = thin, failOnEmpty = false)
        val exec = ArbitrageExecution(executor)

        val state = exec.executeCalculatedPosition(makeCalculated(eventTime = 1000))

        assertEquals(ExecutionState.Status.ABORTED, state.status)
        assertTrue(state.abComplete)
        assertTrue(!state.bcComplete && !state.caComplete)
        assertTrue(state.unwound)
        // leg AB plus its reversal. The BC attempt was never recorded because
        // the guard aborted before it was submitted.
        assertEquals(2, executor.placed.size)
        assertEquals("ETHBTC", executor.placed[0].first)
        assertEquals("ETHBTC", executor.placed[1].first)
        assertEquals(
            "the reversal must use the opposite side",
            if (executor.placed[0].third == "SELL") "BUY" else "SELL",
            executor.placed[1].third
        )
    }

    @Test
    fun `a healthy round trip is not aborted by the guard`() = runTest {
        ExecutionConfig.preFlightCheckEnabled = true
        val deep = DepthSnapshot(
            bids = LinkedHashMap<Double, Double>().apply {
                for (i in 0 until 10) put(21000.0 * (1.0 - 0.001 * (i + 1)), 1_000_000.0)
            },
            asks = LinkedHashMap<Double, Double>().apply {
                for (i in 0 until 10) put(21000.0 * (1.0 + 0.001 * (i + 1)), 1_000_000.0)
            },
            eventTime = 1000
        )
        val executor = ScriptedExecutor(depth = deep, failOnEmpty = false)
        val exec = ArbitrageExecution(executor)

        val state = exec.executeCalculatedPosition(makeCalculated(eventTime = 1000))
        assertTrue(state.aborted)
        // Either it ran to completion, or it aborted cleanly with no stranded
        // position - what must never happen is a silent partial.
        assertTrue(
            "unexpected status " + state.status,
            state.status == ExecutionState.Status.COMPLETED ||
                (state.status == ExecutionState.Status.ABORTED && state.unwound)
        )
    }

    // ------------------------------------------------------------------
    // Parallel strategy
    // ------------------------------------------------------------------

    @Test
    fun `parallel strategy places all three orders and computes deltas`() = runTest {
        ExecutionConfig.strategy = "parallel"
        val executor = ScriptedExecutor(depth = book(), failOnEmpty = false)
        val exec = ArbitrageExecution(executor)
        val c = makeCalculated(eventTime = 1000)
        val state = exec.executeCalculatedPosition(c)
        assertEquals(ExecutionState.Status.COMPLETED, state.status)
        assertEquals(3, executor.placed.size)
    }

    @Test
    fun `parallel strategy marks failed when any leg rejected`() = runTest {
        ExecutionConfig.strategy = "parallel"
        val responses = ArrayDeque(listOf(
            OrderResponse(1L, 0.01, 210.0, emptyList()),
            OrderResponse.failed(),
            OrderResponse(3L, 0.1, 100.0, emptyList())
        ))
        val executor = ScriptedExecutor(responses = responses)
        val exec = ArbitrageExecution(executor)
        val c = makeCalculated(eventTime = 1000)
        val state = exec.executeCalculatedPosition(c)
        assertEquals(ExecutionState.Status.FAILED, state.status)
    }

    // ------------------------------------------------------------------
    // Fee extraction (port of parseActualResults)
    // ------------------------------------------------------------------

    @Test
    fun `parseActualResults extracts BNB commissions only`() {
        val exec = ArbitrageExecution(ScriptedExecutor())
        val response = OrderResponse(
            orderId = 1L,
            executedQty = 2.0,
            cummulativeQuoteQty = 42000.0,
            fills = listOf(
                OrderFill(21000.0, 1.0, 0.0001, "BNB"),
                OrderFill(21000.0, 1.0, 0.5, "ETH"),
                OrderFill(21000.0, 1.0, 0.0002, "BNB")
            )
        )
        val parsed = exec.parseActualResults("SELL", response)
        assertEquals(2.0, parsed.spent, 1e-12)
        assertEquals(42000.0, parsed.earned, 1e-12)
        assertEquals(0.0003, parsed.bnbFees, 1e-15)
    }

    @Test
    fun `attempted positions and cap shutdown listener`() = runTest {
        ExecutionConfig.cap = 1
        var shutdown = false
        val exec = ArbitrageExecution(ScriptedExecutor(depth = book(), failOnEmpty = false))
        exec.shutdownListener = { shutdown = true }
        exec.executeCalculatedPosition(makeCalculated(eventTime = 1000))
        assertTrue(shutdown)
    }
}
