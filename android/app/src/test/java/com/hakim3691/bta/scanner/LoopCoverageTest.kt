package com.hakim3691.bta.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the "LOOPS 0 / progress stuck at 99%" bug.
 *
 * A loop is a coverage claim: every combination has been reached at least once
 * since the last completion. Triangles rejected for a stale leg have still been
 * reached - the scanner looked at them and decided not to price them. Crediting
 * only priced triangles made a loop impossible to close whenever any
 * combination was permanently inactive, because such a triangle is skipped on
 * every cycle for the life of the process. That pinned loopCount at 0 forever
 * and made LOOPS/MIN read 0.0, which looked like a dead scanner.
 */
class LoopCoverageTest {

    private fun tracker(vararg ids: String) =
        LoopTracker(clock = { 0L }).apply { reset(ids.toList()) }

    @Test
    fun `counts combinations skipped as stale toward coverage`() {
        val t = tracker("a", "b", "c")
        // b and c are reached but deliberately skipped (stale legs), so this one
        // cycle covers the whole universe and closes a loop.
        assertTrue(t.record(evaluatedIds = listOf("a"), visitedIds = listOf("a", "b", "c")))
        assertEquals(1L, t.loopCount)
        // The loop closed, so pending was refilled for the next pass: blocked
        // equals the whole universe, not a stranded remainder.
        assertEquals(3, t.total)
        assertEquals(3, t.blocked)
    }

    @Test
    fun `work counter still counts only priced triangles`() {
        val t = tracker("a", "b", "c")
        t.record(evaluatedIds = listOf("a"), visitedIds = listOf("a", "b", "c"))
        // Three reached, one priced: coverage and work must not be conflated.
        assertEquals(1L, t.trianglesEvaluated)
    }

    @Test
    fun `loop closes across cycles when one combination is always skipped`() {
        val t = tracker("a", "b", "c", "d")
        // "d" is dead for good, so it is only ever reached incidentally.
        assertFalse(t.record(listOf("a"), listOf("a", "b")))
        assertEquals(0L, t.loopCount)
        assertTrue(t.record(listOf("c"), listOf("c", "d")))
        assertEquals(1L, t.loopCount)
        // The meaningful assertion is that the loop completed at all. Under the
        // old code it stayed at 0 forever and progress hung just short of 100%.
        assertEquals(t.total, t.blocked)
    }

    @Test
    fun `blocked counts the stranded tail before a loop closes`() {
        val t = tracker("a", "b", "unreachable")
        t.record(listOf("a"), listOf("a"))
        // Only "a" has been reached, so "b" and "unreachable" are both pending.
        assertEquals(2, t.blocked)
        assertEquals(2, t.remaining)
        t.record(listOf("b"), listOf("b"))
        // "b" is now covered; "unreachable" is the permanent remainder.
        assertEquals(1, t.blocked)
        assertTrue("progress cannot complete while an id is stranded", t.progress < 1f)
    }

    @Test
    fun `reports combinations that can never be reached`() {
        val t = tracker("a", "b", "unreachable")
        t.record(listOf("a"), listOf("a"))
        t.record(listOf("b"), listOf("b"))
        // No depth event ever names "unreachable", so it stays pending.
        assertEquals(0L, t.loopCount)
        assertEquals(1, t.blocked)
        assertEquals(1, t.remaining)
    }

    @Test
    fun `default visited set keeps single-argument callers working`() {
        val t = tracker("a", "b")
        assertFalse(t.record(listOf("a")))
        assertTrue(t.record(listOf("b")))
        assertEquals(1L, t.loopCount)
    }

    @Test
    fun `each pass over a one-combination universe counts as its own loop`() {
        val t = tracker("a")
        assertTrue(t.record(listOf("a")))
        assertEquals(1L, t.loopCount)
        // The pending set is refilled on completion, so the next cycle begins a
        // fresh pass - and a universe of one is fully covered every time.
        assertTrue(t.record(listOf("a")))
        assertEquals(2L, t.loopCount)
    }
}
