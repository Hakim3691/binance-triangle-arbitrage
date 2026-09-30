package com.hakim3691.bta.core

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Order-book shape, measured from data we already receive.
 *
 * These are *descriptions of the book as it is right now*, not predictions of
 * where it is going. A book imbalance that reliably predicted the next print
 * would be arbitraged away within a few hundred milliseconds, and the only
 * imbalances that survive are the ones caused by an order passing through -
 * which means the signal is really describing the market's reaction to
 * liquidity, i.e. adverse selection. So the features below are used purely as
 * *gating heuristics*: they can make the engine patient, never make it
 * optimistic.
 */
data class BookFeatures(
    val ticker: String,
    /** (bestAsk - bestBid) / mid, in basis points. [Double.NaN] when unmeasurable. */
    val spreadBps: Double,
    /** Resting size on the bid side minus the ask side, normalized to -1..1. */
    val imbalance: Double,
    /** Mean time between updates of this book, ms. 0 when unmeasured. */
    val interArrivalMs: Double,
    /** Age of the book, ms. */
    val ageMs: Long
) {
    /** A spread we could not measure must never authorise waiting. */
    val spreadKnown: Boolean get() = spreadBps.isFinite() && spreadBps >= 0.0

    companion object {
        val UNKNOWN = BookFeatures("", Double.NaN, 0.0, 0.0, Long.MAX_VALUE)
    }
}

/** The three books a triangle trades through, with the roll-ups the gates need. */
data class TriangleFeatures(
    val ab: BookFeatures,
    val bc: BookFeatures,
    val ca: BookFeatures
) {
    /** A triangle is only as good as its worst book. */
    val maxSpreadBps: Double get() = maxOf(ab.spreadBps, bc.spreadBps, ca.spreadBps)

    val maxAbsImbalance: Double get() = maxOf(abs(ab.imbalance), abs(bc.imbalance), abs(ca.imbalance))

    val maxInterArrivalMs: Double get() = maxOf(ab.interArrivalMs, bc.interArrivalMs, ca.interArrivalMs)

    val stalestAgeMs: Long get() = maxOf(ab.ageMs, bc.ageMs, ca.ageMs)

    /** Book with the widest spread - reported so the reason strings name a ticker. */
    val widestTicker: String
        get() = when {
            ab.spreadBps >= bc.spreadBps && ab.spreadBps >= ca.spreadBps -> ab.ticker
            bc.spreadBps >= ca.spreadBps -> bc.ticker
            else -> ca.ticker
        }
}

/** Pure measurements over a [DepthSnapshot]. */
object Microstructure {

    /**
     * How many levels of each side take part in the imbalance. Twenty is deep
     * enough to be meaningful and shallow enough that it reflects the top of
     * book rather than the whole resting inventory.
     */
    const val IMBALANCE_LEVELS = 20

    /** Top-of-book spread in basis points, or NaN when either side is empty. */
    fun spreadBps(snapshot: DepthSnapshot): Double {
        val bestBid = snapshot.bids.keys.maxOrNull() ?: return Double.NaN
        val bestAsk = snapshot.asks.keys.minOrNull() ?: return Double.NaN
        if (bestBid <= 0.0 || bestAsk <= 0.0 || bestAsk < bestBid) return Double.NaN
        val mid = (bestAsk + bestBid) / 2.0
        if (mid <= 0.0) return Double.NaN
        return (bestAsk - bestBid) / mid * 10_000.0
    }

    /**
     * Top-of-book spread as a percent of mid price - the same units the paper
     * engine's slippage model uses.
     */
    fun spreadPercent(snapshot: DepthSnapshot): Double {
        val bps = spreadBps(snapshot)
        return if (bps.isFinite()) bps / 100.0 else 0.0
    }

    /**
     * (bidSize - askSize) / (bidSize + askSize) over the top [levels] of each
     * side. Positive means the bid side is heavier, i.e. more resting size
     * willing to sell into a market buy.
     */
    fun imbalance(snapshot: DepthSnapshot, levels: Int = IMBALANCE_LEVELS): Double {
        if (levels <= 0) return 0.0
        val bidQty = snapshot.bids.entries.take(levels).sumOf { it.value }
        val askQty = snapshot.asks.entries.take(levels).sumOf { it.value }
        val total = bidQty + askQty
        if (total <= 0.0 || !total.isFinite()) return 0.0
        return ((bidQty - askQty) / total).coerceIn(-1.0, 1.0)
    }

    fun features(
        ticker: String,
        snapshot: DepthSnapshot,
        interArrivalMs: Double,
        now: Long
    ): BookFeatures = BookFeatures(
        ticker = ticker,
        spreadBps = spreadBps(snapshot),
        imbalance = imbalance(snapshot),
        interArrivalMs = interArrivalMs,
        ageMs = if (snapshot.eventTime > 0L) now - snapshot.eventTime else Long.MAX_VALUE
    )

    fun triangleFeatures(
        trade: Trade,
        books: Map<String, DepthSnapshot>,
        cadence: (String) -> Double,
        now: Long
    ): TriangleFeatures? {
        val ab = books[trade.ab.ticker] ?: return null
        val bc = books[trade.bc.ticker] ?: return null
        val ca = books[trade.ca.ticker] ?: return null
        return TriangleFeatures(
            ab = features(trade.ab.ticker, ab, cadence(trade.ab.ticker), now),
            bc = features(trade.bc.ticker, bc, cadence(trade.bc.ticker), now),
            ca = features(trade.ca.ticker, ca, cadence(trade.ca.ticker), now)
        )
    }
}

