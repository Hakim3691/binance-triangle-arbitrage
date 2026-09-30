package com.hakim3691.bta.core

/**
 * A sorted, bounded view of one side of an order book.
 * Bids are sorted descending by price; asks ascending - matching the original
 * `BinanceApi.sortBids` / `sortAsks` semantics.
 */
data class SortedDepth(
    /** price -> quantity, insertion-ordered by the sort described above. */
    val levels: Map<Double, Double>,
    /** Last websocket event time (epoch ms) covering this cache. 0 when unknown. */
    val eventTime: Long = 0L
) {
    val size: Int get() = levels.size

    companion object {
        val EMPTY = SortedDepth(emptyMap(), 0L)

        fun sortBids(cache: Map<Double, Double>, max: Int = Int.MAX_VALUE): Map<Double, Double> =
            cache.entries.sortedByDescending { it.key }.take(max).associate { it.key to it.value }

        fun sortAsks(cache: Map<Double, Double>, max: Int = Int.MAX_VALUE): Map<Double, Double> =
            cache.entries.sortedBy { it.key }.take(max).associate { it.key to it.value }
    }
}

/**
 * Port of the per-ticker depth snapshot consumed by CalculationNode
 * (`{ bids, asks, eventTime }` in the original).
 */
data class DepthSnapshot(
    val bids: Map<Double, Double>,
    val asks: Map<Double, Double>,
    val eventTime: Long = 0L
) {
    companion object {
        val EMPTY = DepthSnapshot(emptyMap(), emptyMap(), 0L)
    }
}
