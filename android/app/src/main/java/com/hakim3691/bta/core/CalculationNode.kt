package com.hakim3691.bta.core

/**
 * Kotlin port of `src/main/CalculationNode.js`.
 *
 * All arithmetic is deliberately structured to be floating-point-exact with the original:
 *  - `amountFrom -= quantity` / `amountFrom -= exchangeableAmount` for partial fills
 *  - same ternary placement (subtract first, then add the remainder term)
 *  - [calculateDustless] mirrors the original's `toFixed(12)` truncation semantics
 */
object CalculationNode {

    /** Result of an order-book walk in one direction. `depth` is the 1-based number of levels consumed. */
    data class ConversionResult(val value: Double, val depth: Int)

    /** Port of the `{ ab, bc, ca }` depth snapshot map used during analysis. */
    data class TradeDepthSnapshot(
        val ab: DepthSnapshot,
        val bc: DepthSnapshot,
        val ca: DepthSnapshot
    )

    // ------------------------------------------------------------------
    // analyze() - evaluates every trade related to a ticker update
    // ------------------------------------------------------------------

    fun analyze(
        trades: List<Trade>,
        depthCacheClone: Map<String, DepthSnapshot>,
        errorCallback: (String) -> Unit,
        executionCheckCallback: (CalculatedPosition) -> Boolean,
        executionCallback: (CalculatedPosition) -> Unit,
        hudEnabled: Boolean = true
    ): Map<String, CalculatedPosition> {
        val results = LinkedHashMap<String, CalculatedPosition>()
        for (trade in trades) {
            try {
                val snapshot = TradeDepthSnapshot(
                    ab = depthCacheClone[trade.ab.ticker] ?: DepthSnapshot.EMPTY,
                    bc = depthCacheClone[trade.bc.ticker] ?: DepthSnapshot.EMPTY,
                    ca = depthCacheClone[trade.ca.ticker] ?: DepthSnapshot.EMPTY
                )
                val calculated = optimize(trade, snapshot)
                if (hudEnabled) results[calculated.id] = calculated
                if (executionCheckCallback(calculated)) {
                    executionCallback(calculated)
                    break
                }
            } catch (e: Exception) {
                errorCallback(e.message ?: e.toString())
            }
        }
        return results
    }

    // ------------------------------------------------------------------
    // optimize() - finds the most profitable investment quantity
    // ------------------------------------------------------------------

    fun optimize(trade: Trade, depthSnapshot: TradeDepthSnapshot): CalculatedPosition {
        val base = trade.symbol.a
        val spec = InvestmentSpec.DEFAULTS[base]
            ?: throw IllegalArgumentException("No investment configuration for base asset $base")
        var bestCalculation: CalculatedPosition? = null
        var quantity = spec.min
        while (quantity <= spec.max) {
            val calculation = calculate(quantity, trade, depthSnapshot)
            if (bestCalculation == null || calculation.percent > bestCalculation.percent) {
                bestCalculation = calculation
            }
            quantity += spec.step
        }
        bestCalculation?.usedDepth = depthSnapshot
        return bestCalculation ?: throw IllegalArgumentException(
            "Investment step configuration for $base produced no calculations"
        )
    }

    // ------------------------------------------------------------------
    // calculate() - evaluates one triangle at a specific investment amount
    // ------------------------------------------------------------------

