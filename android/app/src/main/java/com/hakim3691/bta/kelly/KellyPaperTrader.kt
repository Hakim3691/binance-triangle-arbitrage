package com.hakim3691.bta.kelly

import com.hakim3691.bta.core.CalculationNode
import com.hakim3691.bta.core.CalculatedPosition
import com.hakim3691.bta.core.ExecutionState
import com.hakim3691.bta.core.LegalityCheck
import com.hakim3691.bta.core.OrderResponse
import com.hakim3691.bta.core.Relationship
import com.hakim3691.bta.core.ShallowDepthException
import com.hakim3691.bta.log.LogRepository
import com.hakim3691.bta.paper.PaperTradingEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Kelly-criterion-gated automatic paper trading.
 *
 * Flow per qualifying opportunity:
 *  1. Evaluate [KellyCriterion] against the expected percent and the current
 *     empirical volatility of paper outcomes.
 *  2. Size the position: allocation = budget * kellyFraction (clamped by
 *     [KellyConfig.maxAllocationPerTrade] and available USDT).
 *  3. Fund: BUY the base asset with USDT through the paper engine
 *     (balances/fills stay consistent in one ledger).
 *  4. Execute the triangle with the funded quantity via [com.hakim3691.bta.core.ArbitrageExecution]
 *     (linear strategy by default, same as manual execution).
 *  5. Settle: SELL the resulting base amount back to USDT.
 *  6. Record the outcome percent and update the volatility estimate.
 *
 * No real orders are ever placed: every leg goes through the paper engine.
 */
