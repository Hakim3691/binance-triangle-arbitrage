package com.hakim3691.bta.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The fire-time rules of the staged executor.
 *
 * Two of them are absolute and are the reason this feature is safe to leave on
 * by default: a round trip is never sent below break-even, and the engine only
 * ever spends patience while the spread is tight.
 */
class ExecutionGatesTest {

    private val armedAt = 10_000L
    private var now = 10_000L

    @Before
    fun setUp() {
        ExecutionConfig.feePercent = 0.10
        ExecutionConfig.profitThreshold = 0.30
        ExecutionConfig.armingMarginPercent = 0.10
        ExecutionConfig.abandonMarginPercent = 0.05
        ExecutionConfig.armTtlMs = 2000
        ExecutionConfig.ageThresholdMs = 5000
        ExecutionConfig.microstructureGatesEnabled = true
        ExecutionConfig.spreadTightMaxBps = 8.0
        ExecutionConfig.imbalanceMaxAbs = 0.35
        ExecutionConfig.cadenceMaxInterArrivalMs = 750
        now = armedAt
    }

    @After
    fun tearDown() {
        ExecutionConfig.stagedExecutionEnabled = true
    }

    private fun features(
        spreadBps: Double = 2.0,
        imbalance: Double = 0.10,
        cadence: Double = 120.0,
        ageMs: Long = 20L
    ) = TriangleFeatures(
        BookFeatures("ETHBTC", spreadBps, imbalance, cadence, ageMs),
        BookFeatures("BNBETH", spreadBps, imbalance, cadence, ageMs),
        BookFeatures("BNBBTC", spreadBps, imbalance, cadence, ageMs)
    )

    private fun verdict(
        percent: Double,
        features: TriangleFeatures = features(),
        fireBar: Double = 0.40,
        abandonBar: Double = 0.25,
        waitedMs: Long = 0L,
        ttlMs: Int = 2000
    ): ExecutionGates.Verdict {
        now = armedAt + waitedMs
        return ExecutionGates.evaluate(
            ExecutionGates.Input(
                features = features,
                projectedPercent = percent,
                armedAt = armedAt,
                now = now,
                fireBar = fireBar,
                abandonBar = abandonBar
            ),
            ttlMs = ttlMs
        )
    }

    // ------------------------------------------------------------------
    // Absolute rule 1: no loss inside an opportunity
    // ------------------------------------------------------------------

    @Test
    fun `a triangle projecting a loss is dropped, not sent`() {
        val v = verdict(percent = -0.4)
        assertEquals(ExecutionGates.Decision.ABANDON, v.decision)
        assertTrue(v.reason.contains("edge collapsed"))
    }

    @Test
    fun `the no-loss floor holds even when the bars are configured negative`() {
        // A hand-edited config could put both bars below break-even. The floor
        // is not configurable, so the trade is still withheld.
        val v = verdict(percent = -0.5, fireBar = -0.2, abandonBar = -0.9)
        assertEquals(ExecutionGates.Decision.WAIT, v.decision)
        assertFalse(v.fired)
    }

    @Test
    fun `break-even is allowed but nothing below it`() {
        assertEquals(ExecutionGates.Decision.FIRE, verdict(percent = 0.0, fireBar = 0.0, abandonBar = -1.0).decision)
        assertFalse(verdict(percent = -0.0001, fireBar = 0.0, abandonBar = -1.0).fired)
    }

    // ------------------------------------------------------------------
    // Waiting
    // ------------------------------------------------------------------

    @Test
    fun `an opportunity below the fire bar is held, not dropped`() {
        val v = verdict(percent = 0.35)
        assertEquals(ExecutionGates.Decision.WAIT, v.decision)
        assertTrue(v.reason.contains("waiting for"))
    }

    @Test
    fun `a stale leg is waited on because it recovers on its own`() {
        val v = verdict(percent = 0.5, features = features(ageMs = 9_000L))
        assertEquals(ExecutionGates.Decision.WAIT, v.decision)
        assertTrue(v.reason.contains("stale book"))
    }

