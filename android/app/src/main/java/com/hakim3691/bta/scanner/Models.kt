package com.hakim3691.bta.scanner

import com.hakim3691.bta.core.CalculatedPosition

/** UI-facing representation of one triangular opportunity. */
data class OpportunityUi(
    val id: String,
    val symbolA: String,
    val symbolB: String,
    val symbolC: String,
    val abTicker: String,
    val bcTicker: String,
    val caTicker: String,
    val abMethod: String,
    val bcMethod: String,
    val caMethod: String,
    val percent: Double,
    val ageMs: Long,
    val abAgeMs: Long,
    val bcAgeMs: Long,
    val caAgeMs: Long,
    val investmentA: Double,
    val orderSizeAb: Double,
    val orderSizeBc: Double,
    val orderSizeCa: Double,
    val spentA: Double,
    val earnedB: Double,
    val earnedC: Double,
    val earnedA: Double,
    val abDepth: Int,
    val bcDepth: Int,
    val caDepth: Int,
    val timestamp: Long
) {
    companion object {
        fun from(calculated: CalculatedPosition, now: Long): OpportunityUi {
            val s = calculated.trade.symbol
            val depth = calculated.usedDepth
            return OpportunityUi(
                id = calculated.id,
                symbolA = s.a,
                symbolB = s.b,
                symbolC = s.c,
                abTicker = calculated.trade.ab.ticker,
                bcTicker = calculated.trade.bc.ticker,
                caTicker = calculated.trade.ca.ticker,
                abMethod = calculated.trade.ab.method,
                bcMethod = calculated.trade.bc.method,
                caMethod = calculated.trade.ca.method,
                percent = calculated.percent,
                ageMs = depth?.let { now - minOf(it.ab.eventTime, it.bc.eventTime, it.ca.eventTime) } ?: 0L,
                abAgeMs = depth?.let { now - it.ab.eventTime } ?: 0L,
                bcAgeMs = depth?.let { now - it.bc.eventTime } ?: 0L,
                caAgeMs = depth?.let { now - it.ca.eventTime } ?: 0L,
                investmentA = calculated.a.spent,
                orderSizeAb = calculated.ab.quantity,
                orderSizeBc = calculated.bc.quantity,
                orderSizeCa = calculated.ca.quantity,
                spentA = calculated.a.spent,
                earnedB = calculated.b.earned,
                earnedC = calculated.c.earned,
                earnedA = calculated.a.earned,
                abDepth = calculated.ab.depth,
                bcDepth = calculated.bc.depth,
                caDepth = calculated.ca.depth,
                timestamp = now
            )
        }
    }
}

data class ConnectionStatus(
    val wsStatus: String = "DISCONNECTED",
    val restOk: Boolean = false,
    val latencyMs: Long = 0,
    val syncedTickers: Int = 0,
    val totalTickers: Int = 0,
    /** Tickers whose books are current, not merely seeded with a snapshot. */
    val freshTickers: Int = 0,
    val lastError: String? = null
)

/**
 * Whether the order-book feed can be trusted right now.
 *
 * "Synced" is not the same as "fresh": a ticker keeps a snapshot forever once
 * seeded, so a dropped websocket used to look perfectly healthy while the books
 * went minutes stale.
 */
data class FeedHealth(
    val total: Int = 0,
    val synced: Int = 0,
    val fresh: Int = 0,
    /** Age of the oldest book, ms. */
    val stalestAgeMs: Long = 0,
    val maxFreshAgeMs: Long = 0,
    /**
     * Books older than the gate that are nonetheless counted as current,
     * because they tick slower than the gate by their own nature.
     */
    val quietExempt: Int = 0
) {
    /**
     * The whole feed is dead: no book in the universe is current.
     *
     * Derived from the counts rather than passed in, so it can never disagree
     * with them - the original bug was a caller deciding deadness separately.
     * An empty universe (total == 0) is "unknown", not dead.
     */
    val feedDead: Boolean get() = total > 0 && fresh == 0

    /** Some books are older than the gate but the feed as a whole is alive. */
    val degraded: Boolean get() = !feedDead && total > 0 && fresh < total

    val healthy: Boolean get() = !feedDead && total > 0 && fresh == total

    fun message(): String = when {
        feedDead -> "FEED DEAD - no book updates, scanning suspended"
        degraded -> "DEGRADED FEED - $fresh/$total books current, oldest " +
            "${stalestAgeMs / 1000}s old; stale triangles are skipped" +
            if (quietExempt > 0) " ($quietExempt quiet pairs excused)" else ""
        else -> "LIVE - $fresh/$total books current"
    }
}

data class ScanPerformance(
    /**
     * Scan events, one per websocket depth update. This is *not* a pass over
     * the triangle universe - a single event only re-evaluates the triangles
     * that contain the ticker that moved. See [loopCount] for full coverage.
     */
    val cycleCount: Long = 0,
    val cyclesPerSecond: Double = 0.0,
    val lastCycleMs: Long = 0,
    val avgCycleMs: Double = 0.0,
    /** Triangles evaluated in the latest cycle (profitable or not). */
    val scannedCount: Int = 0,
    /** Triangles with positive expected percent in the latest cycle. */
    val profitableCount: Int = 0,
    /**
     * Completed passes over the whole triangle universe: incremented every time
     * every known combination has been evaluated at least once since the
     * previous increment. This is the "have I seen everything yet" counter.
     */
    val loopCount: Long = 0,
    /** Cumulative triangle evaluations, i.e. how much work has been done. */
    val trianglesEvaluated: Long = 0,
    /** Combinations in the universe (the denominator of a loop). */
    val trianglesTotal: Int = 0,
    /** Progress through the current loop, 0..1. */
    val loopProgress: Float = 0f,
    /** Wall-clock ms taken by the last completed loop. */
    val lastLoopMs: Long = 0,
    /** Completed loops per minute of uptime, 0 before the scanner has started. */
    val loopsPerMinute: Double = 0.0
)
