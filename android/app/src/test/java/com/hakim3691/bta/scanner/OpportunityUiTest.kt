package com.hakim3691.bta.scanner

import com.hakim3691.bta.core.CalculatedPosition
import com.hakim3691.bta.core.DepthSnapshot
import com.hakim3691.bta.core.LegCalculation
import com.hakim3691.bta.core.AssetLedger
import com.hakim3691.bta.core.CalculationNode
import com.hakim3691.bta.core.Relationship
import com.hakim3691.bta.core.Trade
import com.hakim3691.bta.core.TradeSymbols
import org.junit.Assert.assertEquals
import org.junit.Test

class OpportunityUiTest {

    @Test
    fun `ui model carries ages and leg quantities`() {
        val trade = Trade(
            Relationship("SELL", "ETHBTC", "ETH", "BTC", 5),
            Relationship("BUY", "BNBETH", "BNB", "ETH", 2),
            Relationship("SELL", "BNBBTC", "BNB", "BTC", 6),
            TradeSymbols("BTC", "ETH", "BNB")
        )
        val c = CalculatedPosition(
            trade, LegCalculation(0.01, 1), LegCalculation(0.2, 2), LegCalculation(0.3, 3),
            AssetLedger(0.01, 0.0101, 0.0001), AssetLedger(0.01, 0.01, 0.0), AssetLedger(0.01, 0.01, 0.0)
        )
        c.percent = 0.25
        c.usedDepth = CalculationNode.TradeDepthSnapshot(
            DepthSnapshot(emptyMap(), emptyMap(), 1_000),
            DepthSnapshot(emptyMap(), emptyMap(), 1_005),
            DepthSnapshot(emptyMap(), emptyMap(), 1_010)
        )
        val ui = OpportunityUi.from(c, now = 1_050)
        assertEquals(50L, ui.abAgeMs)
        assertEquals(45L, ui.bcAgeMs)
        assertEquals(40L, ui.caAgeMs)
        assertEquals(50L, ui.ageMs) // stalest = max
        assertEquals(0.01, ui.orderSizeAb, 1e-12)
        assertEquals(0.2, ui.orderSizeBc, 1e-12)
        assertEquals(0.3, ui.orderSizeCa, 1e-12)
        assertEquals("BTC-ETH-BNB", ui.id)
    }
}
