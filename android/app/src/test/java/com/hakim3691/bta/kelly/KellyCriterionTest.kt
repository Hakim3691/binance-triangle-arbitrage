package com.hakim3691.bta.kelly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KellyCriterionTest {

    @Test
    fun `normal cdf matches known values`() {
        assertEquals(0.5, KellyCriterion.normalCdf(0.0), 1e-6)
        assertEquals(0.8413, KellyCriterion.normalCdf(1.0), 1e-4)
        assertEquals(0.9772, KellyCriterion.normalCdf(2.0), 1e-4)
        assertEquals(0.1587, KellyCriterion.normalCdf(-1.0), 1e-4)
        assertEquals(0.0228, KellyCriterion.normalCdf(-2.0), 1e-4)
        // symmetry
        assertEquals(1.0 - KellyCriterion.normalCdf(1.5), KellyCriterion.normalCdf(-1.5), 1e-12)
    }

    @Test
    fun `eighty percent probability requires z around 0p84`() {
        // P(win)=0.80 -> z ~= 0.8416
        val eval = KellyCriterion.evaluate(
            expectedPercent = 0.30,
            sigmaPercent = 0.30 / 0.85,   // z = 0.85 -> P = 0.8023
            requiredProbability = 0.80
        )
        assertEquals(0.80, eval.probabilityOfProfit, 5e-3) // A&S CDF approximation error
        assertTrue(eval.passesProbabilityThreshold)
        assertTrue(eval.shouldExecute)
    }

    @Test
    fun `below threshold is rejected`() {
        val eval = KellyCriterion.evaluate(
            expectedPercent = 0.10,
            sigmaPercent = 0.35,      // z = 0.286 -> P ~ 0.61
            requiredProbability = 0.80
        )
        assertFalse(eval.passesProbabilityThreshold)
        assertFalse(eval.shouldExecute)
        assertTrue(eval.probabilityOfProfit < 0.7)
    }

    @Test
    fun `negative expectation never executes`() {
        val eval = KellyCriterion.evaluate(
            expectedPercent = -0.25,
            sigmaPercent = 0.35,
            requiredProbability = 0.80
        )
        assertFalse(eval.shouldExecute)
        assertTrue(eval.probabilityOfProfit < 0.5)
        assertEquals(0.0, eval.kellyFraction, 1e-12)
    }

    @Test
    fun `kelly fraction grows with edge and shrinks with sigma`() {
        // maxKellyFraction raised so raw Kelly magnitudes are comparable (not clamped)
        val lowVol = KellyCriterion.evaluate(0.30, 0.20, 0.5, maxKellyFraction = 1e6)
        val highVol = KellyCriterion.evaluate(0.30, 0.80, 0.5, maxKellyFraction = 1e6)
        assertTrue(lowVol.kellyFraction > highVol.kellyFraction)
        assertTrue(lowVol.probabilityOfProfit > highVol.probabilityOfProfit)
    }

    @Test
    fun `max kelly fraction clamps allocation`() {
        val eval = KellyCriterion.evaluate(0.30, 0.05, 0.5, maxKellyFraction = 0.25)
        assertTrue(eval.kellyFraction <= 0.25 + 1e-12)
    }

    @Test
    fun `tiny sigma gives near-certain win for positive edge`() {
        val eval = KellyCriterion.evaluate(0.30, 0.001, 0.80)
        assertTrue(eval.probabilityOfProfit > 0.999)
        assertTrue(eval.shouldExecute)
    }

    @Test
    fun `zero sigma rejected by require`() {
        try {
            KellyCriterion.evaluate(0.30, 0.0, 0.8)
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
