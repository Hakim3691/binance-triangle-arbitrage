package com.hakim3691.bta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scan depth is a single global applied to every ticker, so it has to be
 * sized for the hardest book in the universe rather than the easiest - and it
 * has to stay inside what the depth stream actually subscribed with.
 */
class ScanDepthUniverseTest {

    @Test
    fun `depth is capped at what the depth stream subscribed with`() {
        val d = AutoTuner.deriveDepth(requiredNotional = 10_000_000.0, levelNotional = 0.01)
        assertEquals(AutoTuner.MAX_SCAN_DEPTH, d)
    }

    @Test
    fun `depth never exceeds the ceiling even when the requirement is enormous`() {
        // Regression: the ceiling used to be applied only on the fallback, so
        // the loop handed back 500 levels from a socket holding 50.
        for (req in listOf(1.0, 1_000.0, 1_000_000.0, 1e12)) {
            for (level in listOf(0.0001, 0.01, 1.0, 5_000.0)) {
                val d = AutoTuner.deriveDepth(req, level)
                assertTrue(
                    "depth $d out of range for req=$req level=$level",
                    d <= AutoTuner.MAX_SCAN_DEPTH
                )
            }
        }
    }

    @Test
    fun `depth is driven by the thinnest book and the largest configured notional`() {
        assertTrue(
            AutoTuner.deriveDepth(650.0, 0.01) >
                AutoTuner.deriveDepth(650.0, 5_000.0)
        )
    }

    @Test
    fun `depth never drops below the floor`() {
        assertEquals(AutoTuner.MIN_SCAN_DEPTH, AutoTuner.deriveDepth(1.0, 1_000_000.0))
    }

    @Test
    fun `unmeasured level notional falls back without throwing`() {
        val d = AutoTuner.deriveDepth(requiredNotional = 100.0, levelNotional = 0.0)
        assertTrue(d in AutoTuner.MIN_SCAN_DEPTH..AutoTuner.MAX_SCAN_DEPTH)
    }

    @Test
    fun `a larger configured notional deepens the scan`() {
        val small = AutoTuner.tune(
            AutoTuner.Inputs(
                base = "BTC", basePriceUsdt = 100_000.0, lotStep = 0.00001,
                budgetUsdt = 100.0, takerCommissionRate = 0.001,
                levelNotional = 20.0, largestConfiguredNotionalUsdt = 5.0
            )
        )
        val large = AutoTuner.tune(
            AutoTuner.Inputs(
                base = "BTC", basePriceUsdt = 100_000.0, lotStep = 0.00001,
                budgetUsdt = 100.0, takerCommissionRate = 0.001,
                levelNotional = 20.0, largestConfiguredNotionalUsdt = 650.0
            )
        )
        assertTrue(
            "largest configured notional must not make the scan shallower: " +
                "${small.depth} vs ${large.depth}",
            large.depth >= small.depth
        )
        assertTrue(large.depth > small.depth)
    }
}

/**
 * Both execution strategies stay reachable, but the decision carries
 * hysteresis: the pre-funded ratio is a continuous quantity that sits on the
 * boundary in normal operation, and a bare threshold flip-flopped between
 * strategies several times a minute.
 */
class StrategyHysteresisTest {

    @Test
    fun `both strategies stay reachable`() {
        assertEquals("parallel", AutoTuner.deriveStrategy(1.0, "linear"))
        assertEquals("linear", AutoTuner.deriveStrategy(0.0, "parallel"))
    }

    @Test
    fun `a ratio hovering on the boundary cannot make the strategy oscillate`() {
        // This is the real-world trace: the ratio sits just under and just
        // over the threshold, and a bare comparison flipped on most samples.
        val noise = listOf(
            0.9985, 0.9995, 0.9989, 0.99951, 0.9987, 0.99949, 0.9992, 0.9988
        )
        var strategy = "linear"
        var changes = 0
        for (r in noise) {
            val next = AutoTuner.deriveStrategy(r, strategy)
            if (next != strategy) changes++
            strategy = next
        }
        assertTrue(
            "strategy changed $changes times across ${noise.size} samples",
            changes <= 1
        )
    }

    @Test
    fun `an unusable reading leaves the live strategy alone`() {
        assertEquals("parallel", AutoTuner.deriveStrategy(Double.NaN, "parallel"))
        assertEquals("linear", AutoTuner.deriveStrategy(Double.NaN, "linear"))
    }

    @Test
    fun `entering parallel needs a clear signal and leaving needs a clear fall`() {
        assertEquals("parallel", AutoTuner.deriveStrategy(1.0, "linear"))
        assertEquals("parallel", AutoTuner.deriveStrategy(0.97, "parallel"))
        assertEquals("linear", AutoTuner.deriveStrategy(0.10, "parallel"))
    }

    @Test
    fun `the band between the two thresholds is the hysteresis zone`() {
        assertTrue(AutoTuner.STRATEGY_EXIT_PRE_FUNDED < AutoTuner.STRATEGY_ENTER_PRE_FUNDED)
        val mid = (AutoTuner.STRATEGY_EXIT_PRE_FUNDED + AutoTuner.STRATEGY_ENTER_PRE_FUNDED) / 2
        assertEquals("linear", AutoTuner.deriveStrategy(mid, "linear"))
        assertEquals("parallel", AutoTuner.deriveStrategy(mid, "parallel"))
    }

    @Test
    fun `a non-finite ratio cannot flip the strategy`() {
        assertEquals("linear", AutoTuner.deriveStrategy(Double.NaN, "linear"))
        assertEquals("parallel", AutoTuner.deriveStrategy(Double.NaN, "parallel"))
    }
}
