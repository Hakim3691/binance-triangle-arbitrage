package com.hakim3691.bta.core

import kotlinx.serialization.Serializable

/**
 * Port of the symbol objects extracted from Binance exchangeInfo in `MarketCache.initialize()`.
 * Only the fields the original implementation actually consumes are retained.
 */
@Serializable
data class SymbolFilter(
    val filterType: String,
    val minQty: String? = null,
    val stepSize: String? = null,
    val tickSize: String? = null,
    val minNotional: String? = null
)

@Serializable
data class SymbolInfo(
    val symbol: String,
    val status: String,
    val baseAsset: String,
    val quoteAsset: String,
    val filters: List<SymbolFilter> = emptyList(),
    /** Computed by the original: digits of precision derived from the LOT_SIZE minQty filter. */
    val dustDecimals: Int = 0,
    /**
     * Effective spot taker commission published by `standardCommission`, as a
     * fraction of notional (0.001 == 0.10%). Null when the symbol omits it,
     * which is how older Binance payloads and test fixtures behave.
     */
    val takerCommission: Double? = null
) {
    val isTrading: Boolean get() = status == "TRADING"

    /** LOT_SIZE stepSize, or null when the symbol has no LOT_SIZE filter. */
    val lotStep: Double? get() = filters.firstOrNull { it.filterType == "LOT_SIZE" }?.stepSize?.toDoubleOrNull()

    /** LOT_SIZE minQty, or null when the symbol has no LOT_SIZE filter. */
    val lotMinQty: Double? get() = filters.firstOrNull { it.filterType == "LOT_SIZE" }?.minQty?.toDoubleOrNull()
}
