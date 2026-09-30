package com.hakim3691.bta.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Derivations for the staged executor: the arming window, the two bars, the
 * microstructure thresholds and the paper engine's simulated friction.
 *
 * The properties that matter are that the drop margin is always smaller than
 * the fire margin (otherwise every arm would abandon on its first check), that
 * no threshold can be derived into a state that silently disables its own
 * gate, and that a market with no observations yet gets a conservative
 * default rather than an invented number.
 */
class AutoTunerGatesTest {

    @Test
    fun `the arm window is several execution deadlines long`() {
        assertEquals(2_000, AutoTuner.deriveArmTtl(deadlineMs = 500))
        assertEquals(4_000, AutoTuner.deriveArmTtl(deadlineMs = 1_000))
    }

    @Test
    fun `the arm window falls back to the measured round trip without a deadline`() {
        // latency 100ms x 3 legs x 3 headroom = 900ms deadline-equivalent
        assertEquals(3_600, AutoTuner.deriveArmTtl(deadlineMs = 0, latencyMs = 100.0))
    }

    @Test
    fun `an unmeasured market still gets a usable arm window`() {
        assertEquals(1_000, AutoTuner.deriveArmTtl(deadlineMs = 0, latencyMs = 0.0))
    }

    @Test
    fun `the arm window is clamped to a range a human can reason about`() {
        assertEquals(500, AutoTuner.deriveArmTtl(deadlineMs = 10))
        assertEquals(20_000, AutoTuner.deriveArmTtl(deadlineMs = 5_000))
    }

    @Test
    fun `the fire margin is one fee of extra edge`() {
        assertEquals(0.10, AutoTuner.deriveArmingMargin(0.10), 1e-12)
        assertEquals(0.001, AutoTuner.deriveArmingMargin(0.001), 1e-12)
        assertEquals(0.0, AutoTuner.deriveArmingMargin(0.0), 0.0)
    }

    @Test
    fun `the drop margin is always below the fire margin`() {
        for (fee in listOf(0.001, 0.02, 0.10, 0.5, 1.0)) {
            assertTrue(
                "fee " + fee,
                AutoTuner.deriveAbandonMargin(fee) < AutoTuner.deriveArmingMargin(fee)
            )
        }
        assertEquals(0.0, AutoTuner.deriveAbandonMargin(0.0), 0.0)
    }

    @Test
    fun `the tight spread ceiling is derived from what the books have been showing`() {
        // Observed worst leg 4bps -> 5bps with headroom.
        assertEquals(5.0, AutoTuner.deriveSpreadTightMaxBps(4.0), 1e-9)
    }

    @Test
    fun `an unmeasured market gets a conservative spread ceiling`() {
        assertEquals(8.0, AutoTuner.deriveSpreadTightMaxBps(0.0), 0.0)
        assertEquals(8.0, AutoTuner.deriveSpreadTightMaxBps(Double.NaN), 0.0)
    }

    @Test
    fun `the spread ceiling is clamped so the gate can never be defeated or blind`() {
        assertEquals(1.0, AutoTuner.deriveSpreadTightMaxBps(0.1), 1e-9)
        assertEquals(50.0, AutoTuner.deriveSpreadTightMaxBps(1_000.0), 1e-9)
    }

    @Test
    fun `the imbalance limit sits inside the range the market normally produces`() {
        // p90 of 0.5 -> 0.30, i.e. tighter than typical but not unreachable.
        assertEquals(0.30, AutoTuner.deriveImbalanceMaxAbs(0.5), 1e-9)
        assertEquals(0.35, AutoTuner.deriveImbalanceMaxAbs(0.0), 0.0)
    }

    @Test
    fun `the imbalance limit is clamped inside the unit interval`() {
        assertEquals(0.15, AutoTuner.deriveImbalanceMaxAbs(0.10), 1e-9)
        assertEquals(0.60, AutoTuner.deriveImbalanceMaxAbs(1.0), 1e-9)
    }

    @Test
    fun `the cadence limit follows the measured round trip`() {
        assertEquals(200, AutoTuner.deriveCadenceMaxInterArrivalMs(50.0))
        assertEquals(750, AutoTuner.deriveCadenceMaxInterArrivalMs(0.0))
        assertEquals(3_000, AutoTuner.deriveCadenceMaxInterArrivalMs(9_999.0))
    }

    @Test
    fun `arm slots are bounded`() {
        assertEquals(8, AutoTuner.deriveMaxArmed())
        assertEquals(1, AutoTuner.deriveMaxArmed(0))
        assertEquals(64, AutoTuner.deriveMaxArmed(1_000))
    }

    @Test
    fun `paper latency mirrors the measured round trip`() {
        assertEquals(0, AutoTuner.derivePaperLatencyMs(0.0))
        assertEquals(150, AutoTuner.derivePaperLatencyMs(150.0))
        assertEquals(2_000, AutoTuner.derivePaperLatencyMs(999_999.0))
    }

    @Test
    fun `paper slippage is half the spread when the order is exposed for no intervals`() {
        assertEquals(0.02, AutoTuner.derivePaperSlippagePercent(4.0), 1e-9)
    }

    @Test
    fun `paper slippage assumes a taker spread rather than zero friction before any observation`() {
        // The first fills of a session must not be frictionless: no observation
        // is not evidence of a frictionless market.
        val expected = AutoTuner.DEFAULT_PAPER_SLIPPAGE_BPS / 2.0 / 100.0
        assertEquals(expected, AutoTuner.derivePaperSlippagePercent(0.0), 1e-9)
        assertEquals(expected, AutoTuner.derivePaperSlippagePercent(-1.0), 1e-9)
        assertEquals(expected, AutoTuner.derivePaperSlippagePercent(Double.NaN), 1e-9)
    }

