package com.hakim3691.bta.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Derives every Settings value from live market + account data instead of
 * hard-coded guesses, so the operator does not have to reason about Binance
 * lot sizes, fee schedules or book depth by hand.
 *
 * Every function here is pure and deterministic: the same [Inputs] always
 * produce the same [Result]. The caller ([com.hakim3691.bta.scanner.ScannerController])
 * decides *which* fields are currently in AUTO mode and only then applies them
 * to [ExecutionConfig] / [InvestmentSpec]; a field the user has taken over
 * manually is never overwritten.
 */
object AutoTuner {

    /** Depth values Binance accepts for both /api/v3/depth and the @depth stream suffix. */
    val VALID_DEPTHS = listOf(5, 10, 20, 50, 100, 500, 1000, 5000)

    /** Floor on the auto-derived scan depth, whatever the notional math asks for. */
    const val MIN_SCAN_DEPTH = 10
    /** Ceiling: the depth the websocket subscribes with, and so all the cache can hold. */
    const val MAX_SCAN_DEPTH = 50
    /** Pre-funded ratio at which the executor starts sending legs in parallel. */
    const val STRATEGY_ENTER_PRE_FUNDED = 0.999
    /** ...and the lower ratio it must fall to before going back to linear. */
    const val STRATEGY_EXIT_PRE_FUNDED = 0.95

    /** Default when a symbol exposes no LOT_SIZE filter. */
    const val FALLBACK_STEP = 0.001

    /** Upper bound on derived CAP so a large budget cannot pause the scanner for hours. */
    const val MAX_AUTO_CAP = 10

    /** Number of sequential orders a round trip has to survive. */
    const val EXECUTION_LEGS = 3

    /**
     * Headroom multiplier on the measured round-trip latency, allowing for each
     * order costing several times the bare REST latency plus queueing.
     */
    const val DEADLINE_HEADROOM = 3

    /**
     * How many execution deadlines an armed opportunity is allowed to live
     * for. One deadline is what a round trip costs, so waiting several of them
     * gives the books room to offer a better version of the same triangle
     * without stranding the opportunity indefinitely.
     */
    const val ARM_TTL_DEADLINES = 4

    /**
     * Extra edge, expressed in fees, that an armed opportunity must add before
     * it is worth sending. Holding an opportunity is only rational if the
     * moment it is eventually fired at is measurably better than the moment it
     * was found.
     */
    const val ARM_MARGIN_FEES = 1.0

    /**
     * How much of a fee's worth of edge may be given back before the triangle
     * is dropped rather than waited on.
     */
    const val ABANDON_MARGIN_FEES = 0.5

    /** Headroom over the observed worst-leg spread still treated as tight. */
    const val SPREAD_HEADROOM = 1.25

    /** Fraction of the observed 90th-percentile imbalance still tolerated. */
    const val IMBALANCE_HEADROOM = 0.6

    /** Slots in the arming book. */
    const val ARM_SLOTS = 8

    /** Bounds for the derived gate thresholds, so no book can talk us into a
     *  threshold that is either meaningless or effectively disabled. */
    const val MIN_SPREAD_TIGHT_BPS = 1.0
    const val MAX_SPREAD_TIGHT_BPS = 50.0
    const val MIN_IMBALANCE_LIMIT = 0.15
    const val MAX_IMBALANCE_LIMIT = 0.60
    const val MIN_CADENCE_LIMIT_MS = 200
    const val MAX_CADENCE_LIMIT_MS = 3000
    const val MAX_PAPER_LATENCY_MS = 2000
    /** Spread assumed before any book has been observed (10bps, a typical taker spread). */
    const val DEFAULT_PAPER_SLIPPAGE_BPS = 10.0
    /** Book-update intervals of in-flight exposure charged on top of the half-spread. */
    const val MAX_SLIPPAGE_LATENCY_INTERVALS = 3.0
    const val MAX_PAPER_SLIPPAGE_PERCENT = 1.0

    /** Fallbacks used before any book has been observed. */
    const val DEFAULT_SPREAD_TIGHT_BPS = 8.0
    const val DEFAULT_IMBALANCE_LIMIT = 0.35
    const val DEFAULT_CADENCE_LIMIT_MS = 750

