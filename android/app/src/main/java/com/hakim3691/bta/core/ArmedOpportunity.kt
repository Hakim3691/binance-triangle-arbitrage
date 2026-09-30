package com.hakim3691.bta.core

import java.util.concurrent.ConcurrentHashMap

/**
 * One triangle that has been recognised but not yet sent.
 *
 * The scanner arms an opportunity instead of executing it, then re-evaluates it
 * on every depth update that touches any of its three books. Because the scan
 * loop is already event-driven, that "constant ping" costs nothing extra: the
 * book move that wakes the triangle up is the same event that would otherwise
 * have re-priced it.
 *
 * The three bars are captured at arming time and then held fixed, so a
 * threshold that drifts under us (AutoTuner re-deriving the profit gate, say)
 * cannot silently redefine an opportunity that is already in flight.
 */
class ArmedOpportunity(
    val id: String,
    val armedAt: Long,
    val ttlMs: Int,
    val fireBar: Double,
    val abandonBar: Double,
    /** Percent at the moment the opportunity was recognised. */
    val armedPercent: Double
) {
    var pings: Int = 0
        private set
    var waits: Int = 0
        private set
    var bestPercent: Double = armedPercent
        private set
    var lastPercent: Double = armedPercent
        private set
    var lastReason: String = "armed at " + "%.4f".format(armedPercent) + "%, waiting for " +
        "%.4f".format(fireBar) + "%"
        private set

    fun ageMs(now: Long): Long = (now - armedAt).coerceAtLeast(0L)

    /**
     * Features seen at the most recent ping, so an episode that leaves the
     * book on the TTL timer can still be described by the book it was
     * measured against. Without this, expiry had no features to report.
     */
    var lastFeatures: TriangleFeatures? = null
        private set

    fun record(verdict: ExecutionGates.Verdict, features: TriangleFeatures? = null) {
        pings++
        if (features != null) lastFeatures = features
        lastPercent = verdict.projectedPercent
        if (verdict.projectedPercent > bestPercent) bestPercent = verdict.projectedPercent
        lastReason = verdict.reason
    }

    fun recordWait() {
        waits++
    }

    fun summary(): String = id + " armed " + ageMs(System.currentTimeMillis()) + "ms, " +
        pings + " pings, " + waits + " waits, last: " + lastReason
}

/** Why an armed opportunity left the book without being sent. */
enum class ArmOutcome { FIRED, WAITING, ABANDONED, EXPIRED }

/** A completed arming episode, kept for the dashboard telemetry. */
data class ArmRecord(
    val id: String,
    val outcome: ArmOutcome,
    val reason: String,
    val durationMs: Long,
    val pings: Int,
    val waits: Int,
    val armedPercent: Double,
    val bestPercent: Double,
    val firedPercent: Double,
    /** Book state at the last ping, when the episode could capture it. */
    val features: TriangleFeatures? = null
)

/**
 * Aggregate view of how the staged executor is behaving.
 *
 * [medianTimeToFireMs] and the abandonment breakdown are the numbers worth
 * watching: they say how long an opportunity survives after it is recognised,
 * which is what decides whether waiting for a better moment is worthwhile at
 * all.
 */
data class ArmingStats(
    val enabled: Boolean = false,
    val armed: Int = 0,
    val slots: Int = 0,
    val fireBarPercent: Double = 0.0,
    val abandonBarPercent: Double = 0.0,
    val ttlMs: Int = 0,
    val episodes: Long = 0,
    val fired: Long = 0,
    val abandoned: Long = 0,
    val expired: Long = 0,
    val medianTimeToFireMs: Long = 0,
    val medianTimeToAbandonMs: Long = 0,
    val medianWaitsBeforeFire: Long = 0,
    val medianFiredPercent: Double = 0.0,
    /** Median percent the episode was armed at, across recent episodes. */
    val medianArmedPercent: Double = 0.0,
    /** Median of the best percent each episode reached while armed. */
    val medianBestPercent: Double = 0.0,
    /** Outcome -> count, most recent first in the UI. */
    val outcomes: Map<String, Long> = emptyMap(),
    val armedIds: List<String> = emptyList(),
    val lastEvent: String? = null
) {
    fun message(): String = when {
        !enabled -> "staged execution off - trades fire on the first print that clears the gate"
        armed == 0 && episodes == 0L -> "staged execution armed - waiting for the first opportunity"
        else -> "armed " + armed + "/" + slots + "  fired " + fired + "  abandoned " +
            abandoned + "  expired " + expired + "  median wait " +
            (if (fired > 0) medianTimeToFireMs.toString() + "ms" else "-")
    }
}

