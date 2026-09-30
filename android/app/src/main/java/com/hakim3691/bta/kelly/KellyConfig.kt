package com.hakim3691.bta.kelly

/**
 * Configuration for Kelly-gated paper auto-execution. Persisted via
 * ConfigurationStore; applied to the engine globals so
 * [com.hakim3691.bta.kelly.KellyPaperTrader] reads a single source of truth.
 *
 * Design note: the Kelly machinery here is a *gate*, not a sizing model.
 * The criterion assumes independent repeated bets with a stationary,
 * correctly-estimated edge distribution - neither holds for this engine.
 * The expected percent is a projection made on the same book that will
 * consume the order, so projection error is correlated with outcome rather
 * than independent noise, and the empirical sigma mixes triangle risk with
 * the funding leg's round-trip cost. The P(win) threshold is a fine
 * conservative filter; the fraction output is not trusted for sizing, which
 * is why sizing is a plain fixed fraction of the budget
 * ([investmentFractionOfKelly] of it, clamped).
 */
object KellyConfig {
    /** Auto-execute Kelly-qualified paper opportunities. */
    @Volatile var enabled: Boolean = true

    /** Paper trading budget in USDT. */
    @Volatile var budgetUsdt: Double = 100.0

    /** Minimum P(net profit) required to execute (0..1). Default 80%. */
    @Volatile var requiredProbability: Double = 0.80

    /** Clamp on Kelly fraction (fraction of qualifying budget per trade). */
    @Volatile var maxKellyFraction: Double = 0.5

    /** Hard cap on a single trade's notional as a fraction of the current budget. */
    @Volatile var maxAllocationPerTrade: Double = 0.25

    /**
     * Triangle investment as a fixed fraction of the budget.
     * E.g. 0.10 = invest 10% of the budget per trade. Fixed-fractional
     * sizing, not Kelly-fraction sizing: see the class note for why the
     * fraction the criterion computes is not trusted with position size.
     */
    @Volatile var investmentFractionOfKelly: Double = 0.10

    /**
     * Minimum viable trade size in USDT. Viable trades invest
     * max(investmentFractionOfKelly * budget, minTradeUsdt).
     */
    @Volatile var minTradeUsdt: Double = 5.0

    /** Volatility assumption (percent) used until enough paper outcomes exist. */
    @Volatile var defaultSigmaPercent: Double = 0.35

    /** Number of recorded outcomes before empirical sigma replaces [defaultSigmaPercent]. */
    @Volatile var sigmaSampleThreshold: Int = 12

    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (budgetUsdt <= 0) errors.add("Paper budget (USDT) must be positive")
        if (requiredProbability !in 0.0..1.0) errors.add("Required probability must be between 0 and 1")
        if (maxKellyFraction <= 0 || maxKellyFraction > 1) errors.add("Kelly fraction clamp must be in (0, 1]")
        if (maxAllocationPerTrade <= 0 || maxAllocationPerTrade > 1) errors.add("Max allocation per trade must be in (0, 1]")
        if (investmentFractionOfKelly <= 0 || investmentFractionOfKelly > 1) errors.add("Investment fraction of Kelly must be in (0, 1]")
        if (minTradeUsdt <= 0) errors.add("Minimum trade amount must be positive")
        if (defaultSigmaPercent <= 0) errors.add("Default volatility must be positive")
        return errors
    }
}
