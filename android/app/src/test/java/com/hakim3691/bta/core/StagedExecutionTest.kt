package com.hakim3691.bta.core

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The staged executor end to end: identify -> arm -> wait on book updates ->
 * fire, with the real optimizer sizing the triangle from the books as they are
 * at that moment.
 *
 * This is the loop the scanner runs, exercised without the websocket, so the
 * two promises the feature makes can be checked directly: an opportunity is
 * never sent at a loss, and patience is only ever spent while the spread is
 * tight.
 */
class StagedExecutionTest {

    private var now = 1_000_000L

    private class RecordingExecutor : TradeExecutor {
        val orders = mutableListOf<Triple<String, Double, String>>()
        var books: Map<String, DepthSnapshot> = emptyMap()
        override suspend fun placeMarketOrder(
            ticker: String,
            quantity: Double,
            method: String
        ): OrderResponse {
            orders.add(Triple(ticker, quantity, method))
            return OrderResponse(orders.size.toLong(), quantity, quantity, emptyList())
        }

        override fun getSortedDepth(ticker: String): DepthSnapshot =
            books[ticker] ?: DepthSnapshot.EMPTY
    }

    /** 0.15% of dislocation in this triangle's favour, applied per leg. */
    private val EDGE = 0.0015

    private val executor = RecordingExecutor()
    private val registry = ArmedOpportunityBook({ 8 }) { now }
    private lateinit var execution: ArbitrageExecution
    private lateinit var trade: Trade

    @Before
    fun setUp() {
        now = 1_000_000L
        ExecutionConfig.feePercent = 0.10
        ExecutionConfig.profitThreshold = 0.30
        ExecutionConfig.armingMarginPercent = 0.10
        ExecutionConfig.abandonMarginPercent = 0.05
        ExecutionConfig.armTtlMs = 2000
        ExecutionConfig.ageThresholdMs = 5_000
        ExecutionConfig.microstructureGatesEnabled = true
        ExecutionConfig.spreadTightMaxBps = 8.0
        ExecutionConfig.imbalanceMaxAbs = 0.35
        ExecutionConfig.cadenceMaxInterArrivalMs = 750
        ExecutionConfig.executionDeadlineMs = 0
        ExecutionConfig.preFlightCheckEnabled = false
        ExecutionConfig.cap = 0
        InvestmentSpec.DEFAULTS.clear()
        InvestmentSpec.DEFAULTS["BTC"] = InvestmentSpec("BTC", 1.0, 1.0, 0.5)
        ExecutionConfig.dedupeMirroredTriangles = false
        trade = MarketCache().initialize(symbols(), setOf("BTC")).trades.first { it.id == "BTC-ETH-BNB" }
        execution = ArbitrageExecution(executor)
    }

    @After
    fun tearDown() {
        ExecutionConfig.stagedExecutionEnabled = true
        InvestmentSpec.DEFAULTS.clear()
    }

    private fun symbols() = listOf(
        SymbolInfo("ETHBTC", "TRADING", "ETH", "BTC", listOf(SymbolFilter("LOT_SIZE", minQty = "0.00001"))),
        SymbolInfo("BNBETH", "TRADING", "BNB", "ETH", listOf(SymbolFilter("LOT_SIZE", minQty = "0.01000000"))),
        SymbolInfo("BNBBTC", "TRADING", "BNB", "BTC", listOf(SymbolFilter("LOT_SIZE", minQty = "0.01000000")))
    )

    /**
     * Ten levels a side around [mid]. [spreadStep] sets the top-of-book spread
     * (0.0001 ~ 2bps, 0.0008 ~ 16bps), the quantities set the imbalance, and
     * [midBoost] shifts the whole book to create the arbitrage - so the
     * economics and both microstructure gates can be driven independently of
     * one another. The shift is applied to both sides so the book is never
     * left crossed.
     */
    private fun book(
        mid: Double,
        spreadStep: Double = 0.0001,
        bidQty: Double = 1e6,
        askQty: Double = 1e6,
        midBoost: Double = 0.0
    ): DepthSnapshot {
        val centre = mid * (1.0 + midBoost)
        return DepthSnapshot(
            bids = LinkedHashMap<Double, Double>().apply {
                for (i in 0 until 10) put(centre * (1.0 - spreadStep * (i + 1)), bidQty)
            },
            asks = LinkedHashMap<Double, Double>().apply {
                for (i in 0 until 10) put(centre * (1.0 + spreadStep * (i + 1)), askQty)
            },
            eventTime = now
        )
    }