class KellyPaperTrader(
    private val paperEngine: PaperTradingEngine,
    private val arbExecution: com.hakim3691.bta.core.ArbitrageExecution,
    private val onTradeCompleted: (ExecutionState, CalculatedPosition) -> Unit,
    private val onStatsChanged: () -> Unit
) {

    data class PaperOutcome(val percent: Double, val usdtPnl: Double, val timestamp: Long)

    data class Stats(
        val equityUsdt: Double,
        val startingUsdt: Double,
        val trades: Int,
        val wins: Int,
        val winRate: Double,
        val totalPnlUsdt: Double,
        val sigmaPercent: Double,
        val lastPWin: Double?
    )

    private val mutex = Mutex()
    private val outcomes = ArrayList<PaperOutcome>()
    private val lastExecutedAt = ConcurrentHashMap<String, Long>()
    /** Residual non-USDT holdings left by unwinds that could not complete. */
    private val stranded = ConcurrentHashMap<String, Double>()
    private var lastPWin: Double? = null

    /** Phase 4b: routes settlement and funding through the live pair graph. */
    private val router = AssetRouter(paperEngine)

    val stats: Stats get() = snapshotStats()

    /**
     * Total equity including assets stranded by failed unwinds.
     *
     * A partial unwind leaves real value sitting in non-USDT assets. Counting
     * only USDT makes every such event look like a total loss, understating
     * equity and corrupting the sigma the gate runs on - so the residual
     * holdings left behind by an unwind are valued at the last known book
     * mid-price and folded into equity.
     */
    private fun strandedValueUsdt(): Double {
        if (stranded.isEmpty()) return 0.0
        var total = 0.0
        for ((asset, qty) in stranded) {
            if (qty <= 0.0) continue
            val book = paperEngine.getSortedDepth(asset + "USDT")
            val bestBid = book.bids.keys.maxOrNull()
            if (bestBid != null) total += qty * bestBid
            // No USDT book for the asset: it stays uncounted rather than guessed.
        }
        return total
    }

    fun recordStranded(assets: List<String>) {
        val engine = paperEngine
        for (asset in assets) {
            if (asset == "USDT") continue
            val qty = engine.balances.getOrDefault(asset, 0.0)
            if (qty > 0.0) stranded[asset] = qty
        }
    }

    fun snapshotStats(): Stats {
        val usdt = paperEngine.balances.getOrDefault("USDT", 0.0)
        val strandedUsdt = strandedValueUsdt()
        val synchronizedOutcomes = synchronized(outcomes) { outcomes.toList() }
        val wins = synchronizedOutcomes.count { it.percent > 0 }
        return Stats(
            equityUsdt = usdt + strandedUsdt,
            startingUsdt = KellyConfig.budgetUsdt,
            trades = synchronizedOutcomes.size,
            wins = wins,
            winRate = if (synchronizedOutcomes.isEmpty()) 0.0 else wins.toDouble() / synchronizedOutcomes.size,
            totalPnlUsdt = synchronizedOutcomes.sumOf { it.usdtPnl },
            sigmaPercent = sigma(),
            lastPWin = lastPWin
        )
    }

    /** Empirical sigma of outcomes, or the configured default until enough samples. */
    fun sigma(): Double {
        val values = synchronized(outcomes) { outcomes.map { it.percent } }
        return if (values.size >= KellyConfig.sigmaSampleThreshold && values.size > 1) {
            val mean = values.average()
            sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
                .coerceAtLeast(1e-4)
        } else {
            KellyConfig.defaultSigmaPercent
        }
    }

    /**
     * Considers one analyzed opportunity for Kelly-gated execution.
     * Returns true when a full trade cycle (fund + triangle + settle) completed.
     */
    suspend fun consider(calculated: CalculatedPosition): Boolean {
        if (!KellyConfig.enabled) return false
        if (calculated.percent <= 0.0) return false // current logic: profitable opportunities only
        if (arbExecution.inProgressIds.isNotEmpty()) return false

        // Per-opportunity cooldown to avoid re-executing the same id every cycle
        val now = System.currentTimeMillis()
        val last = lastExecutedAt[calculated.id] ?: 0L
        if (now - last < COOLDOWN_MS) return false

        val snapshot = calculated.usedDepth ?: return false
        val base = calculated.trade.symbol.a
        val usdtBalance = paperEngine.balances.getOrDefault("USDT", 0.0)
        if (usdtBalance < KellyConfig.minTradeUsdt) return false

        val evaluation = KellyCriterion.evaluate(
            expectedPercent = calculated.percent,
            sigmaPercent = sigma(),
            requiredProbability = KellyConfig.requiredProbability,
            maxKellyFraction = KellyConfig.maxKellyFraction
        )
        lastPWin = evaluation.probabilityOfProfit
        if (!evaluation.shouldExecute) {
            onStatsChanged()
            return false
        }

        return mutex.withLock {
            try {
                // Fixed-fractional sizing, deliberately. The evaluation's
                // kellyFraction is a property of a model whose assumptions do
                // not hold here (see KellyConfig) - it gates the trade but is
                // not trusted with its size. The allocation clamp still caps
                // the per-trade exposure.
                val budget = paperEngine.balances.getOrDefault("USDT", 0.0)
                val allocation = budget * KellyConfig.maxAllocationPerTrade
                var investment = maxOf(
                    allocation * KellyConfig.investmentFractionOfKelly,
                    KellyConfig.minTradeUsdt
                )
                investment = investment.coerceAtMost(budget)
                if (investment < KellyConfig.minTradeUsdt) {
                    LogRepository.info(
                        "kelly",
                        "Skipping trade: budget ${"%.2f".format(budget)} USDT below minimum trade size ${"%.2f".format(KellyConfig.minTradeUsdt)}"
                    )
                    return@withLock false
                }

                LogRepository.info(
                    "kelly",
                    "Kelly execute ${calculated.id}: ${evaluation.summary}, " +
                        "investing ${"%.2f".format(investment)} USDT " +
                        "(fixed ${(KellyConfig.investmentFractionOfKelly * 100).toInt()}% of ${KellyConfig.maxAllocationPerTrade} clamp x budget)"
                )
                lastExecutedAt[calculated.id] = System.currentTimeMillis()

                // 1) Fund: USDT -> base (BUY base on base/USDT). The funding
                // leg consumes the same USDT balance the scanner sizes
                // triangles against, so the base asset is registered as
                // in-progress work first - otherwise a concurrent triangle
                // can be sized against money that is about to be spent.
                arbExecution.inProgressSymbols.add(base)
                try {
                    val funding = fundBase(base, investment)
                    if (funding == null) {
                        LogRepository.warn("kelly", "Funding order failed on ${calculated.trade.symbol.a}")
                        return@withLock false
                    }
                    val (fundedBaseQty, usdtSpent) = funding

                    // 2) Size the triangle at the funded quantity (scale down if book too thin)
                    val sized = sizeTriangle(calculated, snapshot, fundedBaseQty, base)
                    if (sized == null) {
                        // Book too shallow even at reduced size: unwind funding
                        settleBase(base, fundedBaseQty)
                        return@withLock false
                    }

                    // 3) Execute the triangle through the paper engine
                    val state = arbExecution.executeCalculatedPosition(sized)
                    onTradeCompleted(state, sized)

                    // A failed unwind leaves real value behind; count it in
                    // equity rather than letting it vanish from the books.
                    if (state.requiresManualClose) recordStranded(state.strandedAssets)

                    val earnedBase = state.actual?.a?.earned ?: 0.0

                    // 4) Settle: base -> USDT, routed through the pair graph when
                    // no direct book exists (Phase 4b).
                    val settledUsdt = if (earnedBase > 0.0) {
                        router.convert(base, earnedBase, "USDT")
                    } else 0.0

                    val pnl = settledUsdt - usdtSpent
                    val outcomePercent = if (usdtSpent > 0) pnl / usdtSpent * 100.0 else 0.0
                    synchronized(outcomes) { outcomes.add(PaperOutcome(outcomePercent, pnl, System.currentTimeMillis())) }
                    if (synchronized(outcomes) { outcomes.size } > MAX_OUTCOMES) {
                        synchronized(outcomes) { outcomes.removeAt(0) }
                    }

                    LogRepository.info(
                        "kelly",
                        "Kelly result ${calculated.id}: ${"%+.4f".format(outcomePercent)}% (${ "%.4f".format(pnl)} USDT) balances USDT=${"%.2f".format(paperEngine.balances.getOrDefault("USDT", 0.0))}"
                    )
                    true
                } finally {
                    arbExecution.inProgressSymbols.remove(base)
                }
            } finally {
                onStatsChanged()
            }
        }
    }

    /** BUY base with USDT; returns (baseQtyReceived, usdtSpent) or null on failure. */
    private suspend fun fundBase(base: String, usdtAllocation: Double): Pair<Double, Double>? {
        val ticker = base + "USDT"
        if (PaperTradingEngine.paperUniverse[ticker] != null) {
            val depth = paperEngine.getSortedDepth(ticker)
            if (depth.asks.isEmpty()) return null
            // Base quantity purchasable with usdtAllocation at the best ask (conservative pre-size;
            // the engine walk refines the actual fill)
            val bestAsk = depth.asks.keys.first()
            val preSize = usdtAllocation / bestAsk
            val response = paperEngine.placeMarketOrder(ticker, preSize, Relationship.BUY)
            if (response.orderId == null || response.executedQty <= 0.0) {
                LogRepository.warn("kelly", "Funding order failed on $ticker")
                return null
            }
            return response.executedQty to response.cummulativeQuoteQty
        }
        // Phase 4b: no direct USDT book - fund through the pair graph instead of
        // giving up on every triangle rooted at an asset quoted elsewhere.
        val bought = router.convert("USDT", usdtAllocation, base)
        return if (bought > 0.0) bought to usdtAllocation else null
    }

    /** SELL base back to USDT; returns USDT received or null when no market. */
    private suspend fun settleBase(base: String, baseQty: Double): Double? {
        if (baseQty <= 0.0) return null
        val ticker = base + "USDT"
        if (PaperTradingEngine.paperUniverse[ticker] == null) {
            // Phase 4b: route through intermediates instead of abandoning the
            // settlement - the value the triangle just earned must not be
            // stranded just because its asset has no USDT book.
            val routed = router.convert(base, baseQty, "USDT")
            return routed.takeIf { it > 0.0 }
        }
        val response = paperEngine.placeMarketOrder(ticker, baseQty, Relationship.SELL)
        return if (response.orderId != null) response.cummulativeQuoteQty else null
    }

    /**
     * Recomputes the triangle at [fundedBaseQty], halving on shallow depth
     * (max 3 attempts). Falls back to null when the book cannot support it.
     */
    private fun sizeTriangle(
        original: CalculatedPosition,
        snapshot: CalculationNode.TradeDepthSnapshot,
        fundedBaseQty: Double,
        base: String
    ): CalculatedPosition? {
        var quantity = fundedBaseQty
        repeat(3) {
            try {
                val candidate = CalculationNode.calculate(quantity, original.trade, snapshot)
                // Same rule as the scanner: a leg the exchange would reject
                // turns the trade into an unwind, so halve and retry first.
                if (LegalityCheck.check(candidate).legal) return candidate
            } catch (e: ShallowDepthException) {
                // Book too shallow even at this size; fall through to halving.
            }
            quantity /= 2.0
        }
        return null
    }

    fun reset(budgetUsdt: Double) {
        synchronized(outcomes) { outcomes.clear() }
        lastExecutedAt.clear()
        stranded.clear()
        lastPWin = null
        KellyConfig.budgetUsdt = budgetUsdt
        onStatsChanged()
    }

    companion object {
        private const val COOLDOWN_MS = 2_000L
        private const val MAX_OUTCOMES = 200
    }
}
