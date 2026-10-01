package com.hakim3691.bta.core

/**
 * Kotlin port of `src/main/MarketCache.js`.
 *
 * Builds the trading-ticker map from Binance exchangeInfo, discovers every
 * executable A-B-C triangle for the configured investment bases, and maintains
 * the related-ticker index used to fan out websocket updates.
 */
class MarketCache(
    private val whitelist: Set<String> = emptySet(),
    private val executionTemplate: List<String> = listOf("*", "*", "*")
) {

    class Result(
        val tradingSymbols: Map<String, SymbolInfo>,
        val trades: List<Trade>,
        val watching: List<String>,
        val relatedTrades: Map<String, List<Trade>>,
        val relatedTickers: Map<String, Set<String>>,
        val tradingSymbolCount: Int,
        val totalSymbolCountFound: Int,
        /**
         * Assets that a discovered round trip starts and ends in. Derived from
         * the live pair graph, never from configuration: every asset that can
         * root a triangle appears here, so sizing can be derived per asset.
         */
        val bases: Set<String> = emptySet(),
        /**
         * The base asset with the most triangles. Used as the reference asset
         * for tuner readings that need one representative book (fee tier,
         * latency, depth sampling) - not as a restriction on the universe.
         */
        val referenceBase: String? = null
    )

    var result: Result = Result(emptyMap(), emptyList(), emptyList(), emptyMap(), emptyMap(), 0, 0)
        private set

    /**
     * Traversal orders dropped by the last [initialize] call because their
     * mirror was kept. Surfaced in the log so the halving is visible rather
     * than mysterious.
     */
    var resultMirrorsSkipped: Int = 0
        private set

    val trades: List<Trade> get() = result.trades
    val watching: List<String> get() = result.watching
    val tradingSymbols: Map<String, SymbolInfo> get() = result.tradingSymbols

    /**
     * Port of MarketCache.initialize(): parses exchangeInfo symbols, keeps only
     * `TRADING` symbols, computes dustDecimals from LOT_SIZE.minQty, then
     * discovers triangles over the live pair graph.
     *
     * Discovery is graph-based rather than brute force. Each trading pair is an
     * edge between its two assets; a triangle exists exactly where three assets
     * are mutually connected. Enumerating mutual neighbours costs O(V*E) and
     * finds every round trip in the market, where the original triple loop cost
     * O(bases * V^2) and could only ever find triangles that started and ended
     * in a configured base - which is why every opportunity it reported began
     * with BTC. [investmentBases] is therefore no longer a universe: it is an
     * optional restriction (empty means every asset the exchange lists).
     *
     * Both traversal directions of a triangle are kept by default. They are
     * NOT the same trade: A->B->C->A crosses one side of each book and
     * A->C->B->A crosses the other. Working it through, if the mid-price loop
     * return of the first direction is m and the three half-spreads sum to s,
     * the second direction's return is roughly -m - 2s - so at most one
     * direction is ever profitable, and which one depends on the sign of the
     * dislocation. Canonicalizing to the alphabetically-ordered permutation
     * silently drops whichever direction is wrong for the current market,
     * which is half of all profitable opportunities. The switch remains for
     * reproducing the original app's CPU profile, and it costs a recompute
     * cycle rather than an opportunity.
     */
    fun initialize(
        symbols: List<SymbolInfo>,
        investmentBases: Set<String> = emptySet()
    ): Result {
        val trading = symbols.filter { it.isTrading }
        val tradingSymbols = HashMap<String, SymbolInfo>(trading.size)

        for (symbolObj in trading) {
            val lotSize = symbolObj.filters.firstOrNull { it.filterType == "LOT_SIZE" }
            val minQty = lotSize?.minQty ?: "1"
            val dustDecimals = maxOf(minQty.indexOf('1') - 1, 0)
            tradingSymbols[symbolObj.symbol] = symbolObj.copy(dustDecimals = dustDecimals)
        }

        // Adjacency: asset -> every asset it shares a live pair with. Sorted so
        // the enumeration (and therefore the trade list) is deterministic.
        val adjacency = HashMap<String, java.util.TreeSet<String>>()
        for (symbolObj in tradingSymbols.values) {
            adjacency.getOrPut(symbolObj.baseAsset) { java.util.TreeSet() }
                .add(symbolObj.quoteAsset)
            adjacency.getOrPut(symbolObj.quoteAsset) { java.util.TreeSet() }
                .add(symbolObj.baseAsset)
        }

        // Empty restriction = every asset on the exchange. Only an explicit
        // non-empty set narrows the universe.
        val roots: Collection<String> =
            if (investmentBases.isEmpty()) adjacency.keys.sorted() else investmentBases

        val trades = ArrayList<Trade>()
        var skippedMirrors = 0
        for (a in roots) {
            val aNeighbours = adjacency[a] ?: continue
            for (b in aNeighbours) {
                val bNeighbours = adjacency[b] ?: continue
                for (c in bNeighbours) {
                    if (c == a || c == b) continue
                    // Mutual connection is what makes a triangle: without the
                    // c-a pair this is a path, not a round trip.
                    if (c !in aNeighbours) continue
                    // De-duplication is opt-in and default-off: the mirror
                    // trades the opposite side of the same books, so dropping
                    // it drops real opportunities. See the class comment.
                    if (ExecutionConfig.dedupeMirroredTriangles && b > c) {
                        // The mirror (a-c-b) was already considered; count it
                        // only when it is a real trade (whitelist and template
                        // can still reject it), so the number stays exact.
                        if (createTrade(tradingSymbols, a, c, b) != null) {
                            skippedMirrors++
                        }
                        continue
                    }
                    createTrade(tradingSymbols, a, b, c)?.let { trades.add(it) }
                }
            }
        }
        resultMirrorsSkipped = skippedMirrors
        LegalityCheck.resetUniverse()
        LegalityCheck.symbolInfo.putAll(tradingSymbols)

        val watching = LinkedHashSet<String>()
        val relatedTrades = HashMap<String, MutableList<Trade>>()
        val relatedTickers = HashMap<String, MutableSet<String>>()

        for (trade in trades) {
            for (r in listOf(trade.ab, trade.bc, trade.ca)) {
                watching.add(r.ticker)
                relatedTrades.getOrPut(r.ticker) { mutableListOf() }.add(trade)
                for (other in listOf(trade.ab, trade.bc, trade.ca)) {
                    relatedTickers.getOrPut(r.ticker) { mutableSetOf() }.add(other.ticker)
                }
            }
        }

        val tradeBases = trades.mapTo(java.util.TreeSet()) { it.symbol.a }
        val reference = tradeBases.maxByOrNull { base ->
            trades.count { it.symbol.a == base }
        }

        result = Result(
            tradingSymbols = tradingSymbols,
            trades = trades,
            watching = watching.toList(),
            relatedTrades = relatedTrades.mapValues { it.value.toList() },
            relatedTickers = relatedTickers.mapValues { it.value.toSet() },
            tradingSymbolCount = trading.size,
            totalSymbolCountFound = symbols.size,
            bases = tradeBases,
            referenceBase = reference
        )
        return result
    }

    /**
     * Port of `MarketCache.createTrade()`, including whitelist and EXECUTION.TEMPLATE gating.
     */
    private fun createTrade(
        tradingSymbols: Map<String, SymbolInfo>,
        aIn: String,
        bIn: String,
        cIn: String
    ): Trade? {
        val a = aIn.uppercase()
        val b = bIn.uppercase()
        val c = cIn.uppercase()

        if (whitelist.isNotEmpty()) {
            if (a !in whitelist) return null
            if (b !in whitelist) return null
            if (c !in whitelist) return null
        }

        val ab = getRelationship(tradingSymbols, a, b) ?: return null
        if (executionTemplate[0] != "*" && executionTemplate[0] != ab.method) return null

        val bc = getRelationship(tradingSymbols, b, c) ?: return null
        if (executionTemplate[1] != "*" && executionTemplate[1] != bc.method) return null

        val ca = getRelationship(tradingSymbols, c, a) ?: return null
        if (executionTemplate[2] != "*" && executionTemplate[2] != ca.method) return null

        return Trade(ab, bc, ca, TradeSymbols(a, b, c))
    }

    /**
     * Port of `MarketCache.getRelationship()`.
     */
    fun getRelationship(tradingSymbols: Map<String, SymbolInfo>, a: String, b: String): Relationship? {
        tradingSymbols[a + b]?.let {
            return Relationship(
                method = Relationship.SELL,
                ticker = a + b,
                base = a,
                quote = b,
                dustDecimals = it.dustDecimals
            )
        }
        tradingSymbols[b + a]?.let {
            return Relationship(
                method = Relationship.BUY,
                ticker = b + a,
                base = b,
                quote = a,
                dustDecimals = it.dustDecimals
            )
        }
        return null
    }
}