    fun calculate(investmentA: Double, trade: Trade, depthSnapshot: TradeDepthSnapshot): CalculatedPosition {
        val calculated = CalculatedPosition(
            trade = trade,
            ab = LegCalculation(),
            bc = LegCalculation(),
            ca = LegCalculation(),
            a = AssetLedger(),
            b = AssetLedger(),
            c = AssetLedger()
        )
        val s = trade.symbol

        if (trade.ab.method == Relationship.BUY) {
            // Buying BA: invest A, earn B
            val dustedB = orderBookConversion(investmentA, s.a, s.b, trade.ab.ticker, depthSnapshot.ab)
            calculated.b.earned = calculateDustless(dustedB.value, trade.ab.dustDecimals)
            calculated.ab.quantity = calculated.b.earned
            val reverse = orderBookReverseConversion(calculated.b.earned, s.b, s.a, trade.ab.ticker, depthSnapshot.ab)
            calculated.a.spent = reverse.value
            calculated.ab.depth = reverse.depth
        } else {
            // Selling AB
            calculated.a.spent = calculateDustless(investmentA, trade.ab.dustDecimals)
            calculated.ab.quantity = calculated.a.spent
            val conv = orderBookConversion(calculated.a.spent, s.a, s.b, trade.ab.ticker, depthSnapshot.ab)
            calculated.b.earned = conv.value
            calculated.ab.depth = conv.depth
        }

        if (trade.bc.method == Relationship.BUY) {
            // Buying CB: spend B, earn C
            val dustedC = orderBookConversion(calculated.b.earned, s.b, s.c, trade.bc.ticker, depthSnapshot.bc)
            calculated.c.earned = calculateDustless(dustedC.value, trade.bc.dustDecimals)
            calculated.bc.quantity = calculated.c.earned
            val reverse = orderBookReverseConversion(calculated.c.earned, s.c, s.b, trade.bc.ticker, depthSnapshot.bc)
            calculated.b.spent = reverse.value
            calculated.bc.depth = reverse.depth
        } else {
            // Selling BC
            calculated.b.spent = calculateDustless(calculated.b.earned, trade.bc.dustDecimals)
            calculated.bc.quantity = calculated.b.spent
            val conv = orderBookConversion(calculated.b.spent, s.b, s.c, trade.bc.ticker, depthSnapshot.bc)
            calculated.c.earned = conv.value
            calculated.bc.depth = conv.depth
        }

        if (trade.ca.method == Relationship.BUY) {
            // Buying AC: spend C, earn A
            val dustedA = orderBookConversion(calculated.c.earned, s.c, s.a, trade.ca.ticker, depthSnapshot.ca)
            calculated.a.earned = calculateDustless(dustedA.value, trade.ca.dustDecimals)
            calculated.ca.quantity = calculated.a.earned
            val reverse = orderBookReverseConversion(calculated.a.earned, s.a, s.c, trade.ca.ticker, depthSnapshot.ca)
            calculated.c.spent = reverse.value
            calculated.ca.depth = reverse.depth
        } else {
            // Selling CA
            calculated.c.spent = calculateDustless(calculated.c.earned, trade.ca.dustDecimals)
            calculated.ca.quantity = calculated.c.spent
            val conv = orderBookConversion(calculated.c.spent, s.c, s.a, trade.ca.ticker, depthSnapshot.ca)
            calculated.a.earned = conv.value
            calculated.ca.depth = conv.depth
        }

        // Deltas
        calculated.a.delta = calculated.a.earned - calculated.a.spent
        calculated.b.delta = calculated.b.earned - calculated.b.spent
        calculated.c.delta = calculated.c.earned - calculated.c.spent

        calculated.percent = (calculated.a.delta / calculated.a.spent * 100) - (ExecutionConfig.feePercent * 3)
        // JS truthiness check `if (!calculated.percent)`: catches 0, -0 and NaN
        if (calculated.percent == 0.0 || calculated.percent.isNaN()) calculated.percent = -100.0

        return calculated
    }

    // ------------------------------------------------------------------
    // Projection helpers - used by PreFlightGuard to re-price a triangle
    // against books that are newer than the ones it was decided on.
    // ------------------------------------------------------------------

    /** What one leg would cost and yield if executed now against [depth]. */
    data class LegProjection(val spent: Double, val earned: Double)

    /**
     * Walks a single leg for [amountIn] of the input asset, using exactly the
     * same book traversal as [calculate] so a projection cannot disagree with
     * the engine's own arithmetic.
     *
     * BUY spends the quote asset and earns the base asset; SELL spends the base
     * asset and earns the quote asset.
     */
    fun projectLeg(
        relationship: Relationship,
        amountIn: Double,
        depth: DepthSnapshot
    ): LegProjection {
        return if (relationship.method == Relationship.BUY) {
            val conv = orderBookConversion(
                amountIn, relationship.quote, relationship.base, relationship.ticker, depth
            )
            val baseEarned = calculateDustless(conv.value, relationship.dustDecimals)
            val reverse = orderBookReverseConversion(
                baseEarned, relationship.base, relationship.quote, relationship.ticker, depth
            )
            LegProjection(spent = reverse.value, earned = baseEarned)
        } else {
            val dusted = calculateDustless(amountIn, relationship.dustDecimals)
            val conv = orderBookConversion(
                dusted, relationship.base, relationship.quote, relationship.ticker, depth
            )
            LegProjection(spent = dusted, earned = conv.value)
        }
    }

