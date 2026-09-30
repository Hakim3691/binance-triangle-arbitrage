package com.hakim3691.bta.core

import com.hakim3691.bta.util.Util
import java.util.concurrent.ConcurrentHashMap

/** Port of the `actual` result object from the original execution strategies. */
data class ActualResult(
    val a: AssetLedger = AssetLedger(),
    val b: AssetLedger = AssetLedger(),
    val c: AssetLedger = AssetLedger(),
    var fees: Double = 0.0,
    /** Set when at least one leg failed to produce an orderId. */
    var failed: Boolean = false
)

/** Result of a strategy run including which legs received valid fill confirmations. */
data class StrategyResult(
    val actual: ActualResult,
    val abDone: Boolean,
    val bcDone: Boolean,
    val caDone: Boolean,
    /** Set when a pre-flight guard abandoned the round trip mid-flight. */
    val aborted: Boolean = false,
    val abortReason: String? = null,
    val projectedPercent: Double? = null,
    val unwound: Boolean = false,
    val requiresManualClose: Boolean = false,
    val strandedAssets: List<String> = emptyList()
)

/**
 * Tracks the state of a single triangle execution across its three legs.
 * Surfaced to the UI through the ScannerController.
 */
data class ExecutionState(
    val id: String,
    val startTime: Long,
    val strategy: String,
    var status: Status = Status.IN_PROGRESS,
    var abComplete: Boolean = false,
    var bcComplete: Boolean = false,
    var caComplete: Boolean = false,
    var error: String? = null,
    var actual: ActualResult? = null,
    /** Set when a pre-flight check stopped the round trip mid-flight. */
    var aborted: Boolean = false,
    /** Why the round trip was abandoned (deadline or projected shortfall). */
    var abortReason: String? = null,
    /** The percent the pre-flight re-pricing projected for the remaining legs. */
    var projectedPercent: Double? = null,
    /** Whether the already-filled legs were reversed back to the base asset. */
    var unwound: Boolean = false,
    /**
     * True when the app could not put the position back by itself. The user
     * must close it on the exchange by hand.
     */
    var requiresManualClose: Boolean = false,
    /** Assets still held because an unwind leg did not fill. */
    var strandedAssets: List<String> = emptyList()
) {
    enum class Status { IN_PROGRESS, COMPLETED, FAILED, PARTIAL, ABORTED }
}

/**
 * Kotlin port of `src/main/ArbitrageExecution.js`.
 *
 * Faithfully reproduces:
 *  - inProgressIds / inProgressSymbols / attemptedPositions tracking
 *  - isSafeToExecute() gating logic in the same order with the same semantics
 *  - linear and parallel execution strategies, including leg recalculation
 *  - parseActualResults() fee extraction (BNB commission only)
 *
 * Trade placement is delegated to a [TradeExecutor] so the same logic runs
 * against Binance REST (live), the paper-trading simulator, or test doubles.
 */
