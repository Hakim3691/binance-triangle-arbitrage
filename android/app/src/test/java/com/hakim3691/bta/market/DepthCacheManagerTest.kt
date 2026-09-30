package com.hakim3691.bta.market

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Order-book cache sync tests replicating node-binance-api semantics that the
 * original app depends on: buffered diffs before snapshot, discarded stale
 * events, gap-triggered resync, and quantity-zero deletions.
 */
class DepthCacheManagerTest {

    private fun diff(
        symbol: String = "ETHBTC",
        U: Long,
        u: Long,
        E: Long = 1_000L,
        bids: List<Pair<Double, Double>> = emptyList(),
        asks: List<Pair<Double, Double>> = emptyList()
    ) = DepthCacheManager.DiffEvent(symbol, U, u, E, bids, asks)

    @Test
    fun `diffs before snapshot are queued then replayed`() {
        val cache = DepthCacheManager()
        cache.register(listOf("ETHBTC"))

        // Buffer two diffs received before the snapshot
        assertFalse(cache.applyDiff(diff(U = 5, u = 6, bids = listOf(100.0 to 1.0))))
        assertFalse(cache.applyDiff(diff(U = 7, u = 8, bids = listOf(101.0 to 2.0))))

        // Snapshot arrives at 4: replay applies only u > 4
        assertTrue(cache.applySnapshot("ETHBTC", 4, mapOf(99.0 to 5.0), mapOf(102.0 to 3.0)))
        val snap = cache.getSortedSnapshot("ETHBTC")!!
        assertTrue(snap.bids.containsKey(100.0))
        assertTrue(snap.bids.containsKey(101.0))
        assertTrue(snap.bids.containsKey(99.0))
        assertTrue(cache.isSynced("ETHBTC"))
    }

    @Test
    fun `stale events older than snapshot are discarded`() {
        val cache = DepthCacheManager()
        cache.register(listOf("ETHBTC"))
        cache.applySnapshot("ETHBTC", 10, emptyMap(), emptyMap())

        // u=5 < snapshot+1 -> ignored
        assertFalse(cache.applyDiff(diff(U = 4, u = 5)))
    }

    @Test
    fun `gap between snapshot and stream triggers resync`() {
        val cache = DepthCacheManager()
        cache.register(listOf("ETHBTC"))
        cache.applySnapshot("ETHBTC", 10, mapOf(1.0 to 1.0), emptyMap())

        // U=20 > snapshot+1 -> out of sync, resync required
        assertFalse(cache.applyDiff(diff(U = 20, u = 21, bids = listOf(2.0 to 1.0))))
        assertEquals(setOf("ETHBTC"), cache.takeOutOfSyncSymbols())
    }

    @Test
    fun `consecutive updates apply and emit event`() = runTest {
        val cache = DepthCacheManager()
        cache.register(listOf("ETHBTC"))
        cache.applySnapshot("ETHBTC", 10, emptyMap(), emptyMap())

        val collector = launch {
            cache.updates.first()
        }
        val applied = cache.applyDiff(diff(U = 11, u = 12, E = 5555, bids = listOf(50.0 to 3.0)))
        assertTrue(applied)
        val snap = cache.getSortedSnapshot("ETHBTC")!!
        assertEquals(3.0, snap.bids[50.0]!!, 1e-12)
        // Snapshot eventTime is the LOCAL receive timeline (skew-free age math)
        assertTrue(snap.eventTime > 0)
        assertTrue(kotlin.math.abs(snap.eventTime - System.currentTimeMillis()) < 60_000)
        // Server-timeline event time remains available for display
        assertEquals(5555L, cache.getEventTime("ETHBTC"))
        collector.cancel()
    }

    @Test
    fun `zero quantity removes the level`() {
        val cache = DepthCacheManager()
        cache.register(listOf("ETHBTC"))
        cache.applySnapshot("ETHBTC", 10, mapOf(50.0 to 3.0), mapOf(60.0 to 4.0))

        cache.applyDiff(diff(U = 11, u = 12, bids = listOf(50.0 to 0.0), asks = listOf(60.0 to 0.0)))
        val snap = cache.getSortedSnapshot("ETHBTC")!!
        assertFalse(snap.bids.containsKey(50.0))
        assertFalse(snap.asks.containsKey(60.0))
    }

    @Test
    fun `bids sort descending and asks ascending`() {
        val cache = DepthCacheManager()
        cache.register(listOf("ETHBTC"))
        cache.applySnapshot(
            "ETHBTC", 10,
            mapOf(100.0 to 1.0, 102.0 to 1.0, 101.0 to 1.0),
            mapOf(200.0 to 1.0, 202.0 to 1.0, 201.0 to 1.0)
        )
        val snap = cache.getSortedSnapshot("ETHBTC")!!
        assertEquals(listOf(102.0, 101.0, 100.0), snap.bids.keys.toList())
        assertEquals(listOf(200.0, 201.0, 202.0), snap.asks.keys.toList())
    }

    @Test
    fun `getTickersWithoutRecentUpdate detects stale tickers`() {
        val cache = DepthCacheManager()
        cache.register(listOf("A", "B"))
        cache.applySnapshot("A", 1, emptyMap(), emptyMap())
        cache.applyDiff(diff(symbol = "A", U = 2, u = 3, E = 1000))

        // A's last event at t=1000; with maxAge 1500 it is fresh at t=2000 (age 1000)
        // B never received an update -> always stale; A was just touched (real clock) -> fresh
        val stale = cache.getTickersWithoutRecentUpdate(maxAgeMs = 1500)
        assertTrue(stale.contains("B"))
        assertFalse(stale.contains("A"))
    }

    @Test
    fun `valid depth resolution matches the original list`() {
        assertEquals(50, BinanceRestClient.resolveValidDepth(50))
        assertEquals(100, BinanceRestClient.resolveValidDepth(51))
        assertEquals(5000, BinanceRestClient.resolveValidDepth(5001))
        assertEquals(5, BinanceRestClient.resolveValidDepth(5))
    }
}
