package com.hakim3691.bta.core

/**
 * Port of `MarketCache.getRelationship()` return value.
 *
 * For assets A and B:
 *  - If ticker `AB` exists, holding A means you SELL on AB to receive B.
 *  - If ticker `BA` exists, holding A means you BUY on BA (paying with B) to receive A.
 */
data class Relationship(
    val method: String,      // "BUY" or "SELL"
    val ticker: String,      // e.g. "ETHBTC"
    val base: String,        // base asset of the ticker
    val quote: String,       // quote asset of the ticker
    val dustDecimals: Int
) {
    companion object {
        const val BUY = "BUY"
        const val SELL = "SELL"
    }
}

/**
 * Port of `MarketCache.createTrade()` return value: a triangular trade A -> B -> C -> A.
 */
data class Trade(
    val ab: Relationship,
    val bc: Relationship,
    val ca: Relationship,
    val symbol: TradeSymbols
) {
    val id: String get() = "${symbol.a}-${symbol.b}-${symbol.c}"
}

data class TradeSymbols(
    val a: String,
    val b: String,
    val c: String
)
