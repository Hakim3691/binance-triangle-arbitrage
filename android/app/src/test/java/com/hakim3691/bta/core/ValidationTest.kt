package com.hakim3691.bta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of Validation.js checks with the same user-facing messages. */
class ValidationTest {

    private fun validSpecs(): Map<String, InvestmentSpec> =
        mapOf("BTC" to InvestmentSpec("BTC", 0.005, 0.015, 0.005))

    @Test
    fun `valid configuration passes`() {
        ExecutionConfig.scanningDepth = 50
        ExecutionConfig.feePercent = 0.10
        ExecutionConfig.strategy = "linear"
        ExecutionConfig.ageThresholdMs = 25
        ExecutionConfig.cap = 1
        assertTrue(ExecutionConfig.validate(validSpecs()).isEmpty())
    }

    @Test
    fun `negative or zero investments are rejected`() {
        val errors = ExecutionConfig.validate(mapOf("BTC" to InvestmentSpec("BTC", 0.0, 0.015, 0.005)))
        assertTrue(errors.any { it.contains("INVESTMENT.BTC.MIN") })
    }

    @Test
    fun `min greater than max is rejected`() {
        val errors = ExecutionConfig.validate(mapOf("BTC" to InvestmentSpec("BTC", 0.02, 0.015, 0.005)))
        assertTrue(errors.any { it.contains("cannot be greater than maximum") })
    }

    @Test
    fun `oversized step is warned via error list`() {
        val errors = ExecutionConfig.validate(mapOf("BTC" to InvestmentSpec("BTC", 0.005, 0.015, 0.05)))
        assertTrue(errors.any { it.contains("Step size") })
    }

    @Test
    fun `depth bounds enforced`() {
        ExecutionConfig.scanningDepth = 5001
        assertTrue(ExecutionConfig.validate(validSpecs()).any { it.contains("SCANNING.DEPTH") })
        ExecutionConfig.scanningDepth = 0
        assertTrue(ExecutionConfig.validate(validSpecs()).any { it.contains("SCANNING.DEPTH") })
        ExecutionConfig.scanningDepth = 50
    }

    @Test
    fun `invalid strategy rejected`() {
        ExecutionConfig.strategy = "diagonal"
        assertTrue(ExecutionConfig.validate(validSpecs()).any { it.contains("linear, parallel") })
        ExecutionConfig.strategy = "linear"
    }
}