    /**
     * A triangle that is genuinely worth trading: a small real dislocation
     * favouring each leg, so the projected percent clears the fire bar with
     * room to spare and the microstructure gates are the only thing that can
     * stop it going out.
     */
    private fun healthy(spreadStep: Double = 0.0001, bidQty: Double = 1e6, askQty: Double = 1e6) =
        mapOf(
            "ETHBTC" to book(0.015, spreadStep, bidQty, askQty, midBoost = -EDGE),
            "BNBETH" to book(0.302, spreadStep, bidQty, askQty, midBoost = -EDGE),
            "BNBBTC" to book(0.00456, spreadStep, bidQty, askQty, midBoost = EDGE)
        )

    /** The same books after the BTC/BNB market moved against the triangle. */
    private fun againstUs() = mapOf(
        "ETHBTC" to book(0.015, midBoost = -EDGE),
        "BNBETH" to book(0.302, midBoost = -EDGE),
        "BNBBTC" to book(0.00350, midBoost = EDGE)
    )

    private fun snapshot(books: Map<String, DepthSnapshot>) = CalculationNode.TradeDepthSnapshot(
        books.getValue(trade.ab.ticker),
        books.getValue(trade.bc.ticker),
        books.getValue(trade.ca.ticker)
    )

    private fun percent(books: Map<String, DepthSnapshot>): Double =
        CalculationNode.optimize(trade, snapshot(books)).percent

    /**
     * One websocket tick for this triangle: re-size it from the books as they
     * are now, arm it if it is new, and either hold or send. Mirrors what
     * ScannerController.armOrExecute does.
     */
    private suspend fun tick(books: Map<String, DepthSnapshot>, cadenceMs: Double = 120.0): ExecutionGates.Verdict {
        executor.books = books
        val calculated = CalculationNode.optimize(trade, snapshot(books))
        val features = Microstructure.triangleFeatures(trade, books, { cadenceMs }, now)!!
        if (!registry.isArmed(trade.id)) {
            registry.arm(
                id = trade.id,
                ttlMs = ExecutionConfig.armTtlMs,
                fireBar = ExecutionConfig.profitThreshold + ExecutionConfig.armingMarginPercent,
                abandonBar = ExecutionConfig.profitThreshold - ExecutionConfig.abandonMarginPercent,
                percent = calculated.percent
            )
        }
        val arm = registry.armOf(trade.id)!!
        val verdict = ExecutionGates.evaluate(
            ExecutionGates.Input(
                features = features,
                projectedPercent = calculated.percent,
                armedAt = arm.armedAt,
                now = now,
                fireBar = arm.fireBar,
                abandonBar = arm.abandonBar
            ),
            ttlMs = arm.ttlMs
        )
        if (registry.apply(trade.id, verdict) == ArmOutcome.FIRED) {
            execution.executeCalculatedPosition(calculated)
        }
        return verdict
    }

    // ------------------------------------------------------------------

    @Test
    fun `a healthy triangle clears the fire bar so the test baseline is meaningful`() = runTest {
        val books = healthy()
        val p = percent(books)
        assertTrue("baseline percent " + p, p >= ExecutionConfig.profitThreshold + ExecutionConfig.armingMarginPercent)
    }

    @Test
    fun `a lopsided book holds the opportunity instead of sending it`() = runTest {
        // Spread is tight, so the imbalance gate is allowed to make it patient.
        val books = healthy(bidQty = 1e6, askQty = 1e5)
        now += 100L
        val first = tick(books)
        assertEquals(ExecutionGates.Decision.WAIT, first.decision)
        assertTrue(first.reason.contains("lopsided"))
        assertTrue(executor.orders.isEmpty())
        assertEquals(1, registry.size)
    }

