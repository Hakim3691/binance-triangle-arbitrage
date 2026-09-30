package com.hakim3691.bta.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scan is event driven, so "cycles" cannot answer whether every combination
 * has been seen. These tests pin the coverage definition used by LOOPS.
 */
class LoopTrackerTest {

    private var now = 1_000L
    private fun tracker() = LoopTracker { now }

    private val universe = listOf("A-B-C", "A-B-D", "A-C-D", "B-C-D")

    @Test
    fun `a loop completes only when every combination has been seen`() {
        val t = tracker()
        t.reset(universe)

        assertFalse(t.record(listOf("A-B-C", "A-B-D")))
        assertFalse(t.record(listOf("A-C-D")))
        assertEquals(0L, t.loopCount)

        assertTrue(t.record(listOf("B-C-D")))
        assertEquals(1L, t.loopCount)
    }

    @Test
    fun `progress climbs as combinations are covered`() {
        val t = tracker()
        t.reset(universe)

        assertEquals(0f, t.progress, 1e-6f)
        t.record(listOf("A-B-C"))
        assertEquals(0.25f, t.progress, 1e-6f)
        t.record(listOf("A-B-D", "A-C-D"))
        assertEquals(0.75f, t.progress, 1e-6f)
        assertEquals(3, t.covered)
        assertEquals(1, t.remaining)
    }

    @Test
    fun `revisiting a combination inside the same loop does not double count`() {
        val t = tracker()
        t.reset(universe)

        t.record(listOf("A-B-C"))
        t.record(listOf("A-B-C"))
        t.record(listOf("A-B-C"))
        // Work done is cumulative, but coverage is not.
        assertEquals(3L, t.trianglesEvaluated)
        assertEquals(1, t.covered)
        assertEquals(0L, t.loopCount)
    }

    @Test
    fun `the same combinations repeat on later loops`() {
        val t = tracker()
        t.reset(universe)

        t.record(universe)
        assertEquals(1L, t.loopCount)
        // The next loop begins immediately, so coverage restarts from zero.
        assertEquals(0, t.covered)
        assertEquals(universe.size, t.remaining)

        // The next loop needs the full sweep again.
        t.record(listOf("A-B-C"))
        assertEquals(1, t.covered)
        assertEquals(1L, t.loopCount)

        t.record(listOf("A-B-D", "A-C-D", "B-C-D"))
        assertEquals(2L, t.loopCount)
        assertEquals(8L, t.trianglesEvaluated)
    }

    @Test
    fun `last loop duration is measured from the loop start`() {
        val t = tracker()
        t.reset(universe) // loop starts at t=1000

        now = 1_250L
        t.record(universe)
        assertEquals(250L, t.lastLoopMs)
    }

    @Test
    fun `empty universe never completes a loop`() {
        val t = tracker()
        t.reset(emptyList())
        assertFalse(t.record(listOf("A-B-C")))
        assertEquals(0L, t.loopCount)
        assertEquals(0f, t.progress, 1e-6f)
    }

    @Test
    fun `ids outside the universe are ignored for coverage`() {
        val t = tracker()
        t.reset(universe)
        assertFalse(t.record(listOf("A-B-C", "Z-Z-Z", "Q-R-S")))
        assertEquals(1, t.covered)
    }

    @Test
    fun `snapshot reports the same numbers as the tracker`() {
        val t = tracker()
        t.reset(universe)
        t.record(listOf("A-B-C", "A-B-D"))
        val s = t.snapshot()
        assertEquals(t.loopCount, s.loopCount)
        assertEquals(t.trianglesEvaluated, s.trianglesEvaluated)
        assertEquals(t.total, s.trianglesTotal)
        assertEquals(t.covered, s.trianglesCovered)
        assertEquals(t.progress, s.progress, 1e-6f)
    }

    @Test
    fun `reset to a new universe restarts counting`() {
        val t = tracker()
        t.reset(universe)
        t.record(universe)
        assertEquals(1L, t.loopCount)

        t.reset(listOf("X-Y-Z"))
        assertEquals(1, t.total)
        assertEquals(0, t.covered)
        // loopCount is cumulative uptime, not per-universe.
        assertEquals(1L, t.loopCount)
        assertTrue(t.record(listOf("X-Y-Z")))
        assertEquals(2L, t.loopCount)
    }
}

/**
 * Regression for the build that suspended the scanner forever: "some books are
 * old" is a degraded feed, not a dead one, and only a dead feed may stop the
 * scan.
 */
class FeedHealthTest {

    @Test
    fun `a partially fresh universe is degraded, not dead`() {
        val h = FeedHealth(total = 325, synced = 325, fresh = 284, stalestAgeMs = 40_000)
        assertTrue(h.degraded)
        assertFalse(h.feedDead)
        assertFalse(h.healthy)
        assertTrue(h.message().startsWith("DEGRADED"))
    }

    @Test
    fun `only a universe with no current books is dead`() {
        val h = FeedHealth(total = 325, synced = 325, fresh = 0, stalestAgeMs = 600_000)
        assertTrue(h.feedDead)
        assertFalse(h.degraded)
        assertEquals("FEED DEAD", h.message().split(" - ")[0])
    }

    @Test
    fun `a fully current universe is healthy`() {
        val h = FeedHealth(total = 325, synced = 325, fresh = 325, stalestAgeMs = 120)
        assertTrue(h.healthy)
        assertFalse(h.degraded)
        assertTrue(h.message().startsWith("LIVE"))
    }

    @Test
    fun `an empty universe is neither degraded nor dead`() {
        val h = FeedHealth()
        assertFalse(h.feedDead)
        assertFalse(h.degraded)
        assertFalse(h.healthy)
    }
}
