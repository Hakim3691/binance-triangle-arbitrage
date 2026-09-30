package com.hakim3691.bta.core

/**
 * Pre-filters trades whose legs reference empty or missing order books.
 *
 * Illiquid/delisted-quiet tickers (e.g. some TRY pairs) sync with empty or
 * one-sided books. Feeding them to CalculationNode throws ShallowDepthException
 * for every related trade on every cycle, flooding logs and wasting CPU.
 * Filtering here keeps the engine hot path clean; skipped tickers are
 * reported in aggregate instead of per-exception.
 */
object TradeFilter {

    data class Result(
        /** Trades whose ab/bc/ca books all have at least one level on both sides. */
        val complete: List<Trade>,
        /** Tickers whose books were empty/missing, with how many trades skipped for each. */
        val skippedByTicker: Map<String, Int>,
        val skippedTradeCount: Int
    )

    fun filter(trades: List<Trade>, snapshots: Map<String, DepthSnapshot>): Result {
        val complete = ArrayList<Trade>(trades.size)
        val skipCounter = HashMap<String, Int>()
        var skipped = 0

        for (trade in trades) {
            val legs = listOf(trade.ab.ticker to snapshots[trade.ab.ticker],
                trade.bc.ticker to snapshots[trade.bc.ticker],
                trade.ca.ticker to snapshots[trade.ca.ticker])
            val bad = legs.filter { (_, snap) ->
                snap == null || snap.bids.isEmpty() || snap.asks.isEmpty()
            }
            if (bad.isEmpty()) {
                complete.add(trade)
            } else {
                skipped++
                for ((ticker, _) in bad) {
                    skipCounter[ticker] = (skipCounter[ticker] ?: 0) + 1
                }
            }
        }
        return Result(complete, skipCounter, skipped)
    }
}
