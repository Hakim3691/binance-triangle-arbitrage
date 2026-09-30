package com.hakim3691.bta.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A triangle is priced from the books at identification time but sent as three
 * sequential orders. These tests pin the behaviour of the deadline and the
 * mid-flight re-pricing that stop the trade when that gap stops being safe.
 */
class PreFlightGuardTest {

    private var now = 10_000L
    private val guard = PreFlightGuard { now }

    // BTC-ETH-BNB: AB buys ETHBTC (spend BTC, earn ETH), BC buys BNBETH
    // (spend ETH, earn BNB), CA sells BNBBTC (spend BNB, earn BTC).
    private fun trade(): Trade {
        ExecutionConfig.dedupeMirroredTriangles = false
        return MarketCache().initialize(symbols(), setOf("BTC"))
            .trades.first { it.id == "BTC-ETH-BNB" }
    }

    private fun symbols(): List<SymbolInfo> = listOf(
        SymbolInfo("ETHBTC", "TRADING", "ETH", "BTC", listOf(SymbolFilter("LOT_SIZE", minQty = "0.00001"))),
        SymbolInfo("BNBETH", "TRADING", "BNB", "ETH", listOf(SymbolFilter("LOT_SIZE", minQty = "0.01000000"))),
        SymbolInfo("BNBBTC", "TRADING", "BNB", "BTC", listOf(SymbolFilter("LOT_SIZE", minQty = "0.01000000")))
    )

    /**
     * Ten levels each side, very liquid, so a projection is limited by prices
     * rather than by depth. Prices are quote-per-base: 1 ETH = 0.015 BTC,
     * 1 BNB = 0.302 ETH, 1 BNB = 0.00456 BTC.
     */
    private fun book(mid: Double) = DepthSnapshot(
        bids = LinkedHashMap<Double, Double>().apply {
            for (i in 0 until 10) put(mid * (1.0 - 0.001 * (i + 1)), 1_000_000.0)
        },
        asks = LinkedHashMap<Double, Double>().apply {
            for (i in 0 until 10) put(mid * (1.0 + 0.001 * (i + 1)), 1_000_000.0)
        },
        eventTime = 1000
    )

    /** Profitable: 66.7 ETH -> 220.7 BNB -> 1.006 BTC, clearing 3 x 0.10% fee. */
    private fun healthy() = CalculationNode.TradeDepthSnapshot(
        book(0.015), book(0.302), book(0.00456)
    )

    /** Same books after the BTC/BNB market moved against us. */
    private fun drifted() = CalculationNode.TradeDepthSnapshot(
        book(0.015), book(0.302), book(0.00420)
    )

    private fun collapsed() = CalculationNode.TradeDepthSnapshot(
        book(0.015), book(0.302), book(0.00350)
    )

    /** What the engine itself computes for these books. */
    private fun enginePercent(d: CalculationNode.TradeDepthSnapshot): Double =
        CalculationNode.calculate(1.0, trade(), d).percent

    /**
     * Walks the whole chain so the guard is handed balances that actually
     * correspond to the books, instead of arbitrary numbers.
     */
    private fun chain(d: CalculationNode.TradeDepthSnapshot, startA: Double = 1.0): Triple<Double, Double, Double> {
        val t = trade()
        val ab = CalculationNode.projectLeg(t.ab, startA, d.ab)
        val bc = CalculationNode.projectLeg(t.bc, ab.earned, d.bc)
        return Triple(ab.spent, ab.earned, bc.earned)
    }

    @Before
    fun setUp() {
        ExecutionConfig.feePercent = 0.10
        ExecutionConfig.profitThreshold = 0.0
        ExecutionConfig.preFlightMarginPercent = 0.0
        ExecutionConfig.preFlightCheckEnabled = true
        ExecutionConfig.executionDeadlineMs = 0
    }

    @After
    fun tearDown() {
        ExecutionConfig.preFlightCheckEnabled = true
        ExecutionConfig.executionDeadlineMs = 0
        ExecutionConfig.preFlightMarginPercent = 0.0
        ExecutionConfig.dedupeMirroredTriangles = true
    }

    // ---------------- fixtures sanity ----------------

    @Test
    fun `the healthy books are profitable and the drifted ones are not`() {
        assertTrue("healthy should profit, got " + enginePercent(healthy()), enginePercent(healthy()) > 0.0)
        assertTrue("drifted should not profit, got " + enginePercent(drifted()), enginePercent(drifted()) < 0.0)
        assertTrue("collapsed should not profit", enginePercent(collapsed()) < 0.0)
    }

    // ---------------- deadline ----------------

    @Test
    fun `deadline aborts once the budget is spent`() {
        ExecutionConfig.executionDeadlineMs = 500

        now = 10_400
        val ok = guard.verdict(trade(), healthy(), 1.0, 1.0, 1.0, 10_000, PreFlightGuard.Stage.BEFORE_FIRST)
        assertTrue(ok.proceed)
        assertEquals(400L, ok.elapsedMs)

        now = 10_600
        val late = guard.verdict(trade(), healthy(), 1.0, 1.0, 1.0, 10_000, PreFlightGuard.Stage.BEFORE_FIRST)
        assertFalse(late.proceed)
        assertTrue(late.deadlineExceeded)
        assertTrue(late.reason!!.contains("deadline"))
    }

    @Test
    fun `deadline of zero disables the check`() {
        ExecutionConfig.executionDeadlineMs = 0
        now = 10_000_000
        val v = guard.verdict(trade(), healthy(), 1.0, 1.0, 1.0, 10_000, PreFlightGuard.Stage.BEFORE_FIRST)
        assertTrue(v.proceed)
        assertFalse(v.deadlineExceeded)
    }

