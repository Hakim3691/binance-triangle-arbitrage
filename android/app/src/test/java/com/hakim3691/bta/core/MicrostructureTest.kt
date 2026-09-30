package com.hakim3691.bta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The book measurements the staged executor gates on.
 *
 * These are descriptions of the book as it is now, so the tests pin them to
 * arithmetic that can be checked by hand rather than to any forecast.
 */
class MicrostructureTest {

    private fun book(bidQty: Double, askQty: Double) = DepthSnapshot(
        bids = linkedMapOf(100.0 to bidQty, 99.0 to bidQty),
        asks = linkedMapOf(100.02 to askQty, 100.04 to askQty)
    )

    @Test
    fun `spread is measured from the top of book in basis points`() {
        // (100.02 - 100.00) / 100.01 * 10000
        assertEquals(1.9998, Microstructure.spreadBps(book(1.0, 1.0)), 0.001)
    }

    @Test
    fun `spread is unmeasurable when a side is empty`() {
        assertTrue(Microstructure.spreadBps(DepthSnapshot.EMPTY).isNaN())
        val oneSided = DepthSnapshot(bids = linkedMapOf(100.0 to 1.0), asks = emptyMap())
        assertTrue(Microstructure.spreadBps(oneSided).isNaN())
    }

    @Test
    fun `a crossed book does not report a negative spread`() {
        val crossed = DepthSnapshot(
            bids = linkedMapOf(100.0 to 1.0),
            asks = linkedMapOf(99.0 to 1.0)
        )
        assertTrue(Microstructure.spreadBps(crossed).isNaN())
    }

    @Test
    fun `spread percent is the basis point figure in price units`() {
        assertEquals(0.019998, Microstructure.spreadPercent(book(1.0, 1.0)), 1e-6)
        assertEquals(0.0, Microstructure.spreadPercent(DepthSnapshot.EMPTY), 0.0)
    }

    @Test
    fun `imbalance is normalized across the top levels`() {
        // bids 5+5 = 10, asks 4+1 = 5 -> (10-5)/15
        val snapshot = DepthSnapshot(
            bids = linkedMapOf(100.0 to 5.0, 99.0 to 5.0),
            asks = linkedMapOf(100.02 to 4.0, 100.04 to 1.0)
        )
        assertEquals(1.0 / 3.0, Microstructure.imbalance(snapshot), 1e-12)
    }

    @Test
    fun `imbalance flips sign when the ask side is heavier`() {
        val snapshot = DepthSnapshot(
            bids = linkedMapOf(100.0 to 1.0),
            asks = linkedMapOf(100.02 to 3.0)
        )
        assertEquals(-0.5, Microstructure.imbalance(snapshot), 1e-12)
    }

    @Test
    fun `imbalance is neutral on an empty book`() {
        assertEquals(0.0, Microstructure.imbalance(DepthSnapshot.EMPTY), 0.0)
    }

    @Test
    fun `an unmeasured spread is never treated as tight`() {
        assertFalse(BookFeatures.UNKNOWN.spreadKnown)
        assertTrue(BookFeatures("AB", 2.0, 0.0, 0.0, 0L).spreadKnown)
    }

    @Test
    fun `a triangle is described by its worst leg`() {
        val features = TriangleFeatures(
            BookFeatures("AB", 2.0, 0.1, 100.0, 10L),
            BookFeatures("BC", 9.0, -0.6, 300.0, 40L),
            BookFeatures("CA", 4.0, 0.2, 200.0, 25L)
        )
        assertEquals(9.0, features.maxSpreadBps, 1e-12)
        assertEquals(0.6, features.maxAbsImbalance, 1e-12)
        assertEquals(300.0, features.maxInterArrivalMs, 1e-12)
        assertEquals(40L, features.stalestAgeMs)
        assertEquals("BC", features.widestTicker)
    }

    @Test
    fun `cadence starts unmeasured and then tracks the mean gap`() {
        val tracker = CadenceTracker()
        assertEquals(0.0, tracker.record("ETHBTC", 1_000L), 0.0)
        assertEquals(100.0, tracker.record("ETHBTC", 1_100L), 0.0 + 100.0)
        // 0.2 * 300 + 0.8 * 100
        assertEquals(140.0, tracker.record("ETHBTC", 1_400L), 1e-9)
        assertEquals(140.0, tracker.interArrivalMs("ETHBTC"), 1e-9)
        assertEquals(0.0, tracker.interArrivalMs("UNSEEN"), 0.0)
        assertEquals(1, tracker.tickers())
    }

    @Test
    fun `a clock that does not move forward never produces a gap`() {
        val tracker = CadenceTracker()
        tracker.record("ETHBTC", 5_000L)
        assertEquals(0.0, tracker.record("ETHBTC", 4_000L), 0.0)
    }

    @Test
    fun `cadence is per ticker`() {
        val tracker = CadenceTracker()
        tracker.record("A", 0L)
        tracker.record("A", 100L)
        tracker.record("B", 0L)
        tracker.record("B", 900L)
        assertEquals(100.0, tracker.interArrivalMs("A"), 1e-9)
        assertEquals(900.0, tracker.interArrivalMs("B"), 1e-9)
    }

    @Test
    fun `samples summarize what the books have been doing`() {
        val samples = MicrostructureSamples()
        assertEquals(0.0, samples.worstLegSpreadBps(), 0.0)
        for (spread in listOf(2.0, 4.0, 6.0, 8.0)) {
            samples.observe(
                TriangleFeatures(
                    BookFeatures("AB", spread, 0.0, 0.0, 0L),
                    BookFeatures("BC", spread, 0.0, 0.0, 0L),
                    BookFeatures("CA", spread, 0.0, 0.0, 0L)
                )
            )
        }
        assertEquals(4, samples.sampleCount())
        assertEquals(5.0, samples.worstLegSpreadBps(), 1e-9)
    }

    @Test
    fun `sample capacity is bounded`() {
        val samples = MicrostructureSamples(capacity = 10)
        repeat(50) { i ->
            samples.observe(
                TriangleFeatures(
                    BookFeatures("AB", i.toDouble(), 0.0, 0.0, 0L),
                    BookFeatures("BC", 1.0, 0.0, 0.0, 0L),
                    BookFeatures("CA", 1.0, 0.0, 0.0, 0L)
                )
            )
        }
        assertEquals(10, samples.sampleCount())
        // Only the last ten survive, so the median tracks the recent window.
        assertEquals(44.5, samples.worstLegSpreadBps(), 1e-9)
    }
}