    data class Inputs(
        val base: String = "BTC",
        val quote: String = "USDT",
        /** USDT available to the Kelly book. */
        val budgetUsdt: Double = 0.0,
        /** Last price of base/quote, or 0 when the book has not been seen yet. */
        val basePriceUsdt: Double = 0.0,
        /** LOT_SIZE.stepSize of the base/quote symbol. */
        val lotStep: Double = 0.0,
        /** LOT_SIZE.minQty of the base/quote symbol. */
        val lotMinQty: Double = 0.0,
        /** Kelly f*, already clamped to [KellyConfig.maxKellyFraction]. */
        val kellyFraction: Double = 0.0,
        /** KellyConfig.investmentFractionOfKelly (0.10 == invest 10% of Kelly). */
        val investmentFractionOfKelly: Double = 0.0,
        /** KellyConfig.maxAllocationPerTrade. */
        val maxAllocationPerTrade: Double = 1.0,
        /** KellyConfig.minTradeUsdt. */
        val minTradeUsdt: Double = 0.0,
        /**
         * USDT resting on a single observed book level, measured across the
         * thinnest books in the universe rather than on the base pair alone.
         * 0 when unmeasured.
         */
        val levelNotional: Double = 0.0,
        /**
         * Largest notional a single leg is ever asked to convert, taken across
         * every configured investment spec. Deriving depth from the base pair
         * alone under-sizes it for any base with a bigger spec, and every leg
         * over the derived depth then throws ShallowDepthException on every
         * cycle.
         */
        val largestConfiguredNotionalUsdt: Double = 0.0,
        /** Strategy in force now, so the derivation can apply hysteresis. */
        val currentStrategy: String = "linear",
        /** Fraction of the triangle's three legs already held in the portfolio (0..1). */
        val preFundedRatio: Double = 0.0,
        /** exchangeInfo standardCommission.taker, a fraction of notional (0.001 == 0.10%). */
        val takerCommissionRate: Double? = null,
        /** Measured REST round-trip latency, ms. */
        val latencyMs: Double = 0.0,
        /** Median worst-leg spread across recently observed triangles, bps. 0 = unmeasured. */
        val observedWorstLegSpreadBps: Double = 0.0,
        /** 90th percentile of the largest per-leg book imbalance. 0 = unmeasured. */
        val observedP90AbsImbalance: Double = 0.0,
        /** Median inter-update gap across tickers, ms. 0 = unmeasured. */
        val observedMedianInterArrivalMs: Double = 0.0,
        /** Median lifetime of identified opportunities before the edge left, ms. 0 = unmeasured. */
        val observedMedianOpportunityMs: Double = 0.0,
        /** Used when Binance publishes no commission data (VIP0 spot taker). */
        val feePercentFallback: Double = 0.10
    )

    data class Result(
        val base: String,
        val min: Double,
        val max: Double,
        val step: Double,
        val feePercent: Double,
        val profitThreshold: Double,
        val cap: Int,
        val depth: Int,
        val strategy: String,
        /** Wall-clock budget for one round trip, in ms. */
        val deadlineMs: Int,
        /** USDT the Kelly book would put into one triangle. */
        val perTradeUsdt: Double,
        // --- staged execution (arm -> wait -> fire) ---
        /** How long an armed opportunity stays eligible, in ms. */
        val armTtlMs: Int,
        /** Extra percent over the profit gate an armed opportunity must clear. */
        val armingMarginPercent: Double,
        /** How far below the profit gate the edge may fall before abandoning. */
        val abandonMarginPercent: Double,
        /** Widest per-leg spread still considered cheap to cross, bps. */
        val spreadTightMaxBps: Double,
        /** Largest tolerated top-of-book imbalance, 0..1. */
        val imbalanceMaxAbs: Double,
        /** A book updating faster than this is treated as being in flux, ms. */
        val cadenceMaxInterArrivalMs: Int,
        /** How many triangles may be armed at once. */
        val maxArmedOpportunities: Int,
        /** Simulated per-leg order latency for the paper engine, ms. */
        val paperLatencyMs: Int,
        /** Simulated adverse fill slippage for the paper engine, percent. */
        val paperSlippagePercent: Double,
        val notes: List<String>
    ) {
        fun summary(): String = notes.joinToString("  |  ")

        fun investmentSpec(): InvestmentSpec = InvestmentSpec(base, min, max, step)
    }

    // ------------------------------------------------------------------
    // FEE %
    // ------------------------------------------------------------------