    @Test
    fun `paper slippage grows with the book updates an order is in flight for`() {
        // 10bps spread, 200ms order, book ticking every 50ms => 4 intervals,
        // capped at MAX_SLIPPAGE_LATENCY_INTERVALS, so 4x the half-spread.
        val exposed = AutoTuner.derivePaperSlippagePercent(10.0, 200.0, 50.0)
        assertEquals(0.05 * 4.0, exposed, 1e-9)

        // A book that barely ticks while the order is in flight is barely
        // exposed, so it converges on the bare half-spread.
        val sheltered = AutoTuner.derivePaperSlippagePercent(10.0, 200.0, 1_000_000_000.0)
        assertEquals(0.05, sheltered, 1e-4)
        assertTrue(sheltered < exposed)
    }

    @Test
    fun `paper slippage ignores latency until a cadence has been observed`() {
        assertEquals(
            AutoTuner.derivePaperSlippagePercent(10.0, 5_000.0, 0.0),
            AutoTuner.derivePaperSlippagePercent(10.0, 0.0, 0.0),
            1e-9
        )
    }

    @Test
    fun `paper slippage stays bounded`() {
        val absurd = AutoTuner.derivePaperSlippagePercent(50_000.0, 1_000_000.0, 1.0)
        assertTrue(absurd <= AutoTuner.MAX_PAPER_SLIPPAGE_PERCENT)
    }

    @Test
    fun `tune derives the whole staged configuration from measured inputs`() {
        val result = AutoTuner.tune(
            AutoTuner.Inputs(
                base = "BTC",
                budgetUsdt = 1_000.0,
                basePriceUsdt = 60_000.0,
                lotStep = 0.00001,
                lotMinQty = 0.00001,
                takerCommissionRate = 0.001,
                latencyMs = 100.0,
                observedWorstLegSpreadBps = 4.0,
                observedP90AbsImbalance = 0.5
            )
        )
        assertEquals(0.10, result.feePercent, 1e-9)
        // 3 x fee break-even plus one fee of arming margin
        assertEquals(0.40, result.profitThreshold + result.armingMarginPercent, 1e-9)
        assertTrue(result.abandonMarginPercent < result.armingMarginPercent)
        assertEquals(5.0, result.spreadTightMaxBps, 1e-9)
        assertEquals(0.30, result.imbalanceMaxAbs, 1e-9)
        assertEquals(200, result.cadenceMaxInterArrivalMs)
        assertEquals(100, result.paperLatencyMs)
        assertEquals(0.02, result.paperSlippagePercent, 1e-9)
        assertTrue(result.armTtlMs > result.deadlineMs)
    }

    @Test
    fun `the tuner explains the thresholds it chose`() {
        val summary = AutoTuner.tune(
            AutoTuner.Inputs(takerCommissionRate = 0.001, latencyMs = 100.0, observedWorstLegSpreadBps = 4.0)
        ).summary()
        assertTrue(summary.contains("arm "))
        assertTrue(summary.contains("fire at"))
        assertTrue(summary.contains("abandon below"))
        assertTrue(summary.contains("spread<="))
        assertTrue(summary.contains("paper fills"))
    }
}

class DerivedSignatureTest {

    @After
    fun restore() {
        ExecutionConfig.cap = 1
        ExecutionConfig.scanningDepth = 50
        ExecutionConfig.strategy = "linear"
    }

    @Test
    fun `signature survives every field regardless of its declared type`() {
        // Regression: this was a mixed Int/Double/String list formatted with
        // "%.3f", which threw IllegalFormatConversionException ("f !=
        // java.lang.Integer") and killed the scan thread.
        ExecutionConfig.cap = 7
        ExecutionConfig.scanningDepth = 10
        ExecutionConfig.armTtlMs = 7_668
        ExecutionConfig.paperLatencyMs = 213
        ExecutionConfig.maxArmedOpportunities = 8
        ExecutionConfig.cadenceMaxInterArrivalMs = 300
        ExecutionConfig.executionDeadlineMs = 1_917
        ExecutionConfig.strategy = "linear"

        val signature = ExecutionConfig.derivedSignature()
        assertTrue(signature.contains("linear"))
        assertTrue(signature.contains("7.000"))
        assertTrue(signature.contains("10.000"))
    }

    @Test
    fun `signature changes when a derived value changes and not otherwise`() {
        val before = ExecutionConfig.derivedSignature()
        assertEquals(before, ExecutionConfig.derivedSignature())

        val savedSpread = ExecutionConfig.spreadTightMaxBps
        try {
            ExecutionConfig.spreadTightMaxBps = savedSpread + 1.0
            assertTrue(ExecutionConfig.derivedSignature() != before)
        } finally {
            ExecutionConfig.spreadTightMaxBps = savedSpread
        }
        assertEquals(before, ExecutionConfig.derivedSignature())
    }

    @Test
    fun `signature reports strategy changes`() {
        val before = ExecutionConfig.derivedSignature()
        ExecutionConfig.strategy = "parallel"
        try {
            assertTrue(ExecutionConfig.derivedSignature() != before)
        } finally {
            ExecutionConfig.strategy = "linear"
        }
    }
}
