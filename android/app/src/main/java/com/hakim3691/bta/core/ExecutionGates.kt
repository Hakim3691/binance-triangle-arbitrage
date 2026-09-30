package com.hakim3691.bta.core

/**
 * The fire-time predicate for a staged (armed) opportunity.
 *
 * An opportunity detected on one book print is executed over three sequential
 * orders, so by the time the round trip finishes it is trading books that have
 * already moved. The original engine fired on the first print that looked good
 * and never looked again. This object is the rule set that decides, at every
 * ping, whether the triangle should be sent, held, or dropped.
 *
 * Two rules are absolute and are not configurable:
 *
 *  1. **No loss inside an opportunity.** Nothing is ever sent whose projected
 *     percent is below [NO_LOSS_FLOOR], whatever the other gates say. A gate
 *     can only ever *withhold* a trade, never authorize a losing one.
 *  2. **Fire late only when the spread is also tight.** The microstructure
 *     gates (imbalance, cadence) are allowed to make the engine patient only
 *     while the books are cheap to cross. On a wide spread, holding the
 *     opportunity costs more than the patience is worth, so the triangle
 *     either fires now under the normal rules or is abandoned - it is never
 *     delayed on the strength of an imbalance alone.
 *
 * Everything else is read from [ExecutionConfig] so the operator can take any
 * threshold over manually, and the ordering below is fixed so the same inputs
 * always produce the same verdict.
 */
object ExecutionGates {

    /**
     * Hard floor on the projected percent. A triangle is only ever sent when it
     * is expected to at least break even, so no combination of settings or gate
     * outcomes can produce a knowingly-losing round trip.
     */
    const val NO_LOSS_FLOOR = 0.0

    enum class Decision { FIRE, WAIT, ABANDON }

    data class Input(
        val features: TriangleFeatures,
        /** Percent the full round trip would realize on the books as they are now. */
        val projectedPercent: Double,
        val armedAt: Long,
        val now: Long,
        /** Minimum percent required to fire: profit gate plus the arming margin. */
        val fireBar: Double,
        /** Below this the opportunity is considered gone. */
        val abandonBar: Double
    )

    data class Verdict(
        val decision: Decision,
        /** Human-readable, surfaced verbatim in the UI so waiting is explainable. */
        val reason: String,
        val spreadTight: Boolean = false,
        val spreadBps: Double = Double.NaN,
        val imbalance: Double = 0.0,
        val interArrivalMs: Double = 0.0,
        val projectedPercent: Double = 0.0,
        val waitedMs: Long = 0L
    ) {
        val fired: Boolean get() = decision == Decision.FIRE
    }