    @Test
    fun `deadline is checked before the re-pricing`() {
        ExecutionConfig.executionDeadlineMs = 100
        ExecutionConfig.profitThreshold = 99.0 // nothing could ever pass this
        now = 10_500

        val v = guard.verdict(trade(), healthy(), 1.0, 1.0, 1.0, 10_000, PreFlightGuard.Stage.AFTER_AB)
        assertFalse(v.proceed)
        assertTrue("deadline must short-circuit the re-pricing", v.deadlineExceeded)
    }

    // ---------------- re-pricing ----------------

    @Test
    fun `re-pricing passes when the books still support the trade`() {
        val d = healthy()
        val (spentA, earnedB, earnedC) = chain(d)
        val expected = enginePercent(d)

        val v = guard.verdict(trade(), d, spentA, earnedB, earnedC, 10_000, PreFlightGuard.Stage.AFTER_AB)
        assertTrue(v.reason ?: "", v.proceed)
        assertEquals("projection must agree with the engine", expected, v.projectedPercent, 0.05)
    }

    @Test
    fun `re-pricing aborts when the remaining legs no longer pay for themselves`() {
        val d = drifted()
        val (spentA, earnedB, earnedC) = chain(d)

        val v = guard.verdict(trade(), d, spentA, earnedB, earnedC, 10_000, PreFlightGuard.Stage.AFTER_AB)
        assertFalse(v.proceed)
        assertFalse(v.deadlineExceeded)
        assertTrue(v.reason!!.contains("pre-flight"))
        assertTrue(v.projectedPercent < ExecutionConfig.profitThreshold)
    }

    @Test
    fun `after-bc stage projects only the final leg`() {
        val d = healthy()
        val (spentA, earnedB, earnedC) = chain(d)

        val v = guard.verdict(trade(), d, spentA, earnedB, earnedC, 10_000, PreFlightGuard.Stage.AFTER_BC)
        assertTrue(v.reason ?: "", v.proceed)

        val bad = drifted()
        val (spentA2, earnedB2, earnedC2) = chain(bad)
        val v2 = guard.verdict(trade(), bad, spentA2, earnedB2, earnedC2, 10_000, PreFlightGuard.Stage.AFTER_BC)
        assertFalse(v2.proceed)
    }

    @Test
    fun `margin tightens the bar`() {
        val d = healthy()
        val (spentA, earnedB, earnedC) = chain(d)

        val plain = guard.verdict(trade(), d, spentA, earnedB, earnedC, 10_000, PreFlightGuard.Stage.AFTER_AB)
        assertTrue(plain.proceed)

        ExecutionConfig.preFlightMarginPercent = plain.projectedPercent + 0.5
        val withMargin = guard.verdict(trade(), d, spentA, earnedB, earnedC, 10_000, PreFlightGuard.Stage.AFTER_AB)
        assertFalse(withMargin.proceed)
    }

    @Test
    fun `disabled check short-circuits the re-pricing but not the deadline`() {
        ExecutionConfig.preFlightCheckEnabled = false
        val d = drifted()
        val (spentA, earnedB, earnedC) = chain(d)

        assertTrue(guard.verdict(trade(), d, spentA, earnedB, earnedC, 10_000, PreFlightGuard.Stage.AFTER_AB).proceed)

        ExecutionConfig.executionDeadlineMs = 100
        now = 10_500
        val late = guard.verdict(trade(), d, spentA, earnedB, earnedC, 10_000, PreFlightGuard.Stage.AFTER_AB)
        assertFalse(late.proceed)
        assertTrue(late.deadlineExceeded)
    }

    @Test
    fun `before-first stage only checks the deadline`() {
        val v = guard.verdict(trade(), drifted(), 0.0, 0.0, 0.0, 10_000, PreFlightGuard.Stage.BEFORE_FIRST)
        assertTrue(v.proceed)
    }

    @Test
    fun `before-submit re-prices the whole triangle from fresh books`() {
        assertTrue(
            guard.verdict(trade(), healthy(), 1.0, 0.0, 0.0, 10_000, PreFlightGuard.Stage.BEFORE_SUBMIT).proceed
        )
        assertFalse(
            guard.verdict(trade(), collapsed(), 1.0, 0.0, 0.0, 10_000, PreFlightGuard.Stage.BEFORE_SUBMIT).proceed
        )
    }

    @Test
    fun `zero spent cannot fake a positive projection`() {
        val v = guard.verdict(trade(), healthy(), 0.0, 1.0, 1.0, 10_000, PreFlightGuard.Stage.AFTER_BC)
        assertEquals(-100.0, v.projectedPercent, 1e-9)
        assertFalse(v.proceed)
    }

    @Test
    fun `a book that cannot cover the remaining legs aborts instead of throwing`() {
        val thin = CalculationNode.TradeDepthSnapshot(
            book(0.015), book(0.302),
            DepthSnapshot(bids = mapOf(0.0045 to 0.000001), asks = mapOf(0.0046 to 0.000001))
        )
        val (spentA, earnedB, earnedC) = chain(healthy())

        val v = guard.verdict(trade(), thin, spentA, earnedB, earnedC, 10_000, PreFlightGuard.Stage.AFTER_AB)
        assertFalse("a shallow book must abort, not throw", v.proceed)
        assertTrue(v.reason!!.contains("pre-flight"))
    }
}