/**
 * Exponential moving average of the time between updates to one book.
 *
 * A book that printed 40 times in 200ms is in flux and a round trip through it
 * is fragile; a book that has been quiet for seconds is stable. That is the
 * whole signal - it says how settled the book is, not where it is going.
 *
 * The clock is injected so the behaviour is testable without sleeping.
 */
class CadenceTracker(
    private val clock: () -> Long = System::currentTimeMillis,
    private val alpha: Double = 0.2
) {
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private val averages = ConcurrentHashMap<String, Double>()

    /**
     * Registers an update and returns the updated mean inter-arrival time in ms,
     * or 0 for the first update of a ticker.
     */
    fun record(ticker: String, now: Long = clock()): Double {
        val previous = lastSeen.put(ticker, now)
        if (previous == null || now <= previous) return averages[ticker] ?: 0.0
        val gap = (now - previous).toDouble()
        val current = averages[ticker]
        val next = if (current == null) gap else current + alpha * (gap - current)
        averages[ticker] = next
        return next
    }

    /** Mean inter-arrival time in ms, 0 when the ticker has never been seen. */
    fun interArrivalMs(ticker: String): Double = averages[ticker] ?: 0.0

    fun tickers(): Int = averages.size

    /** The current per-ticker mean gaps, for distribution-level statistics. */
    fun tickersSnapshot(): List<Double> = averages.values.toList()

    fun reset() {
        lastSeen.clear()
        averages.clear()
    }
}

/**
 * Rolling record of what the books actually look like, used to derive the
 * gate thresholds instead of hard-coding them.
 *
 * [worstLegSpreadBps] is the median over recent triangles of the *widest* leg
 * spread: the number that decides whether a triangle is tradable at all.
 * [p90AbsImbalance] is the 90th percentile of the largest per-leg imbalance,
 * so the derived limit sits inside the range the market normally produces
 * rather than at an invented value.
 */
class MicrostructureSamples(private val capacity: Int = 200) {
    private val spreads = ArrayDeque<Double>()
    private val imbalances = ArrayDeque<Double>()

    @Synchronized
    fun observe(features: TriangleFeatures) {
        if (features.maxSpreadBps.isFinite()) {
            spreads.addLast(features.maxSpreadBps)
            while (spreads.size > capacity) spreads.removeFirst()
        }
        val imbalance = features.maxAbsImbalance
        if (imbalance.isFinite()) {
            imbalances.addLast(imbalance)
            while (imbalances.size > capacity) imbalances.removeFirst()
        }
    }

    @Synchronized
    fun worstLegSpreadBps(): Double = median(spreads.toList())

    @Synchronized
    fun p90AbsImbalance(): Double = percentile(imbalances.toList(), 0.90)

    /** Percentile of the observed worst-leg spread, for the research log. */
    @Synchronized
    fun spreadPercentile(p: Double): Double = percentile(spreads.toList(), p)

    /** Percentile of the observed worst-leg imbalance, for the research log. */
    @Synchronized
    fun imbalancePercentile(p: Double): Double = percentile(imbalances.toList(), p)

    @Synchronized
    fun sampleCount(): Int = spreads.size

    /**
     * Median of the figures this sampler was fed. When fed with opportunity
     * lifetimes this is the median time the market actually keeps an edge
     * alive - the honest basis for an arm window, since the window is a
     * claim about the market and not about our own latency. The samples ride
     * in the spread deque because the sampler's purpose is "distribution of
     * one observed quantity over recent events".
     */
    @Synchronized
    fun medianStored(): Double = median(spreads.toList())

    fun reset() {
        spreads.clear()
        imbalances.clear()
    }

    private fun median(values: List<Double>): Double = MicrostructureSamples.median(values)

    companion object {
        fun median(values: List<Double>): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
        }

        fun percentile(values: List<Double>, p: Double): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            val clamped = p.coerceIn(0.0, 1.0)
            val index = ((sorted.size - 1) * clamped).toInt()
            return sorted[index]
        }
    }
}

/**
 * Rolling distribution of the best opportunity percent the scanner has seen.
 *
 * This is the base rate the whole Stage 3 decision rests on, and it used to be
 * recorded nowhere: [MicrostructureSamples] tracks spread and imbalance, and
 * arm episodes only ever recorded triangles that had already cleared the
 * profit gate. Without it there is no way to choose an arming bar from
 * evidence rather than from a guess, and no way to say how rare a given
 * threshold actually is.
 */
class EdgeSamples(private val capacity: Int = 4000) {
    private val percents = ArrayDeque<Double>()

    @Synchronized
    fun observe(percent: Double) {
        if (!percent.isFinite()) return
        percents.addLast(percent)
        while (percents.size > capacity) percents.removeFirst()
    }

    /** Percentile of the observed best-edge, or 0 when nothing has been seen. */
    @Synchronized
    fun percentile(p: Double): Double = MicrostructureSamples.percentile(percents.toList(), p)

    @Synchronized
    fun max(): Double = percents.maxOrNull() ?: 0.0

    /** How many observed cycles had a best edge at or above [threshold]. */
    @Synchronized
    fun countAtOrAbove(threshold: Double): Int = percents.count { it >= threshold }

    @Synchronized
    fun size(): Int = percents.size

    @Synchronized
    fun reset() {
        percents.clear()
    }
}