    /**
     * Percent the triangle would realize if the base asset were sold straight
     * back at [aEarned] after being spent at [aSpent].
     *
     * Mirrors the percent formula and its JS truthiness quirk in [calculate], so
     * a projected 0 or NaN reads as -100 rather than sneaking past a gate.
     */
    fun projectedPercent(aEarned: Double, aSpent: Double): Double {
        if (aSpent <= 0.0 || !aSpent.isFinite()) return -100.0
        val percent = ((aEarned - aSpent) / aSpent * 100) - (ExecutionConfig.feePercent * 3)
        return if (percent == 0.0 || percent.isNaN()) -100.0 else percent
    }

    // ------------------------------------------------------------------
    // recalculateTradeLeg() - used by the linear execution strategy
    // ------------------------------------------------------------------

    fun recalculateTradeLeg(
        relationship: Relationship,
        quantityEarned: Double,
        depthSnapshot: DepthSnapshot
    ): Double {
        return if (relationship.method == Relationship.BUY) {
            val dusted = orderBookConversion(
                quantityEarned,
                relationship.quote,
                relationship.base,
                relationship.ticker,
                depthSnapshot
            )
            calculateDustless(dusted.value, relationship.dustDecimals)
        } else {
            calculateDustless(quantityEarned, relationship.dustDecimals)
        }
    }


    /**
     * Formats a Double the way JavaScript's default number-to-string does for
     * whole values (98.0 -> "98"), used inside error messages so messages are
     * byte-identical with the original implementation.
     */
    internal fun jsNum(value: Double): String =
        if (value == kotlin.math.floor(value) && !value.isInfinite() && kotlin.math.abs(value) < 1e21) {
            value.toLong().toString()
        } else {
            value.toString()
        }

    // ------------------------------------------------------------------
    // orderBookConversion() - walking the book in trading direction
    // ------------------------------------------------------------------

    fun orderBookConversion(
        amountFromIn: Double,
        symbolFrom: String,
        symbolTo: String,
        ticker: String,
        depthSnapshot: DepthSnapshot
    ): ConversionResult {
        if (amountFromIn == 0.0) return ConversionResult(0.0, 0)

        var amountFrom = amountFromIn
        var amountTo = 0.0

        if (ticker == symbolFrom + symbolTo) {
            // Selling symbolFrom on its base market: consume bids best-first
            val bidRates = depthSnapshot.bids.keys.toList()
            for (i in bidRates.indices) {
                val rate = bidRates[i]
                val quantity = depthSnapshot.bids[bidRates[i]] ?: 0.0
                val exchangeableAmount = quantity * rate
                if (quantity < amountFrom) {
                    amountFrom -= quantity
                    amountTo += exchangeableAmount
                } else {
                    // Last fill
                    return ConversionResult(amountTo + (amountFrom * rate), i + 1)
                }
            }
            throw ShallowDepthException(
                "Bid depth (${bidRates.size}) too shallow to convert ${jsNum(amountFrom)} $symbolFrom to $symbolTo using $ticker"
            )
        } else {
            // Buying symbolFrom quoted in symbolTo: consume asks best-first
            val askRates = depthSnapshot.asks.keys.toList()
            for (i in askRates.indices) {
                val rate = askRates[i]
                val quantity = depthSnapshot.asks[askRates[i]] ?: 0.0
                val exchangeableAmount = quantity * rate
                if (exchangeableAmount < amountFrom) {
                    amountFrom -= exchangeableAmount
                    amountTo += quantity
                } else {
                    // Last fill
                    return ConversionResult(amountTo + (amountFrom / rate), i + 1)
                }
            }
            throw ShallowDepthException(
                "Ask depth (${askRates.size}) too shallow to convert ${jsNum(amountFrom)} $symbolFrom to $symbolTo using $ticker"
            )
        }
    }

