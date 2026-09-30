package com.hakim3691.bta.core

/**
 * Per-leg exchange-legality checks, run BEFORE any order is submitted.
 *
 * A rejected leg is not a skipped trade: by the time Binance refuses the
 * second leg of a triangle, the first is already filled, and the unwind that
 * follows pays taker fees twice or three times to end up roughly where it
 * started. Anything that can be known to be illegal before submission must
 * therefore stop the whole triangle before leg one.
 *
 * Two checks cover the rejection reasons that dominate in practice:
 *
 *  - LOT_SIZE: the quantity must be a whole multiple of the symbol's
 *    stepSize and at least minQty. The engine's dust truncation only bounds
 *    decimals; it does not make quantities into step multiples.
 *  - NOTIONAL: price x quantity must clear the symbol's minimum notional.
 *    Small legs on cheap assets fail this long before LOT_SIZE does.
 */
object LegalityCheck {

    /** Why a proposed leg would be rejected. */
    sealed class Violation {
        data class LotSize(
            val ticker: String,
            val quantity: Double,
            val stepSize: Double,
            val minQty: Double
        ) : Violation() {
            override fun toString(): String =
                "LOT_SIZE: $ticker quantity $quantity is not a multiple of $stepSize (minQty $minQty)"
        }

        data class Notional(
            val ticker: String,
            val quantity: Double,
            val price: Double,
            val minNotional: Double
        ) : Violation() {
            override fun toString(): String =
                "NOTIONAL: $ticker ${quantity}x$price = ${quantity * price} below minimum $minNotional"
        }

        data class NonPositive(val ticker: String, val quantity: Double) : Violation() {
            override fun toString(): String = "QUANTITY: $ticker quantity $quantity is not positive"
        }
    }

    data class Result(val violations: List<Violation>) {
        val legal: Boolean get() = violations.isEmpty()
    }

    /** Whole multiples of [step] the way Binance validates them (epsilon-tolerant). */
    fun isStepMultiple(quantity: Double, step: Double): Boolean {
        if (step <= 0.0 || !quantity.isFinite()) return false
        val steps = quantity / step
        return kotlin.math.abs(steps - kotlin.math.round(steps)) < 1e-6
    }

    /**
     * Filters for every ticker in the current universe, populated by
     * [com.hakim3691.bta.core.MarketCache.initialize] at scanner start.
     * A ticker with no entry (or no relevant filter) is not checked, because
     * Binance does not enforce what it does not publish.
     */
    val symbolInfo: java.util.concurrent.ConcurrentHashMap<String, SymbolInfo> =
        java.util.concurrent.ConcurrentHashMap()

    fun resetUniverse() = symbolInfo.clear()

    /**
     * Checks every leg of a calculated position against its symbol's filters.
     * Symbols without the relevant filter are skipped - Binance does not
     * enforce what it does not publish.
     */
    fun check(calculated: CalculatedPosition): Result {
        val violations = ArrayList<Violation>(3)
        val trade = calculated.trade

        fun leg(relationship: Relationship, quantity: Double, quotePriceHint: Double?) {
            if (quantity <= 0.0 || !quantity.isFinite()) {
                violations.add(Violation.NonPositive(relationship.ticker, quantity))
                return
            }
            val info = LegalityCheck.symbolInfo[relationship.ticker]
            val lotSize = info?.filters?.firstOrNull { it.filterType == "LOT_SIZE" }
            if (lotSize != null) {
                val step = lotSize.stepSize?.toDoubleOrNull() ?: 0.0
                val minQty = lotSize.minQty?.toDoubleOrNull() ?: 0.0
                if (step > 0.0 && !isStepMultiple(quantity, step)) {
                    violations.add(Violation.LotSize(relationship.ticker, quantity, step, minQty))
                } else if (minQty > 0.0 && quantity + 1e-12 < minQty) {
                    violations.add(Violation.LotSize(relationship.ticker, quantity, step, minQty))
                }
            }
            // Min-notional is validated against the price the leg trades at.
            // The base/quote price implied by the leg itself is the honest
            // one, and the caller's books already contain it.
            val notionalFilter = info?.filters?.firstOrNull {
                it.filterType == "NOTIONAL" || it.filterType == "MIN_NOTIONAL"
            }
            val minNotional = notionalFilter?.minNotional?.toDoubleOrNull() ?: 0.0
            if (minNotional > 0.0) {
                val price = legPrice(trade, relationship, calculated)
                if (price > 0.0 && quantity * price < minNotional - 1e-9) {
                    violations.add(
                        Violation.Notional(relationship.ticker, quantity, price, minNotional)
                    )
                }
            }
        }

        leg(trade.ab, calculated.ab.quantity, null)
        leg(trade.bc, calculated.bc.quantity, null)
        leg(trade.ca, calculated.ca.quantity, null)
        return Result(violations)
    }

    /**
     * The execution price of one leg, derived from what the engine itself
     * computed: spent / earned over the same leg. Returns 0 when it cannot be
     * determined, in which case the notional check is skipped rather than
     * guessed.
     */
    private fun legPrice(trade: Trade, relationship: Relationship, calculated: CalculatedPosition): Double {
        return when (relationship) {
            trade.ab -> if (calculated.ab.quantity > 0.0)
                calculated.a.spent / calculated.ab.quantity else 0.0
            trade.bc -> if (calculated.bc.quantity > 0.0)
                calculated.b.spent / calculated.bc.quantity else 0.0
            trade.ca -> if (calculated.ca.quantity > 0.0)
                calculated.c.spent / calculated.ca.quantity else 0.0
            else -> 0.0
        }
    }
}
