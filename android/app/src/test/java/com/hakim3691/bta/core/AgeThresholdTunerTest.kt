package com.hakim3691.bta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgeThresholdTunerTest {

    @Test
    fun `returns null before enough samples`() {
        val tuner = AgeThresholdTuner(minSamples = 30)
        repeat(10) { tuner.record(100) }
        assertNull(tuner.computeThreshold())
    }

    @Test
    fun `threshold is p90 of observations times multiplier`() {
        val tuner = AgeThresholdTuner(minSamples = 10, windowSize = 100, minMs = 10, maxMs = 60_000)
        // ages 1..100 ms: p90 = 91 (index 9 of 10 after clamping), x1.5 = ~136
        for (i in 1..10) tuner.record(i * 10L)
        val threshold = tuner.computeThreshold()!!
        assertTrue("threshold=$threshold", threshold in 100..160)
    }

    @Test
    fun `clamped to configured bounds`() {
        val tuner = AgeThresholdTuner(minSamples = 5, minMs = 1_000, maxMs = 2_000)
        repeat(10) { tuner.record(50_000) }   // would exceed max
        assertEquals(2_000L, tuner.computeThreshold())
        val t2 = AgeThresholdTuner(minSamples = 5, minMs = 1_000, maxMs = 2_000)
        repeat(10) { t2.record(1) }           // would undercut min
        assertEquals(1_000L, t2.computeThreshold())
    }

    @Test
    fun `window keeps only recent samples`() {
        val tuner = AgeThresholdTuner(windowSize = 20, minSamples = 10, minMs = 10, maxMs = 60_000)
        repeat(100) { tuner.record(10) }      // old high ages leave the window
        repeat(20) { tuner.record(50) }
        val threshold = tuner.computeThreshold()!!
        assertTrue("threshold=$threshold", threshold < 200)
    }

    @Test
    fun `junk observations ignored`() {
        val tuner = AgeThresholdTuner(minSamples = 3)
        tuner.record(-5)
        tuner.record(999_999)
        assertEquals(0, tuner.sampleCount())
        tuner.record(100)
        assertEquals(1, tuner.sampleCount())
    }

    @Test
    fun `latency seeding produces sane initial value`() {
        // seed = 3x latency = 1500; compute = p90(1500) x 1.5 = 2250
        val tuner = AgeThresholdTuner(minSamples = 1, minMs = 1_000, maxMs = 15_000)
        tuner.seedFromLatency(500)
        assertEquals(2_250L, tuner.computeThreshold())
        // 3x10000 = 30000 clamped to max 15000 at seed; compute clamps again
        val t2 = AgeThresholdTuner(minSamples = 1, minMs = 1_000, maxMs = 15_000)
        t2.seedFromLatency(10_000)
        assertEquals(15_000L, t2.computeThreshold())
    }
}
