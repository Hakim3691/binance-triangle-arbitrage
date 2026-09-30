package com.hakim3691.bta.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-progress claim must be atomic with the decision to execute.
 *
 * The scan callback runs on whatever dispatcher delivered the depth update
 * while the execution itself runs on another, so registering "work in
 * progress" at the top of the execution function left a window in which two
 * updates could both pass the nothing-in-progress check and run two
 * triangles concurrently. [ArbitrageExecution.tryBegin] closes it.
 */
class TryBeginTest {

    private fun position(id: String, a: String = "BTC", b: String = "ETH", c: String = "BNB"): CalculatedPosition {
        val trade = Trade(
            ab = Relationship(Relationship.BUY, a + b, b, a, 4),
            bc = Relationship(Relationship.BUY, b + c, c, b, 2),
            ca = Relationship(Relationship.SELL, a + c, c, a, 2),
            symbol = TradeSymbols(a, b, c)
        )
        return CalculatedPosition(
            trade, LegCalculation(), LegCalculation(), LegCalculation(),
            AssetLedger(), AssetLedger(), AssetLedger()
        )
    }

    @Test
    fun `a claimed triangle blocks a second claim`() {
        val exec = ArbitrageExecution(object : TradeExecutor {
            override suspend fun placeMarketOrder(ticker: String, quantity: Double, method: String) =
                OrderResponse(1, quantity, quantity, emptyList())
            override fun getSortedDepth(ticker: String) = DepthSnapshot.EMPTY
        })
        assertTrue(exec.tryBegin(position("T1")))
        assertFalse("second triangle must not start while the first is claimed", exec.tryBegin(position("T2")))
        assertEquals(1, exec.inProgressIds.size)
    }

    @Test
    fun `any shared symbol blocks the claim`() {
        val exec = ArbitrageExecution(object : TradeExecutor {
            override suspend fun placeMarketOrder(ticker: String, quantity: Double, method: String) =
                OrderResponse(1, quantity, quantity, emptyList())
            override fun getSortedDepth(ticker: String) = DepthSnapshot.EMPTY
        })
        // Same three assets, different pairings (the mirror shares all three).
        val trade2 = Trade(
            ab = Relationship(Relationship.BUY, "ETHBTC", "ETH", "BTC", 4),
            bc = Relationship(Relationship.BUY, "BNBBTC", "BNB", "BTC", 2),
            ca = Relationship(Relationship.SELL, "BNBETH", "BNB", "ETH", 2),
            symbol = TradeSymbols("BTC", "BNB", "ETH")
        )
        val p1 = position("T1")
        val p2 = CalculatedPosition(
            trade2, LegCalculation(), LegCalculation(), LegCalculation(),
            AssetLedger(), AssetLedger(), AssetLedger()
        )
        assertTrue(exec.tryBegin(p1))
        assertFalse(exec.tryBegin(p2))
    }

    @Test
    fun `the cap is enforced at claim time`() {
        val exec = ArbitrageExecution(object : TradeExecutor {
            override suspend fun placeMarketOrder(ticker: String, quantity: Double, method: String) =
                OrderResponse(1, quantity, quantity, emptyList())
            override fun getSortedDepth(ticker: String) = DepthSnapshot.EMPTY
        })
        val before = ExecutionConfig.cap
        try {
            ExecutionConfig.cap = 1
            exec.attemptedPositions[1L] = "OLD"
            assertFalse(exec.tryBegin(position("T1")))
        } finally {
            ExecutionConfig.cap = before
        }
    }
}