    @Test
    fun `the window closing abandons the opportunity`() {
        val v = verdict(percent = 0.9, waitedMs = 2_001L, ttlMs = 2000)
        assertEquals(ExecutionGates.Decision.ABANDON, v.decision)
        assertTrue(v.reason.contains("arm expired"))
    }

    @Test
    fun `a zero window means the opportunity never expires on time alone`() {
        val v = verdict(percent = 0.5, waitedMs = 600_000L, ttlMs = 0)
        assertEquals(ExecutionGates.Decision.FIRE, v.decision)
    }

    // ------------------------------------------------------------------
    // Absolute rule 2: fire late only when the spread is tight
    // ------------------------------------------------------------------

    @Test
    fun `a settled tight book fires`() {
        val v = verdict(percent = 0.5)
        assertEquals(ExecutionGates.Decision.FIRE, v.decision)
        assertTrue(v.spreadTight)
        assertTrue(v.reason.contains("settled book"))
    }

    @Test
    fun `a wide spread is never held for a better moment`() {
        // Both deferral gates are failing, but holding costs more on a wide
        // spread than the patience is worth, so the trade goes now.
        val v = verdict(percent = 0.5, features = features(spreadBps = 20.0, imbalance = 0.9))
        assertEquals(ExecutionGates.Decision.FIRE, v.decision)
        assertFalse(v.spreadTight)
        assertTrue(v.reason.contains("wide spread is not worth holding for"))
    }

    @Test
    fun `an unmeasured spread never authorises waiting`() {
        val v = verdict(percent = 0.5, features = features(spreadBps = Double.NaN, imbalance = 0.9))
        assertEquals(ExecutionGates.Decision.FIRE, v.decision)
        assertTrue(v.reason.contains("spread unmeasured"))
    }

    // ------------------------------------------------------------------
    // Microstructure gates, only reachable while the spread is tight
    // ------------------------------------------------------------------

    @Test
    fun `a lopsided book is waited out while the spread is tight`() {
        val v = verdict(percent = 0.5, features = features(imbalance = 0.8))
        assertEquals(ExecutionGates.Decision.WAIT, v.decision)
        assertTrue(v.spreadTight)
        assertTrue(v.reason.contains("lopsided"))
    }

    @Test
    fun `a book in flux is waited out while the spread is tight`() {
        val v = verdict(percent = 0.5, features = features(cadence = 2_000.0))
        assertEquals(ExecutionGates.Decision.WAIT, v.decision)
        assertTrue(v.reason.contains("in flux"))
    }

    @Test
    fun `turning the microstructure gates off leaves economics in charge`() {
        ExecutionConfig.microstructureGatesEnabled = false
        val v = verdict(percent = 0.5, features = features(spreadBps = 40.0, imbalance = 0.9, cadence = 5_000.0))
        assertEquals(ExecutionGates.Decision.FIRE, v.decision)
        assertTrue(v.reason.contains("microstructure gates off"))
    }

    @Test
    fun `the gates are reported on the verdict so waiting is explainable`() {
        val v = verdict(percent = 0.5, features = features(spreadBps = 3.0, imbalance = 0.2, cadence = 90.0))
        assertEquals(3.0, v.spreadBps, 1e-9)
        assertEquals(0.2, v.imbalance, 1e-9)
        assertEquals(90.0, v.interArrivalMs, 1e-9)
        assertEquals(0.5, v.projectedPercent, 1e-9)
        assertEquals(0L, v.waitedMs)
    }

    @Test
    fun `expiry is checked before the edge so a stale arm reports its real age`() {
        val v = verdict(percent = -5.0, waitedMs = 3_000L, ttlMs = 2000)
        assertEquals(ExecutionGates.Decision.ABANDON, v.decision)
        assertTrue(v.reason.contains("arm expired"))
        assertEquals(3_000L, v.waitedMs)
    }
}
