package com.hakim3691.bta.core

/**
 * Port of the `INVESTMENT.[BASE]` configuration object
 * (`{ MIN, MAX, STEP }` in the original `config.json`).
 */
data class InvestmentSpec(
    val base: String,
    val min: Double,
    val max: Double,
    val step: Double
) {
    companion object {
        /**
         * App-level investment configuration, populated from Settings by [ConfigurationStore]
         * and consumed by [CalculationNode.optimize] exactly like the original reads
         * `CONFIG.INVESTMENT[trade.symbol.a]`.
         *
         * Defaults mirror the original config defaults (BTC base).
         */
        val DEFAULTS: MutableMap<String, InvestmentSpec> = mutableMapOf(
            "BTC" to InvestmentSpec("BTC", min = 0.010, max = 0.015, step = 0.005)
        )

        fun configure(specs: Map<String, InvestmentSpec>) {
            DEFAULTS.clear()
            DEFAULTS.putAll(specs)
        }
    }
}
