package com.hakim3691.bta.scanner

/**
 * Counts how many complete passes the scanner has made over the whole triangle
 * universe.
 *
 * The scan itself is event driven - each websocket depth update re-evaluates
 * only the triangles containing the ticker that moved, and those events recur
 * forever, so the same combinations are revisited continuously and never in a
 * fixed round-robin order. "Cycles" therefore counts *events*, which says
 * nothing about coverage.
 *
 * This tracker answers the coverage question instead: a loop is complete once
 * every known combination has been evaluated at least one time since the
 * previous completion. It is pure and clock-injected so it can be unit tested
 * without a websocket.
 */
class LoopTracker(
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var universe: Set<String> = emptySet()
    private var pending: MutableSet<String> = LinkedHashSet()

    /** Completed passes over the universe. */
    var loopCount: Long = 0L
        private set

    /** Cumulative triangle evaluations, i.e. total work done. */
    var trianglesEvaluated: Long = 0L
        private set

    /** Wall-clock ms of the last completed loop. */
    var lastLoopMs: Long = 0L
        private set

    /** When the in-progress loop started. */
    var loopStartedAt: Long = 0L
        private set

    /** Combinations in the universe (denominator of a loop). */
    val total: Int get() = universe.size

    /** Combinations still unseen in the current loop. */
    val remaining: Int get() = pending.size

    /**
     * Combinations this loop has not reached yet. Non-zero at the end of a run
     * means something in the universe can never be visited - typically a
     * triangle whose legs are all permanently dead tickers, so no depth event
     * ever names it. Surfaced rather than left as a mystery 99%.
     */
    val blocked: Int get() = pending.size

    /** Combinations already seen in the current loop. */
    val covered: Int get() = (total - pending.size).coerceAtLeast(0)

    /** Progress through the current loop, 0..1. */
    val progress: Float
        get() = if (total > 0) (covered.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f

    /** Loads a new universe (call after MarketCache is initialized) and starts loop 1. */
    fun reset(universeIds: Collection<String>) {
        universe = LinkedHashSet(universeIds)
        pending = LinkedHashSet(universe)
        loopStartedAt = clock()
    }

    /**
     * Records the combinations reached in one scan cycle.
     *
     * @param evaluatedIds combinations that were actually priced. This is the
     *   work counter, and is deliberately narrower than coverage.
     * @param visitedIds every combination the cycle reached a decision about,
     *   including ones deliberately skipped as stale. Coverage means "the
     *   scanner has now looked at this", and a triangle rejected for a stale leg
     *   has been looked at just as surely as one that produced a price.
     * @return true when this call completed a loop (loopCount has advanced).
     */
    @JvmOverloads
    fun record(
        evaluatedIds: Collection<String>,
        visitedIds: Collection<String> = evaluatedIds
    ): Boolean {
        if (universe.isEmpty()) return false
        // An empty pending set means the previous cycle closed a loop; the next
        // cycle must not be credited to it.
        if (pending.isEmpty()) reset(universe)

        for (id in visitedIds) pending.remove(id)
        trianglesEvaluated += evaluatedIds.size.toLong()

        if (pending.isNotEmpty()) return false

        loopCount++
        lastLoopMs = if (loopStartedAt > 0L) clock() - loopStartedAt else 0L
        pending = LinkedHashSet(universe)
        loopStartedAt = clock()
        return true
    }

    fun snapshot(): LoopSnapshot = LoopSnapshot(
        loopCount = loopCount,
        trianglesEvaluated = trianglesEvaluated,
        trianglesTotal = total,
        trianglesCovered = covered,
        progress = progress,
        lastLoopMs = lastLoopMs,
        blocked = blocked
    )
}

data class LoopSnapshot(
    val loopCount: Long,
    val trianglesEvaluated: Long,
    val trianglesTotal: Int,
    val trianglesCovered: Int,
    val progress: Float,
    val lastLoopMs: Long,
    /** Combinations never reached yet; a non-zero tail means unreachable legs. */
    val blocked: Int = 0
)
