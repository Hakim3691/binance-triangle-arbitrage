package com.hakim3691.bta.core

/**
 * Executable configuration values consumed by the engine, applied from the Settings
 * UI by [com.hakim3691.bta.config.ConfigurationStore]. Mirrors the corresponding
 * `CONFIG.EXECUTION.*` reads in the original source.
 */
object ExecutionConfig {
    /** EXECUTION.FEE - market taker fee (percent). Applied x3 per triangle. */
    var feePercent: Double = 0.10

    /** EXECUTION.THRESHOLD.PROFIT (percent). */
    var profitThreshold: Double = 0.00

    /**
     * EXECUTION.THRESHOLD.AGE (ms).
     *
     * When [ageThresholdAuto] is true (default) this value is continuously
     * self-tuned by [AgeThresholdTuner] from observed book ages - users never
     * need to touch it. Manual mode is available in Settings for experts.
     */
    var ageThresholdMs: Int = 5000

    /** Whether the age threshold self-tunes from measured market data. */
    @Volatile var ageThresholdAuto: Boolean = true

    /**
     * Per-field AUTO switches. When a flag is true, [AutoTuner] owns that value
     * and re-derives it from live market/account data; when false the value the
     * user typed in Settings is used verbatim and never overwritten.
     */
    @Volatile var investmentAuto: Boolean = true
    @Volatile var feeAuto: Boolean = true
    @Volatile var profitThresholdAuto: Boolean = true
    @Volatile var capAuto: Boolean = true
    @Volatile var depthAuto: Boolean = true
    @Volatile var strategyAuto: Boolean = true

    // ------------------------------------------------------------------
    // Execution guards
    //
    // A triangle is priced when it is identified but sent over three
    // sequential orders, so the books can move out from under it mid-flight.
    // See PreFlightGuard.
    // ------------------------------------------------------------------

    /**
     * Wall-clock budget for the whole round trip, in ms. An execution that has
     * already spent this long no longer corresponds to the books it was priced
     * from, so it is abandoned rather than finished. 0 disables the deadline.
     */
    var executionDeadlineMs: Int = 0

    /** Derive [executionDeadlineMs] from the measured REST round-trip latency. */
    @Volatile var executionDeadlineAuto: Boolean = true

    /**
     * Re-price the remaining legs against freshly read books before each order
     * and abandon the trade if the projection no longer clears the profit gate.
     */
    @Volatile var preFlightCheckEnabled: Boolean = true

    /**
     * Extra percent the projection must clear by, on top of
     * [profitThreshold]. Covers the cost of the rest of the round trip.
     */
    var preFlightMarginPercent: Double = 0.0

    // ------------------------------------------------------------------
    // Staged execution: arm -> wait -> fire
    //
    // A triangle is priced on one book print and executed over three
    // sequential orders. Rather than firing on the first print that clears
    // the gate, the opportunity is *armed* and re-evaluated on every update
    // to its own three books until the gates say go, the edge collapses, or
    // the window closes. See ArmedOpportunity / ExecutionGates.
    // ------------------------------------------------------------------

    /**
     * When true, a qualifying triangle is armed and re-evaluated instead of
     * being sent immediately. When false the engine behaves exactly as it did
     * before staging existed.
     */
    @Volatile var stagedExecutionEnabled: Boolean = true

    /**
     * How long an armed opportunity stays eligible, counted from the moment it
     * was recognised. Beyond this the books are no longer the ones the arming
     * decision was made about, so it is dropped rather than fired late.
     */
    var armTtlMs: Int = 2000

    @Volatile var armTtlAuto: Boolean = true

    /**
     * Extra percent an armed opportunity must clear *above* the profit gate
     * before it is sent. This is the reason to wait at all: firing at the
     * detection print spends the opportunity for nothing.
     */
    var armingMarginPercent: Double = 0.10

    @Volatile var armingMarginAuto: Boolean = true

    /**
     * How far below the profit gate the projection may fall before the
     * opportunity is abandoned rather than waited on. Small values abandon
     * early and cheaply; large ones keep holding a triangle that is already
     * unprofitable.
     */
    var abandonMarginPercent: Double = 0.05