    /**
     * Binance publishes the effective spot taker commission as a fraction of
     * notional inside `standardCommission` (0.001 == 0.10%). When it is absent
     * we keep the VIP0 default rather than pretending to know better.
     */
    fun deriveFeePercent(takerCommissionRate: Double?, fallback: Double = 0.10): Double {
        val rate = takerCommissionRate ?: return fallback
        if (!rate.isFinite() || rate <= 0.0 || rate >= 1.0) return fallback
        return rate * 100.0
    }

    // ------------------------------------------------------------------
    // THRESHOLD.PROFIT %
    // ------------------------------------------------------------------

    /**
     * A triangle pays taker fees on all three legs, so `percent` in
     * [CalculationNode] has already had 3 x fee subtracted by the time the
     * gate sees it. A profit gate of 0 therefore admits trades that lose money
     * to slippage alone; 3 x fee is the true break-even edge.
     */
    fun deriveProfitThreshold(feePercent: Double, floor: Double = 0.0): Double =
        max(floor, feePercent * 3.0)

    // ------------------------------------------------------------------
    // INVESTMENT MIN / MAX / STEP
    // ------------------------------------------------------------------

    /**
     * Number of decimal places implied by a step, used to strip float noise.
     *
     * Handles exponent notation: Kotlin renders 0.00001 as "1.0E-5", so a naive
     * count of the digits after the "." would report 1 instead of 5 and every
     * rounding step would collapse the value to zero.
     */
    fun decimalsFor(step: Double): Int {
        if (!step.isFinite() || step <= 0.0) return 8
        val s = step.toString()
        val eIndex = s.indexOfFirst { it == 'E' || it == 'e' }
        val mantissa = if (eIndex >= 0) s.substring(0, eIndex) else s
        val exponent = if (eIndex >= 0) (s.substring(eIndex + 1).toIntOrNull() ?: 0) else 0
        val dot = mantissa.indexOf('.')
        if (dot < 0) return (-exponent).coerceIn(0, 12)
        // Drop trailing zeros: Kotlin renders 1.0 as "1.0", which is one decimal
        // place of pure noise.
        var decimals = mantissa.length - dot - 1
        while (decimals > 0 && mantissa[dot + decimals] == '0') decimals--
        return (decimals - exponent).coerceIn(0, 12)
    }

    fun roundToStep(value: Double, step: Double): Double {
        if (!step.isFinite() || step <= 0.0 || !value.isFinite()) return value
        val scale = Math.pow(10.0, decimalsFor(step).toDouble())
        return Math.round(value * scale) / scale
    }

    /** Largest multiple of [step] that is <= [qty]. */
    fun floorToStep(qty: Double, step: Double): Double {
        if (!step.isFinite() || step <= 0.0) return qty
        val n = floor(qty / step + 1e-9)
        return roundToStep(n * step, step)
    }

    /** Smallest multiple of [step] that is >= [qty]. */
    fun ceilToStep(qty: Double, step: Double): Double {
        if (!step.isFinite() || step <= 0.0) return qty
        val n = ceil(qty / step - 1e-9)
        return roundToStep(n * step, step)
    }

    /**
     * Turns a USDT target into a Binance-legal quantity range.
     *
     * STEP is always the symbol's LOT_SIZE stepSize, because any other value
     * makes the optimizer produce orders the exchange rejects with
     * LOT_SIZE_FILTER_FAILED. MIN is the target size floored onto that grid and
     * raised to LOT_SIZE.minQty; MAX adds exactly one more step so the
     * optimizer has two candidates to choose between (the same shape as the
     * shipped defaults 0.010 / 0.015 / 0.005) while still satisfying
     * `min + step <= max` from [ExecutionConfig.validate].
     */
    fun deriveInvestment(
        base: String,
        targetNotionalUsdt: Double,
        basePriceUsdt: Double,
        lotStep: Double,
        lotMinQty: Double
    ): Triple<Double, Double, Double> {
        val step = if (lotStep > 0.0) lotStep else FALLBACK_STEP
        val minQty = if (lotMinQty > 0.0) lotMinQty else step

        val targetQty = if (basePriceUsdt > 0.0 && targetNotionalUsdt > 0.0)
            targetNotionalUsdt / basePriceUsdt
        else
            minQty

        var min = floorToStep(targetQty, step)
        if (min < minQty - 1e-12 || min <= 0.0) min = ceilToStep(minQty, step)
        val max = roundToStep(min + step, step)
        return Triple(min, max, step)
    }

