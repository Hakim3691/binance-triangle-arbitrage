package com.hakim3691.bta.paper

import com.hakim3691.bta.core.DepthSnapshot
import com.hakim3691.bta.core.ExecutionConfig
import com.hakim3691.bta.core.Relationship
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Paper-trading simulation tests: fills walk the live book, fees accrue in
 * BNB, balances update, failures occur on thin books, and slippage moves
 * fills adversely.
 */
class PaperTradingEngineTest {

    private val ethBtcBook = DepthSnapshot(
        bids = linkedMapOf(21000.0 to 0.5, 20999.0 to 1.0),
        asks = linkedMapOf(21001.0 to 0.4, 21002.0 to 1.0),
        eventTime = 1000
    )

    private fun engine(
        book: DepthSnapshot = ethBtcBook,
        balances: Map<String, Double> = mapOf("BTC" to 50_000.0, "ETH" to 10.0),
        slippage: Double = 0.0,
        latency: Long = 0,
        failRate: Double = 0.0
    ): PaperTradingEngine {
        PaperTradingEngine.paperUniverse["ETHBTC"] = "ETH" to "BTC"
        return PaperTradingEngine({ book }, balances, latency, slippage, failRate)
    }

    @Before
    fun resetFee() {
        ExecutionConfig.feePercent = 0.10
    }

    @Test
    fun `paper buy fills against asks and updates balances`() = runTest {
        val eng = engine()
        val resp = eng.placeMarketOrder("ETHBTC", 0.2, Relationship.BUY)
        assertTrue(resp.orderId != null)
        assertEquals(0.2, resp.executedQty, 1e-12)
        // 0.4 available at 21001 -> 0.2 buys all from that level
        assertEquals(0.2 * 21001.0, resp.cummulativeQuoteQty, 1e-9)
        assertEquals(50_000.0 - 0.2 * 21001.0, eng.balances["BTC"]!!, 1e-6)
        assertEquals(10.0 + 0.2, eng.balances["ETH"]!!, 1e-12)
    }

    @Test
    fun `paper sell walks bids for partial depth then partial-fills`() = runTest {
        val eng = engine()
        val resp = eng.placeMarketOrder("ETHBTC", 2.0, Relationship.SELL) // book only has 1.5 total bid
        assertTrue(resp.orderId != null)
        assertEquals(1.5, resp.executedQty, 1e-12) // 0.5 @21000 + 1.0 @20999
        assertEquals(0.5 * 21000 + 1.0 * 20999, resp.cummulativeQuoteQty, 1e-9)
    }

    @Test
    fun `paper order fails when balance insufficient`() = runTest {
        val eng = engine(balances = mapOf("ETH" to 0.1, "BTC" to 0.0, "USDT" to 100.0))
        val resp = eng.placeMarketOrder("ETHBTC", 1.0, Relationship.SELL)
        assertTrue(resp.orderId == null)
    }

    @Test
    fun `paper fee accrues in BNB per configured percent`() = runTest {
        val eng = engine()
        val resp = eng.placeMarketOrder("ETHBTC", 0.1, Relationship.SELL)
        val expectedFee = resp.cummulativeQuoteQty * 0.10 / 100.0
        assertEquals(expectedFee, resp.fills.first { it.commissionAsset == "BNB" }.commission, 1e-15)
    }

    @Test
    fun `slippage increases cost of buys`() = runTest {
        ExecutionConfig.feePercent = 0.0
        val clean = engine(slippage = 0.0)
        val slipped = engine(slippage = 1.0)
        val r1 = clean.placeMarketOrder("ETHBTC", 0.1, Relationship.BUY)
        val r2 = slipped.placeMarketOrder("ETHBTC", 0.1, Relationship.BUY)
        assertTrue(r2.cummulativeQuoteQty > r1.cummulativeQuoteQty)
    }

    @Test
    fun `slippage degrades sells symmetrically`() = runTest {
        // A market sell digs into the bids exactly like a market buy digs
        // into the asks, so its average fill price must degrade the same
        // way. The SELL leg previously walked LESS deep under slippage -
        // a better fill - which made every triangle containing a sell
        // systematically optimistic in paper.
        ExecutionConfig.feePercent = 0.0
        val clean = engine(slippage = 0.0)
        val slipped = engine(slippage = 1.0)
        // 0.6 crosses the 0.5 top level, so depth is actually walked.
        val r1 = clean.placeMarketOrder("ETHBTC", 0.6, Relationship.SELL)
        val r2 = slipped.placeMarketOrder("ETHBTC", 0.6, Relationship.SELL)
        assertEquals(0.6 * 1.01, r2.executedQty, 1e-9)
        val price1 = r1.cummulativeQuoteQty / r1.executedQty
        val price2 = r2.cummulativeQuoteQty / r2.executedQty
        assertTrue(
            "slipped sell avg $price2 must be below clean $price1",
            price2 < price1
        )
    }

    @Test
    fun `fill log records completed fills`() = runTest {
        val eng = engine()
        eng.placeMarketOrder("ETHBTC", 0.05, Relationship.SELL)
        assertEquals(1, eng.fillLog.size)
        val rec = eng.fillLog.first()
        assertEquals("ETHBTC", rec.ticker)
        assertEquals("SELL", rec.side)
    }

    @Test
    fun `slippage moves the fill against the trader`() = runTest {
        // 1% adverse slippage on a BUY asks for 1% more base than requested,
        // so the quote paid per base unit is worse than the top of book.
        val eng = engine(slippage = 1.0)
        val resp = eng.placeMarketOrder("ETHBTC", 0.2, Relationship.BUY)
        assertTrue(resp.orderId != null)
        assertEquals(0.202, resp.executedQty, 1e-9)
    }

    @Test
    fun `friction can be retuned while the scanner runs`() = runTest {
        val eng = engine()
        assertEquals("0ms / 0.0000%", eng.friction())
        // Slippage is in percent, the same unit the engine's fee is.
        eng.setFriction(120L, 1.0)
        assertEquals(120L, eng.latencyMs)
        assertEquals(1.0, eng.slippagePercent, 1e-12)
        assertEquals("120ms / 1.0000%", eng.friction())
        // The next fill already pays the new friction.
        val resp = eng.placeMarketOrder("ETHBTC", 0.2, Relationship.BUY)
        assertEquals(0.2 * 1.01, resp.executedQty, 1e-9)
    }

    @Test
    fun `friction is never allowed to go negative`() = runTest {
        val eng = engine()
        eng.setFriction(-50L, -1.0)
        assertEquals(0L, eng.latencyMs)
        assertEquals(0.0, eng.slippagePercent, 0.0)
    }
}
