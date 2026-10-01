package com.hakim3691.bta.kelly

import com.hakim3691.bta.core.Relationship
import com.hakim3691.bta.log.LogRepository
import com.hakim3691.bta.paper.PaperTradingEngine

/**
 * Converts one asset into another by crossing live books (Phase 4b).
 *
 * The Kelly ledger is denominated in USDT, but an all-asset universe hands the
 * settlement leg assets whose only liquidity routes through an intermediate:
 * holding ZK and watching ZK/BTC and BTC/USDT means the way out is two sells,
 * not a ZK/USDT order that does not exist. The router finds the shortest book
 * path with a breadth-first search over the paper universe and executes it hop
 * by hop, pre-checking every book first so a conversion never dies halfway
 * with the position already sold.
 *
 * A BUY hop is sized from the best ask at the moment of the order, exactly
 * like the funding leg in [KellyPaperTrader]; the dust that rounding leaves in
 * the spent asset stays in the ledger, and the returned amount is what the
 * final hop actually paid out - never an estimate.
 */
class AssetRouter(private val paperEngine: PaperTradingEngine) {

    private data class RouteHop(
        val ticker: String,
        val from: String,
        val to: String,
        /** True when the hop is a SELL of [from] as the ticker's base asset. */
        val sellBase: Boolean
    )

    /**
     * Converts [qty] of [from] into [to] and returns the amount of [to]
     * received. 0.0 means no route or a book vanished mid-route; whatever was
     * already converted stays in the ledger.
     */
    suspend fun convert(from: String, qty: Double, to: String): Double {
        if (qty <= 0.0) return 0.0
        if (from == to) return qty
        val path = route(from, to)
        if (path == null) {
            LogRepository.debug("kelly", "No book route $from -> $to; holding ${qty} $from")
            return 0.0
        }
        // Preflight: every hop must have a book on the side it crosses. Abort
        // BEFORE selling anything, or a dead middle book leaves the position
        // split across two assets with no way to account for it.
        for (hop in path) {
            val depth = paperEngine.getSortedDepth(hop.ticker)
            val sideAlive = if (hop.sellBase) depth.bids.isNotEmpty() else depth.asks.isNotEmpty()
            if (!sideAlive) {
                LogRepository.warn(
                    "kelly",
                    "Conversion $from -> $to aborted: ${hop.ticker} has no " +
                        if (hop.sellBase) "bids" else "asks"
                )
                return 0.0
            }
        }
        var asset = from
        var amount = qty
        for (hop in path) {
            val response = if (hop.sellBase) {
                paperEngine.placeMarketOrder(hop.ticker, amount, Relationship.SELL)
            } else {
                // Buying [hop.to] with [amount] of the quote: size from the
                // best ask, the same conservative pre-size the funding leg uses.
                val depth = paperEngine.getSortedDepth(hop.ticker)
                val bestAsk = depth.asks.keys.first()
                paperEngine.placeMarketOrder(hop.ticker, amount / bestAsk, Relationship.BUY)
            }
            if (response.orderId == null || response.executedQty <= 0.0) {
                LogRepository.warn(
                    "kelly",
                    "Conversion $from -> $to halted at ${hop.ticker}; holding ${amount} $asset"
                )
                return if (asset == to) amount else 0.0
            }
            asset = hop.to
            amount = if (hop.sellBase) response.cummulativeQuoteQty else response.executedQty
        }
        return if (asset == to) amount else 0.0
    }

    /** Shortest chain of live markets from [from] to [to]; null when none. */
    private fun route(from: String, to: String): List<RouteHop>? {
        if (from == to) return emptyList()
        val universe = PaperTradingEngine.paperUniverse

        data class Node(val parent: String?, val hop: RouteHop?)

        val nodes = HashMap<String, Node>()
        nodes[from] = Node(null, null)
        var frontier = ArrayDeque(listOf(from))
        var found = false
        while (frontier.isNotEmpty() && !found) {
            val next = ArrayDeque<String>()
            for (asset in frontier) {
                for ((ticker, pair) in universe) {
                    val (base, quote) = pair
                    val hop = when (asset) {
                        base -> RouteHop(ticker, asset, quote, sellBase = true)
                        quote -> RouteHop(ticker, asset, base, sellBase = false)
                        else -> null
                    } ?: continue
                    if (hop.to in nodes) continue
                    nodes[hop.to] = Node(asset, hop)
                    if (hop.to == to) {
                        found = true
                        break
                    }
                    next.add(hop.to)
                }
                if (found) break
            }
            frontier = next
        }
        if (!found) return null
        val path = ArrayList<RouteHop>()
        var cursor = to
        while (cursor != from) {
            val node = nodes[cursor] ?: return null
            path.add(node.hop!!)
            cursor = node.parent ?: return null
        }
        path.reverse()
        return path
    }
}
