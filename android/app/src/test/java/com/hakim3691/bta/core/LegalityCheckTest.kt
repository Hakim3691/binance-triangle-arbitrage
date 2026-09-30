package com.hakim3691.bta.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pre-submission exchange-legality checks.
 *
 * A rejected leg is not a skipped trade: the legs before it are already
 * filled, and unwinding them costs taker fees two or three times. These
 * tests pin the two rejection reasons that dominate in practice - quantities
 * off the LOT_SIZE grid and legs under the notional minimum - and the
 * guarantee that both stop the triangle before leg one.
 */
class LegalityCheckTest {

    private lateinit var trade: Trade
    private lateinit var position: CalculatedPosition

    @Before
    fun setUp() {
        ExecutionConfig.dedupeMirroredTriangles = false
        LegalityCheck.symbolInfo.clear()
        trade = MarketCache().initialize(symbols(), setOf("BTC"))
            .trades.first { it.id == "BTC-ETH-BNB" }
        position = CalculatedPosition(
            trade = trade,
            ab = LegCalculation(quantity = 1.5),
            bc = LegCalculation(quantity = 10.0),
            ca = LegCalculation(quantity = 2.0),
            a = AssetLedger(spent = 1.0),
            b = AssetLedger(spent = 9.0),
            c = AssetLedger(spent = 3.0)
        )
    }

    @After
    fun tearDown() {
        LegalityCheck.symbolInfo.clear()
        ExecutionConfig.dedupeMirroredTriangles = true
    }

    /** BNBBTC step 0.01 / minQty 0.1 / minNotional 5; ETHBTC and BNBETH unfiltered. */
    private fun symbols() = listOf(
        SymbolInfo("ETHBTC", "TRADING", "ETH", "BTC", listOf(SymbolFilter("LOT_SIZE", minQty = "0.00001", stepSize = "0.00001"))),
        SymbolInfo("BNBETH", "TRADING", "BNB", "ETH", listOf(SymbolFilter("LOT_SIZE", minQty = "0.01000000", stepSize = "0.01"))),
        SymbolInfo(
            "BNBBTC", "TRADING", "BNB", "BTC", listOf(
                SymbolFilter("LOT_SIZE", minQty = "0.10000000", stepSize = "0.01000000"),
                SymbolFilter("NOTIONAL", minNotional = "5.0")
            )
        )
    )

    @Test
    fun `a legal position passes untouched`() {
        // CA leg sells BNBBTC 2.0: multiple of 0.01, above 0.1, notional 2x1.5=3... 
        // c.spent/qty sets the price, so 3.0/2.0 = 1.5 -> 2.0 x 1.5 = 3.0 < 5 would fail.
        // Give c.spent a notional-clearing value instead.
        position.c.spent = 6.0 // price 3.0, notional 6.0
        val result = LegalityCheck.check(position)
        assertTrue(result.violations.toString(), result.legal)
    }

    @Test
    fun `a quantity off the step grid is flagged`() {
        position.ca.quantity = 2.005 // not a multiple of 0.01
        val result = LegalityCheck.check(position)
        assertFalse(result.legal)
        val v = result.violations.filterIsInstance<LegalityCheck.Violation.LotSize>().single()
        assertEquals("BNBBTC", v.ticker)
        assertEquals(0.01, v.stepSize, 1e-12)
    }

    @Test
    fun `a quantity below minQty is flagged`() {
        position.ca.quantity = 0.05 // multiple of 0.01 but below 0.1
        val result = LegalityCheck.check(position)
        assertFalse(result.legal)
        assertTrue(result.violations.any { it is LegalityCheck.Violation.LotSize })
    }

    @Test
    fun `a leg under the notional minimum is flagged`() {
        position.c.spent = 6.0
        position.ca.quantity = 1.0 // price 6.0, notional 6.0 -> fine
        position.c.spent = 3.0 // price 3.0, notional 3.0 < 5
        val result = LegalityCheck.check(position)
        assertFalse(result.legal)
        val v = result.violations.filterIsInstance<LegalityCheck.Violation.Notional>().single()
        assertEquals("BNBBTC", v.ticker)
        assertEquals(5.0, v.minNotional, 1e-9)
    }

    @Test
    fun `symbols without filters are not enforced`() {
        // ETHBTC and BNBETH carry only LOT_SIZE with wide bounds; position legs
        // 1.5 and 10.0 are legal there even though the test never set prices.
        position.c.spent = 6.0
        val result = LegalityCheck.check(position)
        assertTrue(result.violations.toString(), result.legal)
    }

    @Test
    fun `unregistered tickers are not enforced`() {
        LegalityCheck.symbolInfo.clear()
        position.ca.quantity = 2.005
        assertTrue(LegalityCheck.check(position).legal)
    }

    @Test
    fun `non-positive quantities are flagged`() {
        position.bc.quantity = 0.0
        val result = LegalityCheck.check(position)
        assertFalse(result.legal)
        assertTrue(result.violations.any { it is LegalityCheck.Violation.NonPositive })
    }

    @Test
    fun `epsilon-close steps are accepted`() {
        position.ca.quantity = 2.0000000001 // float noise over a whole step
        position.c.spent = 6.0
        assertTrue(LegalityCheck.check(position).legal)
    }

    @Test
    fun `an illegal position is rejected by the execution gate`() = kotlinx.coroutines.test.runTest {
        position.c.spent = 6.0
        position.ca.quantity = 2.005
        val exec = ArbitrageExecution(object : TradeExecutor {
            override suspend fun placeMarketOrder(ticker: String, quantity: Double, method: String) =
                OrderResponse(1, quantity, quantity, emptyList())
            override fun getSortedDepth(ticker: String) = DepthSnapshot.EMPTY
        })
        // percent gate set high enough to pass; only legality may reject it
        position.percent = 1.0
        assertFalse(exec.isSafeToExecute(position, now = 1_000L))
    }
}
