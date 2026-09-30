package com.hakim3691.bta.core

/**
 * Stops a triangle that was profitable when it was *identified* from being
 * executed anyway once it is actually being sent.
 *
 * Three market orders in sequence are not instantaneous: by the second and
 * third leg the books have moved, and the percent that justified the trade may
 * no longer exist. The original engine sizes each leg from the previous actual
 * fill but never re-checks whether the trade is still worth doing, and it has
 * no deadline at all, so a slow round trip could grind through all three legs
 * into a loss.
 *
 * [verdict] is consulted before every leg:
 *  1. a **deadline** - if more than [ExecutionConfig.executionDeadlineMs] has
 *     elapsed since execution began, stop; an opportunity this old is not the
 *     one that was measured.
 *  2. a **re-pricing** - walk the *remaining* legs against freshly read books
 *     and require the projected percent to still clear the profit gate.
 *
 * Both are read from [ExecutionConfig] so the operator can disable either, and
 * the clock is injected so the behaviour is unit testable without a network.
 */
class PreFlightGuard(
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** How far through the round trip the guard is being consulted. */
    enum class Stage {
        /** No leg has filled yet; only the deadline applies. */
        BEFORE_FIRST,

        /**
         * Nothing is in flight (parallel strategy): re-price the whole
         * triangle from scratch on the current books before submitting.
         */
        BEFORE_SUBMIT,

        /** Leg AB filled; legs BC and CA remain. */
        AFTER_AB,

        /** Legs AB and BC filled; leg CA remains. */
        AFTER_BC
    }

    data class Verdict(
        val proceed: Boolean,
        val projectedPercent: Double,
        val elapsedMs: Long,
        val deadlineExceeded: Boolean,
        val reason: String? = null
    ) {
        companion object {
            fun proceed(elapsedMs: Long = 0L, projectedPercent: Double = 0.0) =
                Verdict(true, projectedPercent, elapsedMs, false)
        }
    }

    /**
     * @param spentA what the base asset has actually cost so far
     * @param earnedB balance of the middle asset held after leg AB
     * @param earnedC balance of the third asset held after leg BC
     */
    fun verdict(
        trade: Trade,
        depth: CalculationNode.TradeDepthSnapshot,
        spentA: Double,
        earnedB: Double,
        earnedC: Double,
        startTime: Long,
        stage: Stage
    ): Verdict {
        val now = clock()
        val elapsed = now - startTime

        val deadline = ExecutionConfig.executionDeadlineMs
        if (deadline > 0 && elapsed > deadline) {
            return Verdict(
                proceed = false,
                projectedPercent = 0.0,
                elapsedMs = elapsed,
                deadlineExceeded = true,
                reason = "execution deadline exceeded: ${elapsed}ms > ${deadline}ms"
            )
        }

        if (!ExecutionConfig.preFlightCheckEnabled) return Verdict.proceed(elapsed)

        // A book that cannot cover the remaining legs throws ShallowDepthException
        // out of the projection. That is itself a reason not to send the next
        // order, so it becomes an abort rather than an exception escaping into
        // the execution loop.
        val projectedOutcome = runCatching {
            when (stage) {
            // Nothing has been spent yet, so there is nothing to re-price.
                Stage.BEFORE_FIRST -> return Verdict.proceed(elapsed)

                Stage.BEFORE_SUBMIT -> {
                    // Full recompute: same investment, current books.
                    val fresh = CalculationNode.calculate(spentA, trade, depth)
                    fresh.a.earned
                }

                Stage.AFTER_AB -> {
                    val bc = CalculationNode.projectLeg(trade.bc, earnedB, depth.bc)
                    val ca = CalculationNode.projectLeg(trade.ca, bc.earned, depth.ca)
                    ca.earned
                }

                Stage.AFTER_BC -> {
                    val ca = CalculationNode.projectLeg(trade.ca, earnedC, depth.ca)
                    ca.earned
                }
            }
        }
        val projected = projectedOutcome.getOrElse { e ->
            return Verdict(
                proceed = false,
                projectedPercent = -100.0,
                elapsedMs = elapsed,
                deadlineExceeded = false,
                reason = "pre-flight re-pricing failed: " + (e.message ?: e::class.java.simpleName)
            )
        }

        val percent = CalculationNode.projectedPercent(projected, spentA)
        val bar = ExecutionConfig.profitThreshold + ExecutionConfig.preFlightMarginPercent
        if (percent < bar) {
            return Verdict(
                proceed = false,
                projectedPercent = percent,
                elapsedMs = elapsed,
                deadlineExceeded = false,
                reason = "pre-flight re-pricing: projected " +
                    "${"%.4f".format(percent)}% below gate " +
                    "${"%.4f".format(bar)}% (estimated ${"%.4f".format(percent - ExecutionConfig.profitThreshold)}% margin)"
            )
        }

        return Verdict(proceed = true, projectedPercent = percent, elapsedMs = elapsed, deadlineExceeded = false)
    }

    /** Convenience: reads fresh books for all three legs of [trade]. */
    fun freshDepth(executor: TradeExecutor, trade: Trade): CalculationNode.TradeDepthSnapshot =
        CalculationNode.TradeDepthSnapshot(
            executor.getSortedDepth(trade.ab.ticker),
            executor.getSortedDepth(trade.bc.ticker),
            executor.getSortedDepth(trade.ca.ticker)
        )
}