    @Volatile var abandonMarginAuto: Boolean = true

    /**
     * Microstructure gating heuristics. These are descriptions of the book as
     * it is now, never forecasts: they can make the engine patient, and they
     * can never authorise a trade the economics would not allow.
     *
     * Per the operator's rule, the imbalance and cadence gates may only delay a
     * fire while the spread is also tight - see [ExecutionGates].
     */
    @Volatile var microstructureGatesEnabled: Boolean = true

    /** Widest per-leg spread, in bps, still considered cheap to cross. */
    var spreadTightMaxBps: Double = 8.0

    @Volatile var spreadAuto: Boolean = true

    /** Largest tolerated top-of-book imbalance, 0..1. */
    var imbalanceMaxAbs: Double = 0.35

    @Volatile var imbalanceAuto: Boolean = true

    /** A book updating faster than this is treated as being in flux. */
    var cadenceMaxInterArrivalMs: Int = 750

    @Volatile var cadenceAuto: Boolean = true

    /** How many triangles may be armed at once. */
    var maxArmedOpportunities: Int = 8

    @Volatile var maxArmedAuto: Boolean = true

    // ------------------------------------------------------------------
    // Paper execution realism
    //
    // With zero latency and zero slippage the simulator fills every order at
    // the exact price the engine projected, so guards, deadlines and unwinds
    // never fire and no staging behaviour can be measured at all. These are
    // derived from the measured round trip and the observed books.
    // ------------------------------------------------------------------

    /** Simulated per-leg order latency in the paper engine. */
    var paperLatencyMs: Int = 0

    @Volatile var paperLatencyAuto: Boolean = true

    /** Simulated adverse fill slippage, percent, applied to every paper leg. */
    var paperSlippagePercent: Double = 0.0

    @Volatile var paperSlippageAuto: Boolean = true

    /**
     * Whether to keep only one traversal order per triangle.
     *
     * Default false, and for good reason: `A-B-C` and `A-C-B` are NOT the
     * same round trip. They cross opposite sides of the same three books, so
     * if the first direction returns m over mid prices with total spread s,
     * the second returns roughly -m - 2s - at most one of them can be
     * profitable, and which one changes with the dislocation. Dropping the
     * mirror drops real opportunities. Enabling this trades half the
     * opportunity set for half the CPU; it exists so the original app's
     * behaviour can be reproduced, not as an optimization.
     */
    @Volatile var dedupeMirroredTriangles: Boolean = false

    /** Compact "fee/profit/cap/depth/strategy AUTO" summary for the UI. */
    /**
     * Stable fingerprint of every value the auto-tuner is responsible for.
     *
     * The auto-tuner logs only when this changes, so it has to describe the
     * DERIVED VALUES and nothing that moves on its own. It used to be built
     * inline from a mixed-type list formatted with "%.3f", which threw
     * IllegalFormatConversionException ("f != java.lang.Integer") the moment
     * an Int reached the formatter - taking down the scan thread with it.
     * Every numeric field is widened to Double here so the types cannot drift
     * apart from the format specifier again.
     */
    fun derivedSignature(): String = listOf(
        feePercent,
        profitThreshold,
        cap.toDouble(),
        scanningDepth.toDouble(),
        executionDeadlineMs.toDouble(),
        armTtlMs.toDouble(),
        armingMarginPercent,
        abandonMarginPercent,
        spreadTightMaxBps,
        imbalanceMaxAbs,
        cadenceMaxInterArrivalMs.toDouble(),
        maxArmedOpportunities.toDouble(),
        paperLatencyMs.toDouble(),
        paperSlippagePercent
    ).joinToString(",") { "%.3f".format(it) } +
        "|" + strategy +
        "|" + autoModeSummary()