    // ------------------------------------------------------------------
    // orderBookReverseConversion() - inverse walk used to express "spent"
    // ------------------------------------------------------------------

    fun orderBookReverseConversion(
        amountFromIn: Double,
        symbolFrom: String,
        symbolTo: String,
        ticker: String,
        depthSnapshot: DepthSnapshot
    ): ConversionResult {
        if (amountFromIn == 0.0) return ConversionResult(0.0, 0)

        var amountFrom = amountFromIn
        var amountTo = 0.0

        if (ticker == symbolFrom + symbolTo) {
            val askRates = depthSnapshot.asks.keys.toList()
            for (i in askRates.indices) {
                val rate = askRates[i]
                val quantity = depthSnapshot.asks[askRates[i]] ?: 0.0
                val exchangeableAmount = quantity * rate
                if (quantity < amountFrom) {
                    amountFrom -= quantity
                    amountTo += exchangeableAmount
                } else {
                    return ConversionResult(amountTo + (amountFrom * rate), i + 1)
                }
            }
            throw ShallowDepthException(
                "Ask depth (${askRates.size}) too shallow to reverse convert ${jsNum(amountFrom)} $symbolFrom to $symbolTo using $ticker"
            )
        } else {
            val bidRates = depthSnapshot.bids.keys.toList()
            for (i in bidRates.indices) {
                val rate = bidRates[i]
                val quantity = depthSnapshot.bids[bidRates[i]] ?: 0.0
                val exchangeableAmount = quantity * rate
                if (exchangeableAmount < amountFrom) {
                    amountFrom -= exchangeableAmount
                    amountTo += quantity
                } else {
                    return ConversionResult(amountTo + (amountFrom / rate), i + 1)
                }
            }
            throw ShallowDepthException(
                "Bid depth (${bidRates.size}) too shallow to reverse convert ${jsNum(amountFrom)} $symbolFrom to $symbolTo using $ticker"
            )
        }
    }

    // ------------------------------------------------------------------
    // getOrderBookDepthRequirement() - levels needed to fill a quantity
    // ------------------------------------------------------------------

    fun getOrderBookDepthRequirement(
        method: String,
        quantity: Double,
        depthSnapshot: DepthSnapshot
    ): Int {
        var i = 0
        var exchanged = 0.0

        if (method == Relationship.SELL) {
            val bidRates = depthSnapshot.bids.keys.toList()
            while (i < bidRates.size) {
                exchanged += depthSnapshot.bids[bidRates[i]] ?: 0.0
                if (exchanged >= quantity) return i + 1
                i++
            }
        } else if (method == Relationship.BUY) {
            val askRates = depthSnapshot.asks.keys.toList()
            while (i < askRates.size) {
                exchanged += depthSnapshot.asks[askRates[i]] ?: 0.0
                if (exchanged >= quantity) return i + 1
                i++
            }
        } else {
            throw IllegalArgumentException("Unknown method: $method")
        }
        // After a full walk, i equals the number of levels (matches the original's post-loop i)
        return i
    }

    // ------------------------------------------------------------------
    // calculateDustless() - truncates to the symbol's LOT_SIZE precision
    // ------------------------------------------------------------------

    /**
     * Mirrors the original exactly:
     *  - integers pass through unchanged
     *  - otherwise the value is rendered with 12 decimals and truncated to
     *    dustDecimals + 1 characters after the decimal point, then parsed back.
     * JavaScript's Number.toFixed rounds half-away-from-zero at digit 12;
     * [String.format] rounds half-up in the same way for the values seen in practice.
     */
    fun calculateDustless(amount: Double, dustDecimals: Int): Double {
        if (amount == kotlin.math.floor(amount) && !amount.isInfinite()) return amount
        val amountString = String.format("%.12f", amount)
        val decimalIndex = amountString.indexOf('.')
        return amountString.substring(0, decimalIndex + dustDecimals + 1).toDouble()
    }
}