    // ------------------------------------------------------------------
    // SCANNING.DEPTH
    // ------------------------------------------------------------------

    /**
     * Smallest Binance-valid depth whose whole book still holds at least
     * [requiredNotional] of resting size, measured from the observed
     * [levelNotional]. Bigger than needed just wastes cycles; smaller than
     * needed makes every candidate look un-fillable and the opportunity is
     * discarded before the fee math ever runs.
     */
    fun deriveDepth(requiredNotional: Double, levelNotional: Double): Int {
        if (levelNotional <= 0.0 || !levelNotional.isFinite() || requiredNotional <= 0.0) {
            return 50
        }
        for (d in VALID_DEPTHS) {
            // Five levels is enough notional on a deep book, but walking only
            // five levels of a thin one prices the triangle off its very top
            // and turns a momentary imbalance into a double-digit percent.
            if (d < MIN_SCAN_DEPTH) continue
            // The ceiling has to be enforced HERE, not only on the fallback:
            // returning d from inside the loop is the path almost every real
            // universe takes, and it used to hand back 500 levels from a
            // socket that only ever holds 50.
            if (d >= MAX_SCAN_DEPTH) break
            if (d * levelNotional >= requiredNotional) return d
        }
        // Capped at what the depth stream actually subscribed with: asking the
        // cache for more levels than the socket was opened with cannot be
        // satisfied, and would just cost memory on every one of the books.
        return minOf(VALID_DEPTHS.last(), MAX_SCAN_DEPTH)
    }

    /**
     * Median resting notional of a single book level, from the top
     * [window] levels of one side of an order book. 0 when the book is empty.
     */
    fun measureLevelNotional(levels: Map<Double, Double>, window: Int = 20): Double {
        if (levels.isEmpty() || window <= 0) return 0.0
        val values = levels.entries.take(window).map { it.key * it.value }.sorted()
        if (values.isEmpty()) return 0.0
        val mid = values.size / 2
        return if (values.size % 2 == 1) values[mid]
        else (values[mid - 1] + values[mid]) / 2.0
    }

    // ------------------------------------------------------------------
    // CAP
    // ------------------------------------------------------------------

    /**
     * How many Kelly-sized trades the budget can fund back to back. CAP is the
     * scanner's "stop and re-check the books" lever, so it tracks funding
     * capacity rather than being a magic number, and is clamped to
     * [MAX_AUTO_CAP].
     */
    fun deriveCap(budgetUsdt: Double, perTradeUsdt: Double, hardCap: Int = MAX_AUTO_CAP): Int {
        if (budgetUsdt <= 0.0 || perTradeUsdt <= 0.0 || !perTradeUsdt.isFinite()) return 1
        return floor(budgetUsdt / perTradeUsdt).toInt().coerceIn(1, max(1, hardCap))
    }

    // ------------------------------------------------------------------
    // EXECUTION DEADLINE
    // ------------------------------------------------------------------

    /**
     * Wall-clock budget for one round trip, derived from the measured REST
     * round-trip latency: three sequential orders at [headroom] times that
     * latency each. If the trip cannot plausibly finish inside that window,
     * the books it was priced from are no longer the books it is trading
     * against.
     */
    fun deriveExecutionDeadline(latencyMs: Double, headroom: Int = DEADLINE_HEADROOM): Int {
        if (!latencyMs.isFinite() || latencyMs <= 0.0) return 250
        val raw = latencyMs * EXECUTION_LEGS * headroom
        return raw.toInt().coerceIn(250, 5000)
    }

    // ------------------------------------------------------------------
    // STRATEGY
    // ------------------------------------------------------------------

    /**
     * `parallel` submits all three legs at once, which needs every leg's asset
     * already held. `linear` walks the legs in sequence and can start from a
     * single USDT balance, so it is the safe default until the portfolio
     * actually holds the triangle's assets.
     */
    /**
     * Both strategies stay reachable, but the decision carries hysteresis.
     *
     * The pre-funded ratio is a continuous quantity that sits right on the
     * boundary in normal operation, so a bare threshold flip-flopped between
     * strategies several times a minute - and because each flip counts as a
     * derived-value change, it re-logged the whole settings line every time.
     * Leaving the current strategy until the ratio is clearly the other way
     * keeps both options without the engine changing its mind on float noise.
     */
    fun deriveStrategy(preFundedRatio: Double, current: String = "linear"): String {
        // A reading we cannot use is not evidence. Keep whatever is live
        // rather than letting one bad sample change the execution strategy.
        if (!preFundedRatio.isFinite()) return current
        val ratio = preFundedRatio
        return if (current == "parallel") {
            if (ratio >= STRATEGY_EXIT_PRE_FUNDED) "parallel" else "linear"
        } else {
            if (ratio >= STRATEGY_ENTER_PRE_FUNDED) "parallel" else "linear"
        }
    }

