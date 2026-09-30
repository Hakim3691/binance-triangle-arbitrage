package com.hakim3691.bta.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The arming registry: what it holds, what it releases, and the telemetry the
 * dashboard reports. The clock is injected so the arming window and the
 * median wait times can be tested without sleeping.
 */
class ArmedOpportunityTest {

    private var now = 100_000L

    @Before
    fun setUp() {
        now = 100_000L
        ExecutionConfig.stagedExecutionEnabled = true
        ExecutionConfig.armTtlMs = 2000
        ExecutionConfig.profitThreshold = 0.30
        ExecutionConfig.armingMarginPercent = 0.10
        ExecutionConfig.abandonMarginPercent = 0.05
    }

    @After
    fun tearDown() {
        ExecutionConfig.stagedExecutionEnabled = true
    }

    private fun features(maxAgeMs: Long = 20L) = TriangleFeatures(
        BookFeatures("AB", 2.0, 0.1, 120.0, maxAgeMs),
        BookFeatures("BC", 2.0, 0.1, 120.0, maxAgeMs),
        BookFeatures("CA", 2.0, 0.1, 120.0, maxAgeMs)
    )

    private fun decision(percent: Double, fireBar: Double = 0.40, abandonBar: Double = 0.25) =
        ExecutionGates.evaluate(
            ExecutionGates.Input(features(), percent, now, now, fireBar, abandonBar),
            ttlMs = ExecutionConfig.armTtlMs
        )

    private fun registry(slots: Int = 8) = ArmedOpportunityBook({ slots }) { now }

    @Test
    fun `arming captures the bars so later threshold drift cannot redefine it`() {
        val registry = registry()
        val arm = registry.arm("BTC-ETH-BNB", 2000, 0.40, 0.25, 0.35)
        assertEquals(0.40, arm.fireBar, 1e-12)
        assertEquals(0.25, arm.abandonBar, 1e-12)
        // The engine thresholds move afterwards; the arm keeps its own.
        ExecutionConfig.armingMarginPercent = 5.0
        assertEquals(0.40, registry.armOf("BTC-ETH-BNB")!!.fireBar, 1e-12)
    }

    @Test
    fun `a fire bar below break-even is raised to the floor at arming time`() {
        val registry = registry()
        val arm = registry.arm("X", 2000, -1.0, -2.0, 0.0)
        assertEquals(ExecutionGates.NO_LOSS_FLOOR, arm.fireBar, 1e-12)
    }

    @Test
    fun `holding is counted and reported, and nothing is executed`() {
        val registry = registry()
        registry.arm("X", 2000, 0.40, 0.25, 0.35)
        now += 100L
        assertEquals(ArmOutcome.WAITING, registry.apply("X", decision(0.35)))
        now += 100L
        assertEquals(ArmOutcome.WAITING, registry.apply("X", decision(0.36)))
        val arm = registry.armOf("X")!!
        assertEquals(2, arm.pings)
        assertEquals(2, arm.waits)
        assertEquals(1, registry.size)
    }

    @Test
    fun `firing releases the arm and records how long it was held`() {
        val registry = registry()
        registry.arm("X", 2000, 0.40, 0.25, 0.35)
        now += 300L
        assertEquals(ArmOutcome.FIRED, registry.apply("X", decision(0.55)))
        assertEquals(0, registry.size)
        assertNull(registry.armOf("X"))
        val stats = registry.snapshot()
        assertEquals(1L, stats.fired)
        assertEquals(1L, stats.episodes)
        assertEquals(300L, stats.medianTimeToFireMs)
        assertTrue(stats.lastEvent!!.contains("fired X after 300ms"))
    }

    @Test
    fun `abandoning releases the arm and is counted apart from firing`() {
        val registry = registry()
        registry.arm("X", 2000, 0.40, 0.25, 0.35)
        now += 500L
        assertEquals(ArmOutcome.ABANDONED, registry.apply("X", decision(0.10)))
        assertEquals(0, registry.size)
        val stats = registry.snapshot()
        assertEquals(1L, stats.abandoned)
        assertEquals(0L, stats.fired)
        assertEquals(500L, stats.medianTimeToAbandonMs)
    }

    @Test
    fun `an arm expires on the timer because quiet books are never pinged`() {
        val registry = registry()
        registry.arm("X", 2000, 0.40, 0.25, 0.35)
        now += 1_500L
        assertTrue(registry.expire().isEmpty())
        now += 600L
        val expired = registry.expire()
        assertEquals(1, expired.size)
        assertEquals(ArmOutcome.EXPIRED, expired[0].outcome)
        assertEquals("X", expired[0].id)
        assertTrue(expired[0].reason.contains("arm expired"))
        assertEquals(0, registry.size)
        assertEquals(1L, registry.snapshot().expired)
    }

    @Test
    fun `a zero window never expires on the timer`() {
        val registry = registry()
        registry.arm("X", 0, 0.40, 0.25, 0.35)
        now += 600_000L
        assertTrue(registry.expire().isEmpty())
        assertEquals(1, registry.size)
    }

    @Test
    fun `slots are bounded and the oldest arm is reported as dropped`() {
        val registry = registry(slots = 2)
        registry.arm("A", 2000, 0.40, 0.25, 0.35)
        now += 50L
        registry.arm("B", 2000, 0.40, 0.25, 0.35)
        now += 50L
        registry.arm("C", 2000, 0.40, 0.25, 0.35)
        assertEquals(2, registry.size)
        assertEquals(listOf("B", "C"), registry.armedIds())
        assertEquals(1L, registry.snapshot().abandoned)
        val record = registry.recentRecords().first { it.id == "A" }
        assertTrue(record.reason.contains("no arming slot free"))
    }

