package com.hakim3691.bta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings screen lets the user hand any of its values to [AutoTuner].
 * These tests pin the derivations so an "AUTO" value can never quietly become
 * something the exchange would reject or that loses money by construction.
 */
class AutoTunerTest {

    // ---------------- FEE % ----------------

    @Test
    fun `fee is derived from the exchangeInfo taker commission`() {
        // Binance publishes 0.001 == 0.10%
        assertEquals(0.1, AutoTuner.deriveFeePercent(0.001), 1e-9)
        assertEquals(0.075, AutoTuner.deriveFeePercent(0.00075), 1e-9)
    }

    @Test
    fun `fee falls back to the VIP0 default when Binance publishes nothing usable`() {
        assertEquals(0.10, AutoTuner.deriveFeePercent(null), 1e-9)
        assertEquals(0.10, AutoTuner.deriveFeePercent(0.0), 1e-9)
        assertEquals(0.10, AutoTuner.deriveFeePercent(Double.NaN), 1e-9)
        assertEquals(0.10, AutoTuner.deriveFeePercent(1.5), 1e-9)
    }

    // ---------------- THRESHOLD.PROFIT % ----------------

    @Test
    fun `profit gate is the three leg break-even edge`() {
        assertEquals(0.30, AutoTuner.deriveProfitThreshold(0.10), 1e-9)
        assertEquals(0.225, AutoTuner.deriveProfitThreshold(0.075), 1e-9)
        // A user-supplied floor still wins.
        assertEquals(0.5, AutoTuner.deriveProfitThreshold(0.10, floor = 0.5), 1e-9)
    }

    // ---------------- MIN / MAX / STEP ----------------

    @Test
    fun `investment size is floored onto the LOT_SIZE grid`() {
        val (min, max, step) = AutoTuner.deriveInvestment(
            base = "BTC",
            targetNotionalUsdt = 50.0,
            basePriceUsdt = 60_000.0,
            lotStep = 0.00001,
            lotMinQty = 0.00001
        )
        assertEquals(0.00001, step, 1e-12)
        // 50 / 60000 == 0.0008333... floored onto the grid to 0.00083
        assertEquals(0.00083, min, 1e-9)
        assertEquals(0.00084, max, 1e-9)
    }

    @Test
    fun `investment size never goes below the symbol minimum`() {
        val (min, max, step) = AutoTuner.deriveInvestment(
            base = "BTC",
            targetNotionalUsdt = 0.5, // far too small for BTC
            basePriceUsdt = 60_000.0,
            lotStep = 0.00001,
            lotMinQty = 0.00001
        )
        assertEquals(0.00001, min, 1e-9)
        assertEquals(0.00002, max, 1e-9)
        assertEquals(0.00001, step, 1e-12)
    }

    @Test
    fun `derived investment size satisfies the engine validator`() {
        val (min, max, step) = AutoTuner.deriveInvestment("ETH", 40.0, 3000.0, 0.001, 0.0001)
        val spec = InvestmentSpec("ETH", min, max, step)
        InvestmentSpec.DEFAULTS.clear()
        InvestmentSpec.DEFAULTS["ETH"] = spec
        val errors = ExecutionConfig.validate()
        assertTrue("derived spec must validate, got: $errors", errors.isEmpty())
        InvestmentSpec.DEFAULTS.clear()
    }

    @Test
    fun `step rounding helpers round in the documented direction`() {
        assertEquals(0.008, AutoTuner.floorToStep(0.0089, 0.001), 1e-12)
        assertEquals(0.009, AutoTuner.ceilToStep(0.0081, 0.001), 1e-12)
        assertEquals(0.008, AutoTuner.floorToStep(0.008, 0.001), 1e-12)
        // A step expressed in exponent notation keeps its precision.
        assertEquals(0.00001, AutoTuner.ceilToStep(0.000001, 0.00001), 1e-12)
        assertEquals(0.00083, AutoTuner.floorToStep(0.0008333, 0.00001), 1e-12)
        assertEquals(5, AutoTuner.decimalsFor(0.00001))
        assertEquals(3, AutoTuner.decimalsFor(0.001))
        assertEquals(0, AutoTuner.decimalsFor(1.0))
    }

    // ---------------- SCANNING.DEPTH ----------------

    @Test
    fun `depth is the smallest Binance-valid value that can hold the trade`() {
        // 5 USDT per level, 150 USDT needed -> 30 levels -> next valid rung is 50.
        assertEquals(50, AutoTuner.deriveDepth(requiredNotional = 150.0, levelNotional = 5.0))
        // 30 USDT needs 6 levels -> 10; 60 USDT needs 12 levels -> 20.
        assertEquals(10, AutoTuner.deriveDepth(requiredNotional = 30.0, levelNotional = 5.0))
        assertEquals(20, AutoTuner.deriveDepth(requiredNotional = 60.0, levelNotional = 5.0))
        // Never below the floor: a five-level walk prices a thin book off its top.
        assertEquals(10, AutoTuner.deriveDepth(requiredNotional = 3.0, levelNotional = 5.0))
    }

    @Test
    fun `depth is clamped to the Binance ladder at both ends`() {
        // A requirement the ladder overshoots stops at the subscribed depth,
        // not at VALID_DEPTHS.last(): the cache can never hold more than the
        // socket was opened with.
        assertEquals(
            AutoTuner.MAX_SCAN_DEPTH,
            AutoTuner.deriveDepth(requiredNotional = 10_000_000.0, levelNotional = 1.0)
        )
        // Unmeasured books fall back to a sane default rather than 5.
        assertEquals(50, AutoTuner.deriveDepth(requiredNotional = 100.0, levelNotional = 0.0))
    }

