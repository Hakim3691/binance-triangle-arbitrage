package com.hakim3691.bta.core

/**
 * Port of the `calculated` object built by CalculationNode.calculate().
 */
data class LegCalculation(
    var quantity: Double = 0.0,
    var depth: Int = 0
)

data class AssetLedger(
    var spent: Double = 0.0,
    var earned: Double = 0.0,
    var delta: Double = 0.0
)

data class CalculatedPosition(
    val trade: Trade,
    val ab: LegCalculation,
    val bc: LegCalculation,
    val ca: LegCalculation,
    val a: AssetLedger,
    val b: AssetLedger,
    val c: AssetLedger
) {
    /** Same id format as the original: `${a}-${b}-${c}` */
    val id: String get() = trade.id

    var percent: Double = 0.0

    /** The depth snapshot used during calculation (set by the scanner). */
    var usedDepth: CalculationNode.TradeDepthSnapshot? = null
        internal set
}
