package com.hakim3691.bta.paper

import com.hakim3691.bta.core.CalculationNode
import com.hakim3691.bta.core.DepthSnapshot
import com.hakim3691.bta.core.ExecutionConfig
import com.hakim3691.bta.core.OrderFill
import com.hakim3691.bta.core.OrderResponse
import com.hakim3691.bta.core.Relationship
import com.hakim3691.bta.core.TradeExecutor
import com.hakim3691.bta.util.Util
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * Paper-trading simulator implementing [TradeExecutor].
 *
 * Market orders are filled against the *current live depth snapshot* of the
 * affected ticker by walking the book exactly like CalculationNode
 * (bids for SELL, asks for BUY), which reproduces the original app's
 * `test` mode behavior where orders are validated but not placed.
 *
 * Extends the original semantics with:
 *  - portfolio balance simulation with a configurable starting balance
 *  - BNB fee accounting identical to parseActualResults (EXECUTION.FEE per leg)
 *  - configurable slippage applied adversely to every fill price
 *  - configurable network/execution latency
 *  - random or scripted leg failures for realistic testing
 *  - partial-fill modeling when the book cannot cover the full quantity
 */
class PaperTradingEngine(
    private val depthProvider: (String) -> DepthSnapshot?,
    startingBalance: Map<String, Double>,
    /**
     * Simulated per-leg latency. Mutable so the AUTO tuner can keep it aligned
     * with the measured round trip while the scanner is running.
     */
    @Volatile var latencyMs: Long = 0,
    /** Simulated adverse fill slippage, percent. See [setFriction]. */
    @Volatile var slippagePercent: Double = 0.0,
    private val failureProbabilityPerLeg: Double = 0.0
) : TradeExecutor {

    /**
     * Applies new execution friction to every subsequent fill.
     *
     * Both values are zero by default, which makes the simulator fill every
     * order at exactly the price the engine projected: deadlines, pre-flight
     * aborts and unwinds then never happen, and any fire-timing behaviour
     * tuned against it is tuned against a fiction. They are derived from the
     * measured REST round trip and the observed book spreads instead.
     */
    fun setFriction(latencyMs: Long, slippagePercent: Double) {
        this.latencyMs = latencyMs.coerceAtLeast(0L)
        this.slippagePercent = maxOf(0.0, slippagePercent)
    }

    /** Current simulated friction, reported on the dashboard. */
    fun friction(): String = latencyMs.toString() + "ms / " + "%.4f".format(slippagePercent) + "%"

    data class PaperFill(val price: Double, val qty: Double, val commission: Double, val commissionAsset: String)

    /** Balances keyed by asset; mirrored into a thread-safe map. */
    val balances: ConcurrentHashMap<String, Double> = ConcurrentHashMap(startingBalance)

    val startingBalanceCopy: Map<String, Double> = startingBalance.toMap()

    /** Completed paper fills for the trade history UI. */
    val fillLog = java.util.concurrent.ConcurrentLinkedQueue<PaperTradeRecord>()

    data class PaperTradeRecord(
        val timestamp: Long,
        val ticker: String,
        val side: String,
        val quantity: Double,
        val price: Double,
        val quoteQty: Double,
        val commissionBnb: Double
    )

    override suspend fun placeMarketOrder(ticker: String, quantity: Double, method: String): OrderResponse {
        if (latencyMs > 0) delay(latencyMs)

        // Scripted / random failure simulation
        if (failureProbabilityPerLeg > 0 && Math.random() < failureProbabilityPerLeg) {
            return OrderResponse.failed()
        }

        val depth = depthProvider(ticker)
            ?: return OrderResponse.failed()

        // Determine assets
        // The ticker's base/quote assets are resolved from the configured trading universe.
        val base = paperUniverse[ticker]?.first ?: return OrderResponse.failed()
        val quote = paperUniverse[ticker]?.second ?: return OrderResponse.failed()

        val slippage = 1.0 + (slippagePercent / 100.0) * (if (method == Relationship.BUY) 1.0 else -1.0)

        var executedQty = 0.0
        var cummulativeQuoteQty = 0.0
        val fills = ArrayList<OrderFill>()

        try {
            if (method == Relationship.BUY) {
                // Buy `quantity` base units: consume asks best-first, paying quote.
                var remainingBase = quantity * slippage
                for ((price, qty) in depth.asks) {
                    if (qty < remainingBase) {
                        remainingBase -= qty
                        executedQty += qty
                        cummulativeQuoteQty += qty * price
                    } else {
                        executedQty += remainingBase
                        cummulativeQuoteQty += remainingBase * price
                        remainingBase = 0.0
                        break
                    }
                }
                if (executedQty <= 0.0) return OrderResponse.failed()
            } else {
                // Sell `quantity` base units: consume bids best-first, receiving
                // quote. Slippage is symmetric: a market sell digs into the book
                // just like a market buy, so the base thrown at the bids grows
                // by the same adverse factor. (The `slippage` factor above is
                // sign-flipped for price reasoning; the quantity inflation is
                // not - walking LESS deep would model a better fill.)
                var remainingBase = quantity * (1.0 + (slippagePercent / 100.0))
                for ((price, qty) in depth.bids) {
                    if (qty < remainingBase) {
                        remainingBase -= qty
                        executedQty += qty
                        cummulativeQuoteQty += qty * price
                    } else {
                        executedQty += remainingBase
                        cummulativeQuoteQty += remainingBase * price
                        remainingBase = 0.0
                        break
                    }
                }
                // Partial fill only when the book could not cover the order.
                if (executedQty <= 0.0) return OrderResponse.failed()
            }
        } catch (e: Exception) {
            return OrderResponse.failed()
        }

        // Balance accounting (quote paid / base received for BUY; inverse for SELL)
        if (method == Relationship.BUY) {
            val quoteBalance = balances.getOrDefault(quote, 0.0)
            if (quoteBalance < cummulativeQuoteQty) return OrderResponse.failed()
            balances[quote] = quoteBalance - cummulativeQuoteQty
            balances[base] = balances.getOrDefault(base, 0.0) + executedQty
        } else {
            val baseBalance = balances.getOrDefault(base, 0.0)
            if (baseBalance < executedQty) return OrderResponse.failed()
            balances[base] = baseBalance - executedQty
            balances[quote] = balances.getOrDefault(quote, 0.0) + cummulativeQuoteQty
        }

        // Fee accounting: EXECUTION.FEE percent of quote value per leg, denominated in BNB
        val bnbFee = cummulativeQuoteQty * (ExecutionConfig.feePercent / 100.0)
        fills.add(OrderFill(price = 0.0, qty = executedQty, commission = bnbFee, commissionAsset = "BNB"))

        fillLog.add(
            PaperTradeRecord(
                timestamp = System.currentTimeMillis(),
                ticker = ticker,
                side = method,
                quantity = executedQty,
                price = if (executedQty > 0) cummulativeQuoteQty / executedQty else 0.0,
                quoteQty = cummulativeQuoteQty,
                commissionBnb = bnbFee
            )
        )

        return OrderResponse(
            orderId = paperOrderId.incrementAndGet(),
            executedQty = executedQty,
            cummulativeQuoteQty = cummulativeQuoteQty,
            fills = fills
        )
    }

    override fun getSortedDepth(ticker: String): DepthSnapshot =
        depthProvider(ticker) ?: DepthSnapshot.EMPTY

    companion object {
        private val paperOrderId = java.util.concurrent.atomic.AtomicLong(1_000_000)

        /**
         * The paper universe maps each ticker to its (base, quote) assets.
         * Populated from MarketCache result by the ScannerController.
         */
        val paperUniverse: ConcurrentHashMap<String, Pair<String, String>> = ConcurrentHashMap()
    }
}