    // ------------------------------------------------------------------
    // STAGED EXECUTION
    //
    // These derive the arming window and the microstructure thresholds from
    // the same measured data the engine already prices with. They are gating
    // heuristics: they decide when it is worth waiting, never whether a
    // round trip can lose money - that floor lives in ExecutionGates and is
    // not configurable.
    // ------------------------------------------------------------------

    /**
     * How long an armed opportunity stays eligible.
     *
     * Two honest signals, in order: how long a dislocation has actually
     * survived on the books so far (the arm window is a claim about the
     * market, so it is derived from the market), and - only when nothing has
     * been observed yet - a few execution deadlines, since one deadline is
     * what sending the triangle costs.
     */
    fun deriveArmTtl(
        deadlineMs: Int,
        latencyMs: Double = 0.0,
        observedMedianOpportunityMs: Double = 0.0
    ): Int {
        if (observedMedianOpportunityMs.isFinite() && observedMedianOpportunityMs > 0.0) {
            return (observedMedianOpportunityMs * 2.0).toInt().coerceIn(500, 20_000)
        }
        val base = if (deadlineMs > 0) deadlineMs.toDouble()
        else if (latencyMs > 0 && latencyMs.isFinite()) latencyMs * EXECUTION_LEGS * DEADLINE_HEADROOM
        else return 1000
        return (base * ARM_TTL_DEADLINES).toInt().coerceIn(500, 20_000)
    }

    /**
     * Extra edge required before an armed opportunity is fired, in percent.
     * Measured in fees because a fee is the natural unit of "is there still
     * anything left after crossing three books".
     */
    fun deriveArmingMargin(feePercent: Double): Double {
        if (!feePercent.isFinite() || feePercent <= 0.0) return 0.0
        return feePercent * ARM_MARGIN_FEES
    }

    /**
     * How far below the profit gate the projection may fall before waiting is
     * pointless. Always smaller than [deriveArmingMargin], so the band between
     * "wait" and "abandon" is never inverted.
     */
    fun deriveAbandonMargin(feePercent: Double): Double {
        if (!feePercent.isFinite() || feePercent <= 0.0) return 0.0
        return feePercent * ABANDON_MARGIN_FEES
    }

    /**
     * The widest per-leg spread that is still cheap to cross, derived from the
     * spread the books have actually been showing with a little headroom. When
     * nothing has been measured yet a conservative default is used rather than
     * a number that would silently disable the gate.
     */
    fun deriveSpreadTightMaxBps(observedWorstLegSpreadBps: Double): Double {
        if (!observedWorstLegSpreadBps.isFinite() || observedWorstLegSpreadBps <= 0.0) {
            return DEFAULT_SPREAD_TIGHT_BPS
        }
        return (observedWorstLegSpreadBps * SPREAD_HEADROOM)
            .coerceIn(MIN_SPREAD_TIGHT_BPS, MAX_SPREAD_TIGHT_BPS)
    }

    /**
     * Largest imbalance still considered a settled book, derived from the 90th
     * percentile the market has actually produced: tighter than typical, so a
     * genuinely lopsided book is waited out, but not so tight that ordinary
     * two-sided flow is mistaken for instability.
     */
    fun deriveImbalanceMaxAbs(observedP90AbsImbalance: Double): Double {
        if (!observedP90AbsImbalance.isFinite() || observedP90AbsImbalance <= 0.0) {
            return DEFAULT_IMBALANCE_LIMIT
        }
        return (observedP90AbsImbalance * IMBALANCE_HEADROOM)
            .coerceIn(MIN_IMBALANCE_LIMIT, MAX_IMBALANCE_LIMIT)
    }