class ArbitrageExecution(
    private val executor: TradeExecutor
) {

    val inProgressIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val inProgressSymbols: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** execution start time (ms) -> calculated position id, mirroring the original map */
    val attemptedPositions: ConcurrentHashMap<Long, String> = ConcurrentHashMap()

    /** Invoked when the execution cap is reached (original calls process.exit(0)). */
    var shutdownListener: (() -> Unit)? = null

    /**
     * Deadline + pre-flight re-pricing. Injected so tests can drive the clock.
     * Reads thresholds from [ExecutionConfig].
     */
    var preFlightGuard: PreFlightGuard = PreFlightGuard()

    // ------------------------------------------------------------------
    // Gate: should this calculated position be executed right now?
    // ------------------------------------------------------------------

    fun isSafeToExecute(calculated: CalculatedPosition, now: Long = System.currentTimeMillis()): Boolean {
        val depth = calculated.usedDepth ?: return false

        // Exchange legality first: a leg Binance will reject is not a skipped
        // trade but an unwind-in-waiting, because by the time the rejection
        // lands the earlier legs are already filled. See LegalityCheck.
        val legality = LegalityCheck.check(calculated)
        if (!legality.legal) return false

        // Profit Threshold is Not Satisfied
        if (calculated.percent < ExecutionConfig.profitThreshold) return false

        // Age Threshold is Not Satisfied
        val minEventTime = minOf(depth.ab.eventTime, depth.bc.eventTime, depth.ca.eventTime)
        val ageInMilliseconds = now - minEventTime
        if (minEventTime <= 0L || ageInMilliseconds > ExecutionConfig.ageThresholdMs) return false

        val s = calculated.trade.symbol
        if (inProgressSymbols.contains(s.a)) return false
        if (inProgressSymbols.contains(s.b)) return false
        if (inProgressSymbols.contains(s.c)) return false

        val cutoff = now - ExecutionConfig.ageThresholdMs
        if (attemptedPositions.entries.any { it.value == calculated.id && it.key > cutoff }) return false

        if (getAttemptedPositionsCountInLastSecond(now) > 1) return false

        if (ExecutionConfig.cap != 0 && getAttemptedPositionsCount() >= ExecutionConfig.cap) return false

        return true
    }

    // ------------------------------------------------------------------
    // Execution entry point
    // ------------------------------------------------------------------

    /**
     * Claims the triangle atomically, before any coroutine is launched.
     *
     * The original was single-threaded, so setting the in-progress markers at
     * the top of the execution function was race-free. In Kotlin the scan
     * callback and the execution live on different dispatchers, so the claim
     * has to happen on the caller's thread: two depth updates arriving back
     * to back would otherwise both pass the "nothing in progress" check
     * before either registered, and run two triangles concurrently - exactly
     * what the per-symbol busy checks exist to prevent.
     *
     * @return false when the triangle (or any of its symbols) is already
     * claimed, in which case nothing was started.
     */
    fun tryBegin(calculated: CalculatedPosition, now: Long = System.currentTimeMillis()): Boolean {
        val s = calculated.trade.symbol
        synchronized(this) {
            if (inProgressIds.isNotEmpty()) return false
            if (s.a in inProgressSymbols || s.b in inProgressSymbols || s.c in inProgressSymbols) return false
            if (ExecutionConfig.cap != 0 && attemptedPositions.size >= ExecutionConfig.cap) return false
            inProgressIds.add(calculated.id)
            inProgressSymbols.add(s.a)
            inProgressSymbols.add(s.b)
            inProgressSymbols.add(s.c)
        }
        return true
    }

    suspend fun executeCalculatedPosition(calculated: CalculatedPosition): ExecutionState {
        val startTime = System.currentTimeMillis()
        val s = calculated.trade.symbol

        // The caller has normally claimed the triangle via [tryBegin] so the
        // decision and the claim are one atomic step. Executing without a
        // claim is still supported (Kelly funds and executes through this
        // path under its own mutex).
        val state = ExecutionState(calculated.id, startTime, ExecutionConfig.strategy)
        attemptedPositions[startTime] = calculated.id

        try {
            val result = when (ExecutionConfig.strategy) {
                "parallel" -> parallelExecutionStrategy(calculated, startTime)
                else -> linearExecutionStrategy(calculated, startTime)
            }
            state.actual = result.actual
            state.abComplete = result.abDone
            state.bcComplete = result.bcDone
            state.caComplete = result.caDone
            state.aborted = result.aborted
            state.abortReason = result.abortReason
            state.projectedPercent = result.projectedPercent
            state.unwound = result.unwound
            state.requiresManualClose = result.requiresManualClose
            state.strandedAssets = result.strandedAssets
            state.status = when {
                result.aborted -> ExecutionState.Status.ABORTED
                result.actual.failed -> ExecutionState.Status.FAILED
                result.abDone && result.bcDone && result.caDone -> ExecutionState.Status.COMPLETED
                else -> ExecutionState.Status.PARTIAL
            }
        } catch (e: Exception) {
            state.error = e.message ?: e.toString()
            state.status = when {
                state.caComplete -> ExecutionState.Status.PARTIAL
                state.bcComplete -> ExecutionState.Status.FAILED
                state.abComplete -> ExecutionState.Status.FAILED
                else -> ExecutionState.Status.FAILED
            }
        } finally {
            inProgressIds.remove(calculated.id)
            inProgressSymbols.remove(s.a)
            inProgressSymbols.remove(s.b)
            inProgressSymbols.remove(s.c)

            // Entries older than the duplicate window can never be read
            // again (isSafeToExecute only looks back ageThresholdMs), so a
            // long session in unlimited-cap mode would otherwise grow the
            // map without bound.
            val cutoff = System.currentTimeMillis() - ATTEMPTED_RETENTION_MS
            attemptedPositions.keys.removeAll { it < cutoff }

            if (ExecutionConfig.cap != 0 && attemptedPositions.size >= ExecutionConfig.cap) {
                shutdownListener?.invoke()
            }
        }
        return state
    }

    // ------------------------------------------------------------------
    // Linear strategy (port of linearExecutionStrategy)
    // ------------------------------------------------------------------

    suspend fun linearExecutionStrategy(
        calculated: CalculatedPosition,
        startTime: Long = System.currentTimeMillis()
    ): StrategyResult {
        val actual = ActualResult()
        var recalculatedBc = calculated.bc.quantity
        var recalculatedCa = calculated.ca.quantity
        var abDone = false
        var bcDone = false
        var caDone = false

        // Filled legs, newest last, so an abort can unwind them in reverse.
        val filled = ArrayList<Pair<Relationship, Double>>()

        // Deadline only: nothing has been spent yet, so there is nothing to
        // unwind and nothing to re-price.
        val first = preFlightGuard.verdict(
            calculated.trade,
            preFlightGuard.freshDepth(executor, calculated.trade),
            spentA = 0.0, earnedB = 0.0, earnedC = 0.0,
            startTime = startTime,
            stage = PreFlightGuard.Stage.BEFORE_FIRST
        )
        if (!first.proceed) {
            return StrategyResult(
                actual = actual, abDone = false, bcDone = false, caDone = false,
                aborted = true, abortReason = first.reason
            )
        }

        val resultsAb = executor.placeMarketOrder(calculated.trade.ab.ticker, calculated.ab.quantity, calculated.trade.ab.method)
        if (resultsAb.orderId == null) {
            // Nothing was opened, so there is nothing to unwind and no reason
            // to keep submitting the remaining legs of a triangle that cannot
            // start.
            return StrategyResult(
                actual = actual, abDone = false, bcDone = false, caDone = false,
                abortReason = "leg AB was rejected"
            )
        }
        if (resultsAb.orderId != null) {
            abDone = true
            val leg = parseActualResults(calculated.trade.ab.method, resultsAb)
            actual.a.spent = leg.spent
            actual.b.earned = leg.earned
            actual.fees += leg.bnbFees
            filled.add(calculated.trade.ab to calculated.ab.quantity)

            // The edge may have evaporated while leg AB was in flight.
            val afterAb = preFlightGuard.verdict(
                calculated.trade,
                preFlightGuard.freshDepth(executor, calculated.trade),
                spentA = actual.a.spent, earnedB = actual.b.earned, earnedC = 0.0,
                startTime = startTime,
                stage = PreFlightGuard.Stage.AFTER_AB
            )
            if (!afterAb.proceed) {
                val u = unwind(calculated.trade, filled, actual)
                return StrategyResult(
                    actual = actual, abDone = true, bcDone = false, caDone = false,
                    aborted = true, abortReason = afterAb.reason,
                    projectedPercent = afterAb.projectedPercent,
                    unwound = u.complete,
                    requiresManualClose = !u.complete,
                    strandedAssets = u.strandedAssets
                )
            }

            recalculatedBc = CalculationNode.recalculateTradeLeg(
                calculated.trade.bc, actual.b.earned,
                executor.getSortedDepth(calculated.trade.bc.ticker)
            )
        }

        val resultsBc = executor.placeMarketOrder(calculated.trade.bc.ticker, recalculatedBc, calculated.trade.bc.method)
        if (resultsBc.orderId == null && abDone) {
            // Leg AB is already filled and leg BC was rejected. Placing CA
            // now would compound the imbalance, so the position is put back
            // instead and, if that fails, flagged for manual closure.
            val u = unwind(calculated.trade, filled, actual)
            return StrategyResult(
                actual = actual, abDone = true, bcDone = false, caDone = false,
                aborted = false,
                abortReason = "leg BC was rejected after AB filled",
                unwound = u.complete,
                requiresManualClose = !u.complete,
                strandedAssets = u.strandedAssets
            )
        }
        if (resultsBc.orderId != null) {
            bcDone = true
            val leg = parseActualResults(calculated.trade.bc.method, resultsBc)
            actual.b.spent = leg.spent
            actual.c.earned = leg.earned
            actual.fees += leg.bnbFees
            filled.add(calculated.trade.bc to recalculatedBc)

            val afterBc = preFlightGuard.verdict(
                calculated.trade,
                preFlightGuard.freshDepth(executor, calculated.trade),
                spentA = actual.a.spent, earnedB = actual.b.earned, earnedC = actual.c.earned,
                startTime = startTime,
                stage = PreFlightGuard.Stage.AFTER_BC
            )
            if (!afterBc.proceed) {
                val u = unwind(calculated.trade, filled, actual)
                return StrategyResult(
                    actual = actual, abDone = true, bcDone = true, caDone = false,
                    aborted = true, abortReason = afterBc.reason,
                    projectedPercent = afterBc.projectedPercent,
                    unwound = u.complete,
                    requiresManualClose = !u.complete,
                    strandedAssets = u.strandedAssets
                )
            }

            recalculatedCa = CalculationNode.recalculateTradeLeg(
                calculated.trade.ca, actual.c.earned,
                executor.getSortedDepth(calculated.trade.ca.ticker)
            )
        }

        val resultsCa = executor.placeMarketOrder(calculated.trade.ca.ticker, recalculatedCa, calculated.trade.ca.method)
        if (resultsCa.orderId == null && (abDone || bcDone)) {
            // The closing leg failed: AB and/or BC are open on the exchange.
            val u = unwind(calculated.trade, filled, actual)
            return StrategyResult(
                actual = actual, abDone = abDone, bcDone = bcDone, caDone = false,
                aborted = false,
                abortReason = "leg CA was rejected after earlier legs filled",
                unwound = u.complete,
                requiresManualClose = !u.complete,
                strandedAssets = u.strandedAssets
            )
        }
        if (resultsCa.orderId != null) {
            caDone = true
            val leg = parseActualResults(calculated.trade.ca.method, resultsCa)
            actual.c.spent = leg.spent
            actual.a.earned = leg.earned
            actual.fees += leg.bnbFees
            filled.add(calculated.trade.ca to recalculatedCa)
        }

        actual.a.delta = actual.a.earned - actual.a.spent
        actual.b.delta = actual.b.earned - actual.b.spent
        actual.c.delta = actual.c.earned - actual.c.spent
        actual.failed = !(abDone && bcDone && caDone)
        return StrategyResult(actual, abDone, bcDone, caDone)
    }

    /**
     * Reverses the legs that already filled, newest first, so an aborted round
     * trip returns to the base asset instead of parking the position in the
     * middle asset. Each leg is undone with the opposite side on the same
     * ticker for the quantity that was traded, which is what restores the
     * inventory the leg consumed.
     *
     * @return true when every reversal filled.
     */
    private suspend fun unwind(
        trade: Trade,
        filled: List<Pair<Relationship, Double>>,
        actual: ActualResult
    ): UnwindResult {
        var complete = true
        val stranded = LinkedHashSet<String>()
        for ((relationship, quantity) in filled.asReversed()) {
            if (quantity <= 0.0) continue
            val opposite = if (relationship.method == Relationship.BUY) Relationship.SELL else Relationship.BUY
            val response = runCatching {
                executor.placeMarketOrder(relationship.ticker, quantity, opposite)
            }.getOrElse { null }
            if (response == null || response.orderId == null) {
                complete = false
                // A leg that could not be reversed leaves the asset it was
                // holding open on the exchange.
                stranded.add(relationship.quote)
            }
        }
        // After an unwind the round trip produced no base-asset result.
        if (complete) {
            actual.a.earned = 0.0
            actual.a.delta = -actual.a.spent
            actual.b.earned = 0.0
            actual.c.earned = 0.0
            actual.failed = false
        } else {
            actual.failed = true
        }
        return UnwindResult(complete = complete, strandedAssets = stranded.toList())
    }

    /** Outcome of reversing the legs that already filled. */
    data class UnwindResult(
        val complete: Boolean,
        /** Assets still held because their reversal did not fill. */
        val strandedAssets: List<String>
    )

    // ------------------------------------------------------------------
    // Parallel strategy (port of parallelExecutionStrategy)
    // ------------------------------------------------------------------

    suspend fun parallelExecutionStrategy(
        calculated: CalculatedPosition,
        startTime: Long = System.currentTimeMillis()
    ): StrategyResult {
        // Parallel submits all three legs at once, so there is no mid-flight
        // window to guard: one check up front covers the deadline, and the legs
        // are sized together so the books cannot drift between them.
        val pre = preFlightGuard.verdict(
            calculated.trade,
            preFlightGuard.freshDepth(executor, calculated.trade),
            spentA = calculated.a.spent, earnedB = 0.0, earnedC = 0.0,
            startTime = startTime,
            stage = PreFlightGuard.Stage.BEFORE_SUBMIT
        )
        if (!pre.proceed) {
            return StrategyResult(
                actual = ActualResult(), abDone = false, bcDone = false, caDone = false,
                aborted = true, abortReason = pre.reason,
                projectedPercent = pre.projectedPercent
            )
        }

        val resultsAb = executor.placeMarketOrder(calculated.trade.ab.ticker, calculated.ab.quantity, calculated.trade.ab.method)
        val resultsBc = executor.placeMarketOrder(calculated.trade.bc.ticker, calculated.bc.quantity, calculated.trade.bc.method)
        val resultsCa = executor.placeMarketOrder(calculated.trade.ca.ticker, calculated.ca.quantity, calculated.trade.ca.method)

        val actual = ActualResult()
        val abDone = resultsAb.orderId != null
        val bcDone = resultsBc.orderId != null
        val caDone = resultsCa.orderId != null

        if (abDone && bcDone && caDone) {
            val legAb = parseActualResults(calculated.trade.ab.method, resultsAb)
            actual.a.spent = legAb.spent
            actual.b.earned = legAb.earned
            actual.fees += legAb.bnbFees

            val legBc = parseActualResults(calculated.trade.bc.method, resultsBc)
            actual.b.spent = legBc.spent
            actual.c.earned = legBc.earned
            actual.fees += legBc.bnbFees

            val legCa = parseActualResults(calculated.trade.ca.method, resultsCa)
            actual.c.spent = legCa.spent
            actual.a.earned = legCa.earned
            actual.fees += legCa.bnbFees

            actual.a.delta = actual.a.earned - actual.a.spent
            actual.b.delta = actual.b.earned - actual.b.spent
            actual.c.delta = actual.c.earned - actual.c.spent
        } else {
            actual.failed = true
        }
        return StrategyResult(actual, abDone, bcDone, caDone)
    }

    // ------------------------------------------------------------------
    // parseActualResults (port)
    // ------------------------------------------------------------------

    data class ParsedLeg(val spent: Double, val earned: Double, val bnbFees: Double)

    fun parseActualResults(method: String, response: OrderResponse): ParsedLeg {
        val spent = if (method == Relationship.BUY) response.cummulativeQuoteQty else response.executedQty
        val earned = if (method == Relationship.SELL) response.cummulativeQuoteQty else response.executedQty
        val bnbFees = response.fills
            .filter { it.commissionAsset == "BNB" }
            .sumOf { it.commission }
        return ParsedLeg(spent, earned, bnbFees)
    }

    /** History kept for the same-opportunity duplicate check. */
    companion object {
        const val ATTEMPTED_RETENTION_MS = 2L * 60 * 60 * 1000
    }

    fun getAttemptedPositionsCount(): Int = attemptedPositions.size

    fun getAttemptedPositionsCountInLastSecond(now: Long = System.currentTimeMillis()): Int =
        attemptedPositions.keys.count { it > now - 1000 }

    /** Port of Util.pruneSnapshot (used for trace logging of used depth). */
    fun prunedSnapshot(snapshot: DepthSnapshot, threshold: Int): DepthSnapshot =
        DepthSnapshot(
            bids = Util.prune(snapshot.bids, threshold),
            asks = Util.prune(snapshot.asks, threshold),
            eventTime = snapshot.eventTime
        )
}