    @Test
    fun `re-arming the same triangle starts a fresh window rather than stacking`() {
        val registry = registry()
        registry.arm("X", 2000, 0.40, 0.25, 0.35)
        now += 400L
        val again = registry.arm("X", 2000, 0.45, 0.28, 0.44)
        assertEquals(1, registry.size)
        assertEquals(0, again.pings)
        assertEquals(now, again.armedAt)
        assertEquals(0.45, again.fireBar, 1e-12)
    }

    @Test
    fun `a verdict for an unknown triangle changes nothing`() {
        val registry = registry()
        assertEquals(ArmOutcome.WAITING, registry.apply("GHOST", decision(0.9)))
        assertEquals(0, registry.size)
    }

    @Test
    fun `the best percent seen while waiting is remembered`() {
        val registry = registry()
        registry.arm("X", 2000, 0.40, 0.25, 0.35)
        registry.apply("X", decision(0.38))
        registry.apply("X", decision(0.39))
        registry.apply("X", decision(0.36))
        assertEquals(0.39, registry.armOf("X")!!.bestPercent, 1e-12)
        assertEquals(0.36, registry.armOf("X")!!.lastPercent, 1e-12)
    }

    @Test
    fun `wait statistics accumulate across episodes`() {
        val registry = registry()
        registry.arm("A", 2000, 0.40, 0.25, 0.35)
        registry.apply("A", decision(0.35))
        registry.apply("A", decision(0.36))
        now += 100L
        assertEquals(ArmOutcome.FIRED, registry.apply("A", decision(0.50)))
        now += 100L
        registry.arm("B", 2000, 0.40, 0.25, 0.35)
        registry.apply("B", decision(0.35))
        now += 100L
        assertEquals(ArmOutcome.FIRED, registry.apply("B", decision(0.60)))
        val stats = registry.snapshot()
        assertEquals(2L, stats.fired)
        assertEquals(1L, stats.medianWaitsBeforeFire)
        assertEquals(0.55, stats.medianFiredPercent, 1e-9)
        assertEquals(2, stats.outcomes["FIRED"]?.toInt())
    }

    @Test
    fun `the reported bars follow the current engine configuration`() {
        ExecutionConfig.profitThreshold = 0.30
        ExecutionConfig.armingMarginPercent = 0.12
        ExecutionConfig.abandonMarginPercent = 0.04
        val stats = registry().snapshot()
        assertEquals(0.42, stats.fireBarPercent, 1e-9)
        assertEquals(0.26, stats.abandonBarPercent, 1e-9)
    }

    @Test
    fun `clearing the book drops every arm without recording a trade`() {
        val registry = registry()
        registry.arm("A", 2000, 0.40, 0.25, 0.35)
        registry.arm("B", 2000, 0.40, 0.25, 0.35)
        registry.clear()
        assertEquals(0, registry.size)
        assertEquals(0L, registry.snapshot().fired)
        assertEquals(0L, registry.snapshot().abandoned)
    }

    @Test
    fun `the summary says so plainly when staging is switched off`() {
        ExecutionConfig.stagedExecutionEnabled = false
        assertTrue(registry().snapshot().message().contains("staged execution off"))
        ExecutionConfig.stagedExecutionEnabled = true
        assertTrue(registry().snapshot().message().contains("waiting for the first opportunity"))
    }

    @Test
    fun `an armed opportunity exposes its own age and reason`() {
        val arms = registry()
        val arm = arms.arm("X", 2000, 0.40, 0.25, 0.35)
        assertNotNull(arm.summary())
        now += 750L
        assertEquals(750L, arm.ageMs(now))
    }

    @Test
    fun `the aggregate reports the real median armed and best percents`() {
        // Regression: medianArmedPercent was hardcoded 0.0 and medianBestPercent
        // was a duplicate of medianFiredPercent, so the two columns the Stage 3
        // thesis depends on were both wrong.
        val registry = registry()
        // Three arms that are pinged upward but never reach the fire bar, so
        // all three close on the TTL - one closed episode per armed/best pair.
        for ((armed, best) in listOf(0.30 to 0.45, 0.40 to 0.42, 0.50 to 0.35)) {
            registry.arm("T-$armed", 100, 0.55, 0.20, armed)
            registry.apply("T-$armed", decision(best, fireBar = 0.55, abandonBar = 0.20), features())
        }
        now += 200
        assertEquals(3, registry.expire().size)
        val stats = registry.snapshot()
        assertEquals(0.40, stats.medianArmedPercent, 1e-9)
        // bestPercent is a high-water mark: the arm that opened at 0.50 and was
        // only pinged down to 0.35 still counts as having reached 0.50.
        assertEquals(0.45, stats.medianBestPercent, 1e-9)
        // Distinct values: they must not be reporting the same column.
        assertTrue(stats.medianArmedPercent != stats.medianBestPercent)
    }

    @Test
    fun `an expired arm keeps the features it was last measured against`() {
        val registry = registry()
        registry.arm("T-1", 100, 0.55, 0.20, 0.30)
        registry.apply("T-1", decision(0.31, fireBar = 0.55, abandonBar = 0.20), features())
        now += 500
        val expired = registry.expire()
        assertEquals(1, expired.size)
        // Without this the EXPIRED row had no book state to report at all.
        assertNotNull(expired[0].features)
        assertEquals(2.0, expired[0].features!!.maxSpreadBps, 1e-9)
    }

    @Test
    fun `an arm that never pinged reports no features rather than fabricated ones`() {
        val registry = registry()
        registry.arm("T-2", 100, 0.55, 0.20, 0.30)
        now += 500
        val expired = registry.expire()
        assertEquals(1, expired.size)
        assertNull(expired[0].features)
    }
}