/**
 * The registry of armed opportunities.
 *
 * Deliberately small and bounded: holding an opportunity is free, but the
 * engine must not let the book of arms grow without limit, so the oldest entry
 * is dropped when the slots are full and that drop is reported as an
 * abandonment rather than silently forgotten.
 *
 * The clock is injected so expiry is testable without sleeping.
 */
class ArmedOpportunityBook(
    private val maxArmed: () -> Int = { ExecutionConfig.maxArmedOpportunities },
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val armed = LinkedHashMap<String, ArmedOpportunity>()
    private val fireDurations = ArrayDeque<Long>()
    private val abandonDurations = ArrayDeque<Long>()
    private val fireWaits = ArrayDeque<Long>()
    private val firePercents = ArrayDeque<Double>()
    private val armedPercents = ArrayDeque<Double>()
    private val bestPercents = ArrayDeque<Double>()
    private val recent = ArrayDeque<ArmRecord>()
    private var episodes = 0L
    private var fired = 0L
    private var abandoned = 0L
    private var expired = 0L
    private var lastEvent: String? = null

    private val capacity = 200

    val size: Int get() = synchronized(armed) { armed.size }

    fun isArmed(id: String): Boolean = synchronized(armed) { armed.containsKey(id) }

    /** The live arm for [id], or null. Its bars are the ones captured at arming. */
    fun armOf(id: String): ArmedOpportunity? = synchronized(armed) { armed[id] }

    fun armedIds(): List<String> = synchronized(armed) { armed.keys.toList() }

    /** Replaces any existing arm for this triangle so the bars are not mixed. */
    fun arm(
        id: String,
        ttlMs: Int,
        fireBar: Double,
        abandonBar: Double,
        percent: Double
    ): ArmedOpportunity = synchronized(armed) {
        val existing = armed.remove(id)
        if (existing != null) {
            lastEvent = "re-armed " + id + " after " + existing.pings + " pings (" +
                existing.lastReason + ")"
        }
        val opportunity = ArmedOpportunity(
            id = id,
            armedAt = clock(),
            ttlMs = ttlMs,
            fireBar = maxOf(fireBar, ExecutionGates.NO_LOSS_FLOOR),
            abandonBar = abandonBar,
            armedPercent = percent
        )
        val limit = maxOf(1, maxArmed())
        while (armed.size >= limit) {
            val oldest = armed.keys.firstOrNull() ?: break
            val dropped = armed.remove(oldest) ?: break
            recordAbandon(ArmRecord(
                id = dropped.id,
                outcome = ArmOutcome.ABANDONED,
                reason = "no arming slot free (limit " + limit + ")",
                durationMs = dropped.ageMs(clock()),
                pings = dropped.pings,
                waits = dropped.waits,
                armedPercent = dropped.armedPercent,
                bestPercent = dropped.bestPercent,
                firedPercent = 0.0
            ), abandoned = true, expired = false)
        }
        armed[id] = opportunity
        opportunity
    }

    /** Applies a gate verdict to an armed triangle, returning the action taken. */
    fun apply(
        id: String,
        verdict: ExecutionGates.Verdict,
        features: TriangleFeatures? = null
    ): ArmOutcome = synchronized(armed) {
        val opportunity = armed[id] ?: return ArmOutcome.WAITING
        opportunity.record(verdict, features)
        val now = clock()
        when (verdict.decision) {
            ExecutionGates.Decision.FIRE -> {
                armed.remove(id)
                episodes++
                fired++
                val duration = opportunity.ageMs(now)
                push(fireDurations, duration)
                push(fireWaits, opportunity.waits.toLong())
                push(firePercents, verdict.projectedPercent)
                remember(ArmRecord(
                    id = id,
                    outcome = ArmOutcome.FIRED,
                    reason = verdict.reason,
                    durationMs = duration,
                    pings = opportunity.pings,
                    waits = opportunity.waits,
                    armedPercent = opportunity.armedPercent,
                    bestPercent = opportunity.bestPercent,
                    firedPercent = verdict.projectedPercent
                ))
                lastEvent = "fired " + id + " after " + duration + "ms / " + opportunity.waits +
                    " waits at " + "%.4f".format(verdict.projectedPercent) + "%"
                ArmOutcome.FIRED
            }

            ExecutionGates.Decision.WAIT -> {
                opportunity.recordWait()
                ArmOutcome.WAITING
            }

            ExecutionGates.Decision.ABANDON -> {
                armed.remove(id)
                episodes++
                abandoned++
                val duration = opportunity.ageMs(now)
                push(abandonDurations, duration)
                remember(ArmRecord(
                    id = id,
                    outcome = ArmOutcome.ABANDONED,
                    reason = verdict.reason,
                    durationMs = duration,
                    pings = opportunity.pings,
                    waits = opportunity.waits,
                    armedPercent = opportunity.armedPercent,
                    bestPercent = opportunity.bestPercent,
                    firedPercent = 0.0,
                    features = opportunity.lastFeatures
                ))
                lastEvent = "abandoned " + id + " after " + duration + "ms: " + verdict.reason
                ArmOutcome.ABANDONED
            }
        }
    }

    /**
     * Drops arms whose window has closed. Driven by a timer rather than by book
     * events, because a triangle whose books go quiet would otherwise never be
     * pinged again and would sit armed forever.
     */
    fun expire(): List<ArmRecord> {
        val now = clock()
        val out = ArrayList<ArmRecord>()
        synchronized(armed) {
            val iterator = armed.entries.iterator()
            while (iterator.hasNext()) {
                val opportunity = iterator.next().value
                if (opportunity.ttlMs <= 0) continue
                if (opportunity.ageMs(now) <= opportunity.ttlMs) continue
                iterator.remove()
                episodes++
                expired++
                push(abandonDurations, opportunity.ageMs(now))
                val record = ArmRecord(
                    id = opportunity.id,
                    outcome = ArmOutcome.EXPIRED,
                    reason = "arm expired after " + opportunity.ageMs(now) + "ms (ttl " +
                        opportunity.ttlMs + "ms)",
                    durationMs = opportunity.ageMs(now),
                    pings = opportunity.pings,
                    waits = opportunity.waits,
                    armedPercent = opportunity.armedPercent,
                    bestPercent = opportunity.bestPercent,
                    firedPercent = 0.0,
                    features = opportunity.lastFeatures
                )
                remember(record)
                out.add(record)
                lastEvent = "expired " + opportunity.id + " after " + opportunity.ageMs(now) + "ms"
            }
        }
        return out
    }

    fun clear() {
        synchronized(armed) {
            armed.clear()
            lastEvent = "arming cleared"
        }
    }

    fun recentRecords(limit: Int = 10): List<ArmRecord> =
        synchronized(armed) { recent.toList().takeLast(limit).reversed() }

    fun snapshot(): ArmingStats = synchronized(armed) {
        ArmingStats(
            enabled = ExecutionConfig.stagedExecutionEnabled,
            armed = armed.size,
            slots = maxOf(1, maxArmed()),
            fireBarPercent = ExecutionConfig.profitThreshold + ExecutionConfig.armingMarginPercent,
            abandonBarPercent = ExecutionConfig.profitThreshold - ExecutionConfig.abandonMarginPercent,
            ttlMs = ExecutionConfig.armTtlMs,
            episodes = episodes,
            fired = fired,
            abandoned = abandoned,
            expired = expired,
            medianTimeToFireMs = median(fireDurations),
            medianTimeToAbandonMs = median(abandonDurations),
            medianWaitsBeforeFire = median(fireWaits),
            medianFiredPercent = median(firePercents),
            medianArmedPercent = MicrostructureSamples.median(armedPercents.toList()),
            medianBestPercent = MicrostructureSamples.median(bestPercents.toList()),
            outcomes = recent.groupingBy { it.outcome.name }.eachCount().mapValues { it.value.toLong() },
            armedIds = armed.keys.toList(),
            lastEvent = lastEvent
        )
    }

    private fun <T> push(deque: ArrayDeque<T>, value: T) {
        deque.addLast(value)
        while (deque.size > capacity) deque.removeFirst()
    }

    private fun remember(record: ArmRecord) {
        // Every closed episode goes through here, so this is the one place
        // that can capture the armed and best percents for the aggregate.
        push(armedPercents, record.armedPercent)
        push(bestPercents, record.bestPercent)
        recent.addLast(record)
        while (recent.size > capacity) recent.removeFirst()
    }

    private fun recordAbandon(record: ArmRecord, abandoned: Boolean, expired: Boolean) {
        episodes++
        if (abandoned) this.abandoned++
        if (expired) this.expired++
        push(abandonDurations, record.durationMs)
        remember(record)
        lastEvent = "abandoned " + record.id + ": " + record.reason
    }

    private fun median(deque: ArrayDeque<Long>): Long {
        if (deque.isEmpty()) return 0L
        val sorted = deque.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private fun median(deque: ArrayDeque<Double>): Double {
        if (deque.isEmpty()) return 0.0
        val sorted = deque.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