    fun autoModeSummary(): String = listOf(
        "investment" to investmentAuto,
        "fee" to feeAuto,
        "profit" to profitThresholdAuto,
        "cap" to capAuto,
        "depth" to depthAuto,
        "strategy" to strategyAuto
    ).joinToString(" ") { (name, on) -> name + if (on) ":AUTO" else ":MANUAL" } +
        "  preflight:" + if (preFlightCheckEnabled) "ON" else "OFF" +
        "  deadline:" + (if (executionDeadlineMs == 0) "off" else executionDeadlineMs.toString() + "ms") +
        "  staged:" + if (stagedExecutionEnabled) "ON" else "OFF" +
        "  arm(" + (if (armTtlAuto) "A" else "M") + (if (armingMarginAuto) "A" else "M") +
        (if (abandonMarginAuto) "A" else "M") + (if (spreadAuto) "A" else "M") +
        (if (imbalanceAuto) "A" else "M") + (if (cadenceAuto) "A" else "M") +
        (if (maxArmedAuto) "A" else "M") + ")"

    /** EXECUTION.STRATEGY: "linear" or "parallel". */
    var strategy: String = "linear"

    /** EXECUTION.CAP: max executions before the scanner pauses (0 = unlimited). */
    var cap: Int = 1

    /** SCANNING.DEPTH */
    var scanningDepth: Int = 50

    /** Port of Validation.js checks for the values consumed by the engine. */
    fun validate(investmentSpecs: Map<String, InvestmentSpec> = InvestmentSpec.DEFAULTS): List<String> {
        val errors = mutableListOf<String>()
        for ((base, spec) in investmentSpecs) {
            if (spec.min <= 0) errors.add("Minimum investment quantity (INVESTMENT.$base.MIN) must be a positive number")
            if (spec.max <= 0) errors.add("Maximum investment quantity (INVESTMENT.$base.MAX) must be a positive number")
            if (spec.step <= 0) errors.add("Investment step size (INVESTMENT.$base.STEP) must be a positive number")
            if (spec.min > spec.max)
                errors.add("Minimum investment quantity (INVESTMENT.$base.MIN) cannot be greater than maximum investment quantity (INVESTMENT.$base.MAX)")
            if (spec.min != spec.max && (spec.min + spec.step) > spec.max)
                errors.add("Step size (INVESTMENT.$base.STEP) is too large for calculation optimization")
        }
        if (scanningDepth <= 0 || scanningDepth > 5000)
            errors.add("Depth size (SCANNING.DEPTH) must be a positive integer <= 5000")
        if (feePercent < 0)
            errors.add("Execution fee (EXECUTION.FEE) must be a positive number")
        if (strategy !in listOf("linear", "parallel"))
            errors.add("Execution strategy (EXECUTION.STRATEGY) must be one of the following values: linear, parallel]")
        if (ageThresholdMs <= 0)
            errors.add("Age threshold (EXECUTION.THRESHOLD.AGE) must be a positive number")
        if (executionDeadlineMs < 0)
            errors.add("Execution deadline must be zero (disabled) or a positive number of milliseconds")
        if (preFlightMarginPercent < 0)
            errors.add("Pre-flight margin must not be negative")
        if (cap < 0)
            errors.add("Execution cap (EXECUTION.CAP) must be a positive integer")
        if (armTtlMs < 0)
            errors.add("Arm window (STAGED.TTL) must be zero (disabled) or a positive number of milliseconds")
        if (armingMarginPercent < 0)
            errors.add("Arming margin must not be negative")
        if (abandonMarginPercent < 0)
            errors.add("Abandon margin must not be negative")
        if (armingMarginPercent < abandonMarginPercent)
            errors.add("Abandon margin must be smaller than the arming margin, otherwise every armed opportunity is abandoned on the first check")
        if (spreadTightMaxBps < 0)
            errors.add("Tight spread ceiling must not be negative")
        if (imbalanceMaxAbs < 0 || imbalanceMaxAbs > 1)
            errors.add("Imbalance ceiling must be between 0 and 1")
        if (cadenceMaxInterArrivalMs < 0)
            errors.add("Book cadence ceiling must not be negative")
        if (maxArmedOpportunities < 1)
            errors.add("At least one opportunity must be armable at a time")
        if (paperLatencyMs < 0)
            errors.add("Paper latency must not be negative")
        if (paperSlippagePercent < 0)
            errors.add("Paper slippage must not be negative")
        return errors
    }
}
