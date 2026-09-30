package com.hakim3691.bta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeFilterTest {

    private val okBook = DepthSnapshot(bids = linkedMapOf(1.0 to 1.0), asks = linkedMapOf(2.0 to 1.0))
    private val emptyBook = DepthSnapshot(emptyMap(), emptyMap())

    private fun trade(ab: String, bc: String, ca: String) = Trade(
        Relationship("SELL", ab, "A", "B", 4),
        Relationship("BUY", bc, "C", "B", 4),
        Relationship("SELL", ca, "C", "A", 4),
        TradeSymbols("A", "B", "C")
    )

    @Test
    fun `trades with complete books pass through`() {
        val books = mapOf("AB" to okBook, "CB" to okBook, "CA" to okBook)
        val result = TradeFilter.filter(listOf(trade("AB", "CB", "CA")), books)
        assertEquals(1, result.complete.size)
        assertEquals(0, result.skippedTradeCount)
    }

    @Test
    fun `empty-book trades are skipped with ticker attribution`() {
        val books = mapOf("AB" to okBook, "CB" to emptyBook, "CA" to okBook)
        val result = TradeFilter.filter(listOf(trade("AB", "CB", "CA")), books)
        assertTrue(result.complete.isEmpty())
        assertEquals(1, result.skippedTradeCount)
        assertEquals(1, result.skippedByTicker["CB"])
    }

    @Test
    fun `missing snapshot treated as empty`() {
        val books = mapOf("AB" to okBook, "CB" to okBook) // CA missing entirely
        val result = TradeFilter.filter(listOf(trade("AB", "CB", "CA")), books)
        assertTrue(result.complete.isEmpty())
        assertEquals(1, result.skippedByTicker["CA"])
    }

    @Test
    fun `mixed batch filters correctly and counts`() {
        val books = mapOf("AB" to okBook, "CB" to okBook, "CA" to emptyBook, "DX" to emptyBook)
        val trades = listOf(
            trade("AB", "CB", "CA"),   // skipped (CA empty)
            trade("AB", "CB", "CA"),   // skipped (CA empty)
            trade("AB", "CB", "AB")    // ok
        )
        val result = TradeFilter.filter(trades, books)
        assertEquals(1, result.complete.size)
        assertEquals(2, result.skippedTradeCount)
        assertEquals(2, result.skippedByTicker["CA"])
    }
}
