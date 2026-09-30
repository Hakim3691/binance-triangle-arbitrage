package com.hakim3691.bta.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Synced" and "fresh" are different questions, and confusing them is what let
 * a ten-minute websocket outage render as a healthy 325/325.
 */
class DepthFreshnessTest {

    /** Clock the cache stamps snapshots with, so ages are exact rather than timed. */
    private var now = 1_000_000L
    private fun cache() = DepthCacheManager { now }

    /** Seeds a ticker while pretending it was received [ageMs] ago. */
    private fun snapshot(
        cache: DepthCacheManager,
        symbol: String,
        ageMs: Long,
        at: Long
    ) {
        now = at - ageMs
        cache.applySnapshot(symbol, 1L, mapOf(100.0 to 1.0), mapOf(101.0 to 1.0))
        now = at
    }

    @Test
    fun `an unregistered ticker is neither synced nor fresh`() {
        val c = cache()
        assertFalse(c.isSynced("BTCUSDT"))
        assertFalse(c.isFresh("BTCUSDT", maxAgeMs = 5_000))
    }

    @Test
    fun `synced stays true while fresh goes false`() {
        val c = cache()
        val now = 1_000_000L
        c.register(listOf("BTCUSDT"))
        snapshot(c, "BTCUSDT", ageMs = 1_000, at = now)

        assertTrue("a seeded ticker is synced", c.isSynced("BTCUSDT"))
        assertTrue(c.isFresh("BTCUSDT", maxAgeMs = 5_000, now = now))

        // Ten minutes later the snapshot is still there but the book is dead.
        val later = now + 600_000
        assertTrue(c.isSynced("BTCUSDT"))
        assertFalse(c.isFresh("BTCUSDT", maxAgeMs = 5_000, now = later))
    }

    @Test
    fun `freshness separates synced from fresh across the universe`() {
        val c = cache()
        val now = 1_000_000L
        c.register(listOf("A", "B", "C", "D"))

        // A and B are current, C is old, D never synced.
        snapshot(c, "A", ageMs = 0, at = now)
        snapshot(c, "B", ageMs = 100, at = now)
        snapshot(c, "C", ageMs = 60_000, at = now)

        val f = c.freshness(maxAgeMs = 5_000, now = now)
        assertEquals(4, f.total)
        assertEquals(3, f.synced)
        assertEquals(2, f.fresh)
        assertEquals(listOf("C", "D"), f.staleTickers.sorted())
        assertEquals(60_000L, f.stalestAgeMs)
        // A is the newest book in the universe.
        assertEquals(0L, f.freshestAgeMs)
        assertFalse(f.feedDead)
    }

    @Test
    fun `feedDead only when nothing in the universe is current`() {
        val c = cache()
        val now = 1_000_000L
        c.register(listOf("A", "B", "C"))
        snapshot(c, "A", ageMs = 0, at = now)
        snapshot(c, "B", ageMs = 50, at = now)

        // A and B are current even though C never synced at all, so the feed is
        // alive: an unseeded ticker must not be mistaken for a dead feed.
        val f = c.freshness(maxAgeMs = 1_000, now = now)
        assertEquals(2, f.fresh)
        assertFalse(f.feedDead)
        assertEquals(listOf("C"), f.staleTickers)

        // Only once every book has gone old does it count as dead.
        assertTrue(c.freshness(maxAgeMs = 1_000, now = now + 60_000).feedDead)
    }

    @Test
    fun `a completely dead feed is reported as dead`() {
        val c = cache()
        val now = 1_000_000L
        c.register(listOf("A", "B"))
        snapshot(c, "A", ageMs = 0, at = now)
        snapshot(c, "B", ageMs = 0, at = now)

        val f = c.freshness(maxAgeMs = 1_000, now = now + 600_000)
        assertEquals(0, f.fresh)
        assertTrue(f.feedDead)
        assertEquals(2, f.synced)
    }
}
