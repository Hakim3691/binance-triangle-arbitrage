package com.hakim3691.bta.core

/**
 * Abstraction over market-order placement and depth access.
 * Implemented by the paper-trading simulator and the live Binance REST client.
 */
interface TradeExecutor {
    /**
     * Places a market order and returns the fill response.
     * A response with `orderId == null` represents a rejected/failed order
     * (equivalent to the original checking `results.orderId` after a caught API error).
     */
    suspend fun placeMarketOrder(ticker: String, quantity: Double, method: String): OrderResponse

    /** Sorted, bounded depth for a ticker (used by linear-strategy leg recalculation). */
    fun getSortedDepth(ticker: String): DepthSnapshot
}

/** Port of the fields consumed from the node-binance-api order response. */
data class OrderResponse(
    val orderId: Long?,
    val executedQty: Double,
    val cummulativeQuoteQty: Double,
    val fills: List<OrderFill>
) {
    companion object {
        fun failed(): OrderResponse = OrderResponse(null, 0.0, 0.0, emptyList())
    }
}

data class OrderFill(
    val price: Double,
    val qty: Double,
    val commission: Double,
    val commissionAsset: String
)
