package com.hakim3691.bta.core

/**
 * Self-tuning execution freshness limit.
 *
 * Mobile networks produce book ages (time since the stalest leg's last
 * update) that vary with latency and market activity. Rather than asking the
 * user to pick a millisecond threshold, the app records the stalest-leg age
 * of every analyzed opportunity and continuously sets the execution gate to
 * the 90th percentile of observed ages with a safety multiplier.
 *
 * The result is clamped to [minMs, maxMs] so the gate can never become
 * trivially permissive or absurdly strict.
 */
class AgeThresholdTuner(
    private val windowSize: Int = 300,
    private val minMs: Long = 1_000,
    private val maxMs: Long = 15_000,
    private val percentile: Double = 0.90,
    private val multiplier: Double = 1.5,
    private val minSamples: Int = 30
) {
    private val samples = ArrayDeque<Long>()

    /** Records one observation of stalest-leg age in milliseconds. */
    fun record(ageMs: Long) {
        if (ageMs < 0 || ageMs > 60_000) return // ignore junk (uninitialized clocks etc.)
        synchronized(samples) {
            samples.addLast(ageMs)
            while (samples.size > windowSize) samples.removeFirst()
        }
    }

    /** Number of retained samples. */
    fun sampleCount(): Int = synchronized(samples) { samples.size }

    /**
     * Discards every observation.
     *
     * Ages recorded while the websocket is down describe a disconnection, not
     * market behaviour. Without this, one outage trains p90 towards the ceiling
     * and leaves the gate far more permissive than normal operation warrants.
     */
    fun reset() {
        synchronized(samples) { samples.clear() }
    }

    /**
     * Computes the recommended threshold, or null while fewer than
     * [minSamples] observations exist.
     */
    fun computeThreshold(): Long? {
        val snapshot = synchronized(samples) { samples.toList() }
        if (snapshot.size < minSamples) return null
        val sorted = snapshot.sorted()
        val index = (sorted.size * percentile).toInt().coerceIn(0, sorted.size - 1)
        val p = sorted[index]
        return (p * multiplier).toLong().coerceIn(minMs, maxMs)
    }

    /** Seeds the tuner with an initial value (e.g. from measured REST latency). */
    fun seedFromLatency(latencyMs: Long) {
        if (latencyMs <= 0) return
        record((latencyMs * 3).coerceIn(minMs, maxMs))
    }
}