    @Test
    fun `the same triangle fires as soon as the book settles`() = runTest {
        now += 100L
        tick(healthy(bidQty = 1e6, askQty = 1e5))
        assertTrue(executor.orders.isEmpty())

        now += 200L
        val second = tick(healthy())
        assertEquals(ExecutionGates.Decision.FIRE, second.decision)
        assertEquals(3, executor.orders.size)
        assertEquals(0, registry.size)

        val stats = registry.snapshot()
        assertEquals(1L, stats.fired)
        assertEquals(1L, stats.medianWaitsBeforeFire)
        assertEquals(200L, stats.medianTimeToFireMs)
    }

    @Test
    fun `a wide spread is sent immediately rather than held for a better moment`() = runTest {
        // Both microstructure gates are failing, but on a wide spread holding
        // costs more than the patience is worth.
        val books = healthy(spreadStep = 0.0008, bidQty = 1e6, askQty = 1e5)
        assertTrue("wide books still profitable", percent(books) >= 0.40)
        val verdict = tick(books)
        assertEquals(ExecutionGates.Decision.FIRE, verdict.decision)
        assertFalse(verdict.spreadTight)
        assertEquals(3, executor.orders.size)
    }

    @Test
    fun `a triangle that has moved against us is dropped and never sent`() = runTest {
        val verdict = tick(againstUs())
        assertEquals(ExecutionGates.Decision.ABANDON, verdict.decision)
        assertTrue(executor.orders.isEmpty())
        assertEquals(0, registry.size)
    }

    @Test
    fun `an opportunity that only ever gets worse is never sent at a loss`() = runTest {
        // Every tick is tight, settled and fresh - the microstructure gates all
        // pass - yet nothing is ever executed, because the economics never do.
        for (i in 1..5) {
            now += 150L
            val books = mapOf(
                "ETHBTC" to book(0.015, midBoost = -EDGE),
                "BNBETH" to book(0.302, midBoost = -EDGE),
                "BNBBTC" to book(0.00456 - i * 0.00035, midBoost = EDGE)
            )
            tick(books)
        }
        assertTrue(executor.orders.isEmpty())
        assertEquals(0L, registry.snapshot().fired)
    }

    @Test
    fun `an opportunity whose books go quiet expires instead of hanging armed forever`() = runTest {
        tick(healthy(bidQty = 1e6, askQty = 1e5))
        assertEquals(1, registry.size)
        now += 1_500L
        assertTrue(registry.expire().isEmpty())
        now += 600L
        val expired = registry.expire()
        assertEquals(1, expired.size)
        assertEquals(ArmOutcome.EXPIRED, expired[0].outcome)
        assertTrue(executor.orders.isEmpty())
    }

    @Test
    fun `the size sent is the one the books support at fire time, not at arming`() = runTest {
        tick(healthy(bidQty = 1e6, askQty = 1e5))
        assertTrue(executor.orders.isEmpty())
        val sizedAtArming = CalculationNode.optimize(trade, snapshot(healthy(bidQty = 1e6, askQty = 1e5)))

        // The notional is re-derived (as AutoTuner does) while the opportunity
        // is held; the next ping must size against the books and the new spec.
        now += 200L
        InvestmentSpec.DEFAULTS["BTC"] = InvestmentSpec("BTC", 0.5, 0.5, 0.25)
        val fireBooks = healthy()
        tick(fireBooks)

        val sizedAtFire = CalculationNode.optimize(trade, snapshot(fireBooks))
        assertEquals(3, executor.orders.size)
        assertEquals(sizedAtFire.ab.quantity, executor.orders[0].second, 1e-9)
        assertFalse(
            "the arming size must not be reused",
            sizedAtArming.ab.quantity == executor.orders[0].second
        )
    }

    @Test
    fun `stale books are waited on rather than priced`() = runTest {
        val books = healthy().mapValues { (_, s) -> s.copy(eventTime = now - 9_000L) }
        val verdict = tick(books)
        assertEquals(ExecutionGates.Decision.WAIT, verdict.decision)
        assertTrue(verdict.reason.contains("stale book"))
        assertTrue(executor.orders.isEmpty())
    }

    @Test
    fun `a book in flux is waited on while the spread is tight`() = runTest {
        val verdict = tick(healthy(), cadenceMs = 4_000.0)
        assertEquals(ExecutionGates.Decision.WAIT, verdict.decision)
        assertTrue(verdict.reason.contains("in flux"))
        assertTrue(executor.orders.isEmpty())
    }
}