    /**
     * A book that updates faster than a couple of round trips is reacting to
     * something rather than resting, and a three-order round trip through it
     * is fragile. Slower than that and the book is simply quiet.
     *
     * [latencyMs] is only the fallback for the first seconds of a session,
     * before any book cadence has been observed: the honest derivation is
     * from the ticker's own update distribution, which is a property of the
     * market and not of our connection.
     */
    fun deriveCadenceMaxInterArrivalMs(
        observedMedianInterArrivalMs: Double,
        latencyMs: Double = 0.0
    ): Int {
        if (observedMedianInterArrivalMs.isFinite() && observedMedianInterArrivalMs > 0.0) {
            // A book resting noticeably longer than its own median is quiet
            // for this market; one at the median or below is in flux.
            return (observedMedianInterArrivalMs * 2.0).toInt()
                .coerceIn(MIN_CADENCE_LIMIT_MS, MAX_CADENCE_LIMIT_MS)
        }
        if (latencyMs.isFinite() && latencyMs > 0.0) {
            return (latencyMs * 2.0).toInt().coerceIn(MIN_CADENCE_LIMIT_MS, MAX_CADENCE_LIMIT_MS)
        }
        return DEFAULT_CADENCE_LIMIT_MS
    }

    /** Fixed number of arming slots; see [ARM_SLOTS]. */
    fun deriveMaxArmed(slots: Int = ARM_SLOTS): Int = slots.coerceIn(1, 64)

    // ------------------------------------------------------------------
    // PAPER EXECUTION REALISM
    // ------------------------------------------------------------------

    /**
     * Simulated per-leg latency. Taken from the same measured REST round trip
     * the execution deadline is derived from, so the simulator pays roughly
     * what the real thing costs - without it, deadlines and pre-flight guards
     * can never fire and no staging behaviour can be measured.
     */
    fun derivePaperLatencyMs(latencyMs: Double): Int {
        if (!latencyMs.isFinite() || latencyMs <= 0.0) return 0
        return latencyMs.toInt().coerceIn(0, MAX_PAPER_LATENCY_MS)
    }

    /**
     * Simulated adverse fill slippage.
     *
     * The paper engine already walks the real book for every fill, so the
     * crossing cost of the spread is priced honestly and must not be charged
     * twice. What is left is the move that happens *while the order is in
     * flight*: the decision is taken from one snapshot, the fill happens
     * [latencyMs] later against a book that has been updating the whole time.
     *
     * That is modelled as the half-spread a taker pays on arrival, scaled by
     * how many book-update intervals the order was in flight for. A book that
     * ticks every 300ms and an order that takes 213ms is barely exposed; a
     * book that ticks every 50ms is exposed to four moves, and pretending
     * otherwise is exactly the optimism that makes backtests lie.
     *
     * Having no observation yet is not evidence of a frictionless market, so
     * the fallback is a typical tight spread rather than zero. Returning 0.0
     * here used to give the first fills of a session literally no friction.
     */
    fun derivePaperSlippagePercent(
        observedWorstLegSpreadBps: Double,
        latencyMs: Double = 0.0,
        observedMedianInterArrivalMs: Double = 0.0
    ): Double {
        val spreadBps = if (!observedWorstLegSpreadBps.isFinite() || observedWorstLegSpreadBps <= 0.0) {
            DEFAULT_PAPER_SLIPPAGE_BPS
        } else {
            observedWorstLegSpreadBps
        }
        val intervals = if (latencyMs > 0.0 && observedMedianInterArrivalMs > 0.0 &&
            observedMedianInterArrivalMs.isFinite()
        ) {
            latencyMs / observedMedianInterArrivalMs
        } else {
            0.0
        }
        val exposure = 1.0 + intervals.coerceIn(0.0, MAX_SLIPPAGE_LATENCY_INTERVALS)
        return (spreadBps / 2.0 / 100.0 * exposure).coerceIn(0.0, MAX_PAPER_SLIPPAGE_PERCENT)
    }

    // ------------------------------------------------------------------
    // Composition
    // ------------------------------------------------------------------

    /** Mirrors the sizing in KellyPaperTrader so the derived MIN/MAX cover one real trade. */
    fun derivePerTradeUsdt(
        budgetUsdt: Double,
        kellyFraction: Double,
        maxAllocationPerTrade: Double,
        investmentFractionOfKelly: Double,
        minTradeUsdt: Double
    ): Double {
        val kellyAllocation = budgetUsdt * max(kellyFraction, 0.0).coerceAtMost(maxAllocationPerTrade)
        val investment = max(kellyAllocation * investmentFractionOfKelly, minTradeUsdt)
        return investment.coerceAtMost(max(budgetUsdt, 0.0))
    }