    fun evaluate(input: Input, ttlMs: Int = ExecutionConfig.armTtlMs): Verdict {
        val waited = (input.now - input.armedAt).coerceAtLeast(0L)
        val spread = input.features.maxSpreadBps
        val imbalance = input.features.maxAbsImbalance
        val cadence = input.features.maxInterArrivalMs

        // 1. The window this opportunity was recognised in has closed. Beyond
        //    it, the books are no longer the ones the arming decision was made
        //    about, and waiting longer cannot make the trade more recognisable.
        if (ttlMs > 0 && waited > ttlMs) {
            return Verdict(
                decision = Decision.ABANDON,
                reason = "arm expired after " + waited + "ms (ttl " + ttlMs + "ms)",
                spreadBps = spread,
                imbalance = imbalance,
                interArrivalMs = cadence,
                projectedPercent = input.projectedPercent,
                waitedMs = waited
            )
        }

        // 2. The edge is gone, not merely reduced. A book can always improve;
        //    one that has fallen this far below the gate while we waited is not
        //    going to be re-identified as the same opportunity.
        if (input.projectedPercent < input.abandonBar) {
            return Verdict(
                decision = Decision.ABANDON,
                reason = "edge collapsed to " + fmt(input.projectedPercent) + "% (abandon bar " +
                    fmt(input.abandonBar) + "%) after " + waited + "ms",
                spreadBps = spread,
                imbalance = imbalance,
                interArrivalMs = cadence,
                projectedPercent = input.projectedPercent,
                waitedMs = waited
            )
        }

        // 3. A stale leg cannot be priced, but it recovers on its own, so this
        //    is patience rather than abandonment.
        val maxAge = ExecutionConfig.ageThresholdMs.toLong()
        if (input.features.stalestAgeMs > maxAge) {
            return Verdict(
                decision = Decision.WAIT,
                reason = "waiting on a stale book (" + input.features.stalestAgeMs + "ms > " +
                    maxAge + "ms)",
                spreadBps = spread,
                imbalance = imbalance,
                interArrivalMs = cadence,
                projectedPercent = input.projectedPercent,
                waitedMs = waited
            )
        }

        // 4. Not good enough yet. This is the case the whole feature exists
        //    for: the opportunity is real but this is not the moment for it.
        val fireBar = maxOf(input.fireBar, NO_LOSS_FLOOR)
        if (input.projectedPercent < fireBar) {
            return Verdict(
                decision = Decision.WAIT,
                reason = "waiting for " + fmt(fireBar) + "%, projecting " +
                    fmt(input.projectedPercent) + "%",
                spreadBps = spread,
                imbalance = imbalance,
                interArrivalMs = cadence,
                projectedPercent = input.projectedPercent,
                waitedMs = waited
            )
        }

        // From here on the trade is allowed to go.
        val spreadTight = input.features.spreadKnown() && spread <= ExecutionConfig.spreadTightMaxBps

        if (!ExecutionConfig.microstructureGatesEnabled) {
            return fire(
                "fired: " + fmt(input.projectedPercent) + "% clears " + fmt(fireBar) +
                    "% (microstructure gates off)",
                spread, spreadTight, imbalance, cadence, input.projectedPercent, waited
            )
        }

        // Rule 2: patience is only worth it while crossing is cheap.
        if (!spreadTight) {
            val why = if (input.features.spreadKnown()) {
                "spread " + fmt(spread) + "bps > " + fmt(ExecutionConfig.spreadTightMaxBps) + "bps"
            } else {
                "spread unmeasured"
            }
            return fire(
                "fired without waiting: " + fmt(input.projectedPercent) + "% clears " + fmt(fireBar) +
                    "% but " + why + " - a wide spread is not worth holding for",
                spread, false, imbalance, cadence, input.projectedPercent, waited
            )
        }

        if (imbalance > ExecutionConfig.imbalanceMaxAbs) {
            return Verdict(
                decision = Decision.WAIT,
                reason = "spread tight but book is lopsided (imbalance " + fmt(imbalance) +
                    " > " + fmt(ExecutionConfig.imbalanceMaxAbs) + ")",
                spreadTight = true,
                spreadBps = spread,
                imbalance = imbalance,
                interArrivalMs = cadence,
                projectedPercent = input.projectedPercent,
                waitedMs = waited
            )
        }

        if (cadence > ExecutionConfig.cadenceMaxInterArrivalMs) {
            return Verdict(
                decision = Decision.WAIT,
                reason = "spread tight but book is in flux (updates every " +
                    cadence.toLong() + "ms > " + ExecutionConfig.cadenceMaxInterArrivalMs + "ms)",
                spreadTight = true,
                spreadBps = spread,
                imbalance = imbalance,
                interArrivalMs = cadence,
                projectedPercent = input.projectedPercent,
                waitedMs = waited
            )
        }

        return fire(
            "fired: " + fmt(input.projectedPercent) + "% clears " + fmt(fireBar) +
                "% on a settled book (spread " + fmt(spread) + "bps, imbalance " + fmt(imbalance) + ")",
            spread, true, imbalance, cadence, input.projectedPercent, waited
        )
    }

    private fun fire(
        reason: String,
        spread: Double,
        spreadTight: Boolean,
        imbalance: Double,
        cadence: Double,
        percent: Double,
        waited: Long
    ) = Verdict(
        decision = Decision.FIRE,
        reason = reason,
        spreadTight = spreadTight,
        spreadBps = spread,
        imbalance = imbalance,
        interArrivalMs = cadence,
        projectedPercent = percent,
        waitedMs = waited
    )

    private fun fmt(v: Double): String =
        if (v.isFinite()) "%.4f".format(v) else "?"

    private fun TriangleFeatures.spreadKnown(): Boolean {
        val allKnown = ab.spreadKnown && bc.spreadKnown && ca.spreadKnown
        return allKnown
    }
}
