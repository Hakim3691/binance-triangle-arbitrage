package com.hakim3691.bta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The best-edge distribution is the base rate the Stage 3 decision is made
 * against: without it there is no evidence for choosing an arming bar, and no
 * way to say how rare a threshold actually is.
 */
class EdgeSamplesTest {

    @Test
    fun `percentiles describe the observed distribution`() {
        val s = EdgeSamples()
        for (v in listOf(-2.0, -1.0, -0.5, 0.0, 0.25, 0.5)) s.observe(v)

        assertEquals(6, s.size())
        assertEquals(-2.0, s.percentile(0.0), 1e-9)
        assertEquals(0.5, s.max(), 1e-9)
        // index = (n - 1) * p
        assertEquals(-1.0, s.percentile(0.20), 1e-9)
        assertEquals(-0.5, s.percentile(0.40), 1e-9)
        assertEquals(0.5, s.percentile(1.0), 1e-9)
    }

    @Test
    fun `an empty sampler reports zero rather than throwing`() {
        val s = EdgeSamples()
        assertEquals(0, s.size())
        assertEquals(0.0, s.percentile(0.5), 0.0)
        assertEquals(0.0, s.max(), 0.0)
        assertEquals(0, s.countAtOrAbove(0.4))
    }

    @Test
    fun `countAtOrAbove counts cycles that reached the bar`() {
        val s = EdgeSamples()
        for (v in listOf(-2.0, -0.1, 0.30, 0.40, 0.55, 0.39)) s.observe(v)
        assertEquals(2, s.countAtOrAbove(0.40))
        // 0.30, 0.40, 0.55 and 0.39 all reach 0.30.
        assertEquals(4, s.countAtOrAbove(0.30))
        assertEquals(0, s.countAtOrAbove(0.90))
    }

    @Test
    fun `non-finite samples are ignored`() {
        val s = EdgeSamples()
        s.observe(Double.NaN)
        s.observe(Double.POSITIVE_INFINITY)
        s.observe(-1.0)
        assertEquals(1, s.size())
        assertEquals(-1.0, s.max(), 1e-9)
    }

    @Test
    fun `the deque is bounded so a long session cannot grow without limit`() {
        val s = EdgeSamples(capacity = 10)
        for (i in 1..1000) s.observe(i.toDouble())
        assertEquals(10, s.size())
        assertEquals(1000.0, s.max(), 1e-9)
        assertEquals(991.0, s.percentile(0.0), 1e-9)
    }

    @Test
    fun `reset clears the window`() {
        val s = EdgeSamples()
        s.observe(1.0)
        s.reset()
        assertEquals(0, s.size())
        assertTrue(s.max() == 0.0)
    }
}