    @Test
    fun `level notional is the median of the top of the book`() {
        val levels = mapOf(100.0 to 1.0, 99.0 to 1.0, 98.0 to 1.0)
        assertEquals(99.0, AutoTuner.measureLevelNotional(levels), 1e-9)
        assertEquals(0.0, AutoTuner.measureLevelNotional(emptyMap()), 1e-9)
        // Only the window is measured.
        val wide = (1..100).associate { (200 - it).toDouble() to 1.0 }
        assertTrue(AutoTuner.measureLevelNotional(wide) > 100.0)
    }

    // ---------------- CAP ----------------

    @Test
    fun `cap tracks how many Kelly sized trades the budget can fund`() {
        assertEquals(10, AutoTuner.deriveCap(budgetUsdt = 100.0, perTradeUsdt = 5.0))
        assertEquals(5, AutoTuner.deriveCap(budgetUsdt = 100.0, perTradeUsdt = 20.0))
        assertEquals(1, AutoTuner.deriveCap(budgetUsdt = 100.0, perTradeUsdt = 500.0))
        assertEquals(1, AutoTuner.deriveCap(budgetUsdt = 0.0, perTradeUsdt = 5.0))
    }

    // ---------------- STRATEGY ----------------

    @Test
    fun `parallel only when every leg is already funded`() {
        assertEquals("linear", AutoTuner.deriveStrategy(0.0))
        assertEquals("linear", AutoTuner.deriveStrategy(0.66))
        assertEquals("parallel", AutoTuner.deriveStrategy(1.0))
    }

    // ---------------- PER TRADE SIZING ----------------

    @Test
    fun `per trade size mirrors the Kelly paper trader`() {
        // budget 100, f* 0.5 clamped by maxAllocation 0.25 -> 25, then 10% -> 2.5,
        // floored at the 5 USDT minimum trade size.
        val perTrade = AutoTuner.derivePerTradeUsdt(
            budgetUsdt = 100.0,
            kellyFraction = 0.5,
            maxAllocationPerTrade = 0.25,
            investmentFractionOfKelly = 0.10,
            minTradeUsdt = 5.0
        )
        assertEquals(5.0, perTrade, 1e-9)
    }

    @Test
    fun `per trade size never exceeds the budget`() {
        val perTrade = AutoTuner.derivePerTradeUsdt(
            budgetUsdt = 3.0,
            kellyFraction = 1.0,
            maxAllocationPerTrade = 1.0,
            investmentFractionOfKelly = 1.0,
            minTradeUsdt = 5.0
        )
        assertEquals(3.0, perTrade, 1e-9)
    }

    // ---------------- END TO END ----------------

    @Test
    fun `tune produces a config the engine accepts`() {
        val result = AutoTuner.tune(
            AutoTuner.Inputs(
                base = "BTC",
                budgetUsdt = 100.0,
                basePriceUsdt = 60_000.0,
                lotStep = 0.00001,
                lotMinQty = 0.00001,
                kellyFraction = 0.5,
                investmentFractionOfKelly = 0.10,
                maxAllocationPerTrade = 0.25,
                minTradeUsdt = 5.0,
                levelNotional = 250.0,
                preFundedRatio = 0.0,
                takerCommissionRate = 0.001
            )
        )

        assertEquals("BTC", result.base)
        assertEquals(0.1, result.feePercent, 1e-9)
        assertEquals(0.3, result.profitThreshold, 1e-9)
        assertEquals(5.0, result.perTradeUsdt, 1e-9)
        assertEquals("linear", result.strategy)
        // 5 USDT per trade, budget 100 -> 20, clamped to MAX_AUTO_CAP.
        assertEquals(10, result.cap)
        // 15 USDT of book needed, 250 USDT per level -> one rung would do, but
        // the depth floor keeps it at 10.
        assertEquals(AutoTuner.MIN_SCAN_DEPTH, result.depth)
        assertTrue(result.step > 0.0)
        assertTrue(result.min >= 0.0)

        val savedFee = ExecutionConfig.feePercent
        val savedThreshold = ExecutionConfig.profitThreshold
        val savedCap = ExecutionConfig.cap
        val savedDepth = ExecutionConfig.scanningDepth
        try {
            InvestmentSpec.DEFAULTS.clear()
            InvestmentSpec.DEFAULTS[result.base] = result.investmentSpec()
            ExecutionConfig.feePercent = result.feePercent
            ExecutionConfig.profitThreshold = result.profitThreshold
            ExecutionConfig.cap = result.cap
            ExecutionConfig.scanningDepth = result.depth
            val errors = ExecutionConfig.validate()
            assertTrue("tuned config must validate, got: $errors", errors.isEmpty())
        } finally {
            ExecutionConfig.feePercent = savedFee
            ExecutionConfig.profitThreshold = savedThreshold
            ExecutionConfig.cap = savedCap
            ExecutionConfig.scanningDepth = savedDepth
            InvestmentSpec.DEFAULTS.clear()
        }
    }

    @Test
    fun `tune stays valid before any book has been seen`() {
        val result = AutoTuner.tune(AutoTuner.Inputs(base = "btc", budgetUsdt = 100.0))
        assertEquals("BTC", result.base)
        assertTrue("step must always be positive", result.step > 0.0)
        assertTrue("min must always be positive", result.min > 0.0)
        assertEquals(50, result.depth)
        assertEquals("linear", result.strategy)
        assertTrue(result.summary().isNotBlank())
    }
}
