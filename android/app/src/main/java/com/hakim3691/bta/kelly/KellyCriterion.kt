package com.hakim3691.bta.kelly

/**
 * Kelly criterion evaluation for triangular-arbitrage paper trading.
 *
 * Model: the realized net return (percent, after fees) of executing an
 * identified opportunity is treated as approximately normally distributed
 * around the engine's expected percent with standard deviation [sigma].
 *
 *   P(net profit > 0) = Phi(expected / sigma)
 *
 * Kelly fraction uses the generalized two-sided form
 * (win amount b, loss amount a, both expressed as fractions of stake):
 *
 *   f* = (p*b - q*a) / (a*b)
 *
 * with p = P(win), q = 1-p, and a/b estimated from the same normal model:
 * conditional expected gain when winning, conditional expected loss when losing
 * (truncated normal first moments).
 */
object KellyCriterion {

    data class Evaluation(
        /** P(realized net return > 0), 0..1 */
        val probabilityOfProfit: Double,
        /** Kelly-optimal fraction of the allocated budget, 0..1 (already floored at 0) */
        val kellyFraction: Double,
        /** Whether the opportunity passes the configured probability threshold */
        val passesProbabilityThreshold: Boolean,
        /** Whether the opportunity has positive edge under Kelly (f* > 0) */
        val positiveEdge: Boolean,
        /** Whether the whole evaluation passes (threshold + edge) */
        val shouldExecute: Boolean,
        /** Human-readable summary for logs/UI */
        val summary: String
    )

    /**
     * @param expectedPercent engine-expected net profit percent (e.g. 0.25 for +0.25%)
     * @param sigmaPercent standard deviation of realized returns (percent). Must be > 0.
     * @param requiredProbability minimum P(profit) to execute (e.g. 0.80 for 80%)
     * @param maxKellyFraction clamp on the Kelly fraction (e.g. 0.25 = quarter-Kelly at most)
     */
    fun evaluate(
        expectedPercent: Double,
        sigmaPercent: Double,
        requiredProbability: Double,
        maxKellyFraction: Double = 1.0
    ): Evaluation {
        require(sigmaPercent > 0.0) { "sigma must be positive" }
        val z = expectedPercent / sigmaPercent
        val p = normalCdf(z)
        val q = 1.0 - p

        // Conditional moments of the truncated normal (percent units):
        // E[X | X>0] = mu + sigma * phi(z) / (1 - Phi(z))  for the winning side (upper truncation)
        // E[X | X<0] = mu - sigma * phi(z) / Phi(z)        for the losing side (magnitude)
        val phiZ = normalPdf(z)
        val expectedWin = if (p > 1e-9 && (1 - p) > 1e-9) {
            expectedPercent + sigmaPercent * (phiZ / p)
        } else expectedPercent.coerceAtLeast(0.0)
        val expectedLoss = if (p > 1e-9 && q > 1e-9) {
            (expectedPercent - sigmaPercent * (phiZ / q)).coerceAtMost(0.0)
        } else 0.0

        val winB = (expectedWin / 100.0).coerceAtLeast(0.0)      // fraction gained when winning
        val lossA = (-expectedLoss / 100.0).coerceAtLeast(1e-12) // fraction lost when losing (>0)

        val kellyRaw = (p * winB - q * lossA) / (winB * lossA)
        val kelly = kellyRaw.coerceIn(0.0, maxKellyFraction)

        val passesThreshold = p >= requiredProbability
        val positiveEdge = kellyRaw > 0.0
        val should = passesThreshold && positiveEdge

        return Evaluation(
            probabilityOfProfit = p,
            kellyFraction = kelly,
            passesProbabilityThreshold = passesThreshold,
            positiveEdge = positiveEdge,
            shouldExecute = should,
            summary = "P(win)=%.1f%% f*=%.3f z=%.2f".format(p * 100, kelly, z)
        )
    }

    /** Standard normal CDF via Abramowitz-Stegun 7.1.26 erfx approximation (|err| < 1.5e-7). */
    fun normalCdf(x: Double): Double {
        val t = 1.0 / (1.0 + 0.2316419 * kotlin.math.abs(x))
        val poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 +
            t * (-1.821255978 + t * 1.330274429))))
        val pdf = normalPdf(x)
        return if (x >= 0) 1.0 - pdf * poly else pdf * poly
    }

    /** Standard normal PDF. */
    fun normalPdf(x: Double): Double =
        kotlin.math.exp(-0.5 * x * x) / kotlin.math.sqrt(2.0 * Math.PI)
}