    /** Runs every derivation once and reports what it decided and why. */
    fun tune(i: Inputs): Result {
        val notes = ArrayList<String>(6)

        val fee = deriveFeePercent(i.takerCommissionRate, i.feePercentFallback)
        notes.add(
            if (i.takerCommissionRate != null) "fee ${"%.4f".format(fee)}% from exchangeInfo"
            else "fee ${"%.4f".format(fee)}% default (no commission published)"
        )

        val threshold = deriveProfitThreshold(fee)
        notes.add("profit gate ${"%.4f".format(threshold)}% (3 x fee break-even)")

        val perTrade = derivePerTradeUsdt(
            i.budgetUsdt, i.kellyFraction, i.maxAllocationPerTrade,
            i.investmentFractionOfKelly, i.minTradeUsdt
        )
        notes.add("per-trade ${"%.2f".format(perTrade)} USDT")

        val (min, max, step) = deriveInvestment(
            i.base, perTrade, i.basePriceUsdt, i.lotStep, i.lotMinQty
        )
        notes.add("size $min..$max step $step ${i.base}")

        val depth = deriveDepth(
            maxOf(perTrade, i.largestConfiguredNotionalUsdt) * 3.0,
            i.levelNotional
        )
        notes.add("depth $depth levels")

        val cap = deriveCap(i.budgetUsdt, perTrade)
        notes.add("cap $cap trades")

        val strategy = deriveStrategy(i.preFundedRatio, i.currentStrategy)
        notes.add("strategy $strategy")

        val deadline = deriveExecutionDeadline(i.latencyMs)
        notes.add(
            "deadline " + deadline + "ms (latency " + "%.0f".format(i.latencyMs) +
                "ms x " + EXECUTION_LEGS + " legs x " + DEADLINE_HEADROOM + ")"
        )

        val armTtl = deriveArmTtl(deadline, i.latencyMs, i.observedMedianOpportunityMs)
        val armMargin = deriveArmingMargin(fee)
        val abandonMargin = deriveAbandonMargin(fee)
        val spreadLimit = deriveSpreadTightMaxBps(i.observedWorstLegSpreadBps)
        val imbalanceLimit = deriveImbalanceMaxAbs(i.observedP90AbsImbalance)
        val cadenceLimit = deriveCadenceMaxInterArrivalMs(i.observedMedianInterArrivalMs, i.latencyMs)
        notes.add(
            "arm " + armTtl + "ms, fire at " + "%.4f".format(threshold + armMargin) +
                "%, abandon below " + "%.4f".format(threshold - abandonMargin) + "%"
        )
        notes.add(
            "gates spread<=" + "%.2f".format(spreadLimit) + "bps (observed " +
                "%.2f".format(i.observedWorstLegSpreadBps) + "), imbalance<=" +
                "%.2f".format(imbalanceLimit) + " (observed " +
                "%.2f".format(i.observedP90AbsImbalance) + "), cadence<=" + cadenceLimit + "ms"
        )

        val paperLatency = derivePaperLatencyMs(i.latencyMs)
        val paperSlippage = derivePaperSlippagePercent(
            i.observedWorstLegSpreadBps,
            i.latencyMs,
            i.observedMedianInterArrivalMs
        )
        notes.add(
            "paper fills " + paperLatency + "ms latency, " +
                "%.4f".format(paperSlippage) + "% slippage"
        )

        return Result(
            base = i.base.uppercase().trim(),
            min = min,
            max = max,
            step = step,
            feePercent = fee,
            profitThreshold = threshold,
            cap = cap,
            depth = depth,
            strategy = strategy,
            deadlineMs = deadline,
            perTradeUsdt = perTrade,
            armTtlMs = armTtl,
            armingMarginPercent = armMargin,
            abandonMarginPercent = abandonMargin,
            spreadTightMaxBps = spreadLimit,
            imbalanceMaxAbs = imbalanceLimit,
            cadenceMaxInterArrivalMs = cadenceLimit,
            maxArmedOpportunities = deriveMaxArmed(),
            paperLatencyMs = paperLatency,
            paperSlippagePercent = paperSlippage,
            notes = notes
        )
    }
}
