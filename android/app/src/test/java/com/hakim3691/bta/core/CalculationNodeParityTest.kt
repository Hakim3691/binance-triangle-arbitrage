package com.hakim3691.bta.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 13 validation: compares Kotlin engine outputs against reference
 * vectors generated from the ORIGINAL JavaScript implementation
 * (validation/genVectors.js run against bmino's CalculationNode.js).
 *
 * Tolerance: exact bit-equality is required for values that only involve
 * +, -, * (IEEE-754 deterministic); values involving division (1/x in ask
 * walks) are compared with RELATIVE tolerance 1e-12.
 */
class CalculationNodeParityTest {

    private val vectors: JSONObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("reference-vectors.json")!!
        JSONObject(stream.bufferedReader().readText())
    }

    private fun relTol(actual: Double, expected: Double, tol: Double = 1e-12) {
        if (expected == 0.0) {
            assertEquals(expected, actual, 1e-15)
        } else {
            assertTrue(
                "expected $expected but got $actual (rel err ${kotlin.math.abs((actual - expected) / expected)})",
                kotlin.math.abs((actual - expected) / expected) <= tol
            )
        }
    }

    private fun tradeFromJson(name: String): Trade {
        val t = vectors.getJSONObject("triangles").getJSONObject(name)
        fun rel(key: String): Relationship {
            val r = t.getJSONObject(key)
            return Relationship(
                method = r.getString("method"),
                ticker = r.getString("ticker"),
                base = r.getString("base"),
                quote = r.getString("quote"),
                dustDecimals = r.getInt("dustDecimals")
            )
        }
        val s = t.getJSONObject("symbol")
        return Trade(rel("ab"), rel("bc"), rel("ca"), TradeSymbols(s.getString("a"), s.getString("b"), s.getString("c")))
    }

    private fun depthFromJson(side: String): DepthSnapshot {
        val d = vectors.getJSONObject("depth").getJSONObject(side)
        fun levels(obj: JSONObject, descending: Boolean): LinkedHashMap<Double, Double> {
            // org.json iterates in HashMap order; restore the canonical best-first
            // order (bids desc, asks asc) that DepthCacheManager guarantees and
            // that the original JS cache held at calculation time.
            val entries = mutableListOf<Pair<Double, Double>>()
            for (key in obj.keys()) entries.add(key.toDouble() to obj.getDouble(key))
            entries.sortByDescending { it.first }   // bids: best (highest) first
            if (!descending) entries.reverse()      // asks: best (lowest) first
            val ordered = LinkedHashMap<Double, Double>()
            for ((k, v) in entries) ordered[k] = v
            return ordered
        }
        return DepthSnapshot(
            bids = levels(d.getJSONObject("bids"), descending = true),
            asks = levels(d.getJSONObject("asks"), descending = false),
            eventTime = d.getLong("eventTime")
        )
    }

    private fun snapshot(): CalculationNode.TradeDepthSnapshot =
        CalculationNode.TradeDepthSnapshot(depthFromJson("ab"), depthFromJson("bc"), depthFromJson("ca"))

    @Test
    fun `calculate matches javascript for every triangle and investment quantity`() {
        // Apply the exact vector config (mirrors CONFIG.EXECUTION.FEE and INVESTMENT[bases])
        val cfg = vectors.getJSONObject("config")
        ExecutionConfig.feePercent = cfg.getJSONObject("EXECUTION").getDouble("FEE")
        val investments = cfg.getJSONObject("INVESTMENT")
        InvestmentSpec.DEFAULTS.clear()
        for (base in investments.keys()) {
            val spec = investments.getJSONObject(base)
            InvestmentSpec.DEFAULTS[base] = InvestmentSpec(
                base, spec.getDouble("MIN"), spec.getDouble("MAX"), spec.getDouble("STEP")
            )
        }
        val snapshot = snapshot()
        for (name in vectors.getJSONObject("calculations").keys()) {
            val trade = tradeFromJson(name)
            val expectedList = vectors.getJSONObject("calculations").getJSONArray(name)
            val base = trade.symbol.a
            val spec = InvestmentSpec.DEFAULTS[base]
                ?: error("Missing investment spec for $base in test")

            val results = ArrayList<CalculatedPosition>()
            var q = spec.min
            while (q <= spec.max) {
                results.add(CalculationNode.calculate(q, trade, snapshot))
                q += spec.step
            }

            assertEquals("$name: result count", expectedList.length(), results.size)
            for (i in 0 until expectedList.length()) {
                val exp = expectedList.getJSONObject(i)
                val act = results[i]
                assertEquals("$name[$i] id", exp.getString("id"), act.id)
                relTol(act.percent, exp.getDouble("percent"))
                relTol(act.a.spent, exp.getJSONObject("a").getDouble("spent"))
                relTol(act.a.earned, exp.getJSONObject("a").getDouble("earned"))
                relTol(act.a.delta, exp.getJSONObject("a").getDouble("delta"))
                relTol(act.b.spent, exp.getJSONObject("b").getDouble("spent"))
                relTol(act.b.earned, exp.getJSONObject("b").getDouble("earned"))
                relTol(act.b.delta, exp.getJSONObject("b").getDouble("delta"))
                relTol(act.c.spent, exp.getJSONObject("c").getDouble("spent"))
                relTol(act.c.earned, exp.getJSONObject("c").getDouble("earned"))
                relTol(act.c.delta, exp.getJSONObject("c").getDouble("delta"))
                relTol(act.ab.quantity, exp.getJSONObject("ab").getDouble("quantity"))
                relTol(act.bc.quantity, exp.getJSONObject("bc").getDouble("quantity"))
                relTol(act.ca.quantity, exp.getJSONObject("ca").getDouble("quantity"))
                assertEquals("$name[$i] ab depth", exp.getJSONObject("ab").getInt("depth"), act.ab.depth)
                assertEquals("$name[$i] bc depth", exp.getJSONObject("bc").getInt("depth"), act.bc.depth)
                assertEquals("$name[$i] ca depth", exp.getJSONObject("ca").getInt("depth"), act.ca.depth)
            }
        }
    }

    @Test
    fun `orderBookConversion matches javascript unit probes`() {
        val probes = vectors.getJSONObject("unitProbes").getJSONObject("orderBookConversion")

        // Recreate the exact probe books
        val bidBook = DepthSnapshot(
            bids = linkedMapOf(0.5 to 2.0, 0.49 to 2.0),
            asks = emptyMap()
        )
        val bidBook2 = DepthSnapshot(
            bids = linkedMapOf(0.5 to 2.0, 0.49 to 5.0),
            asks = emptyMap()
        )
        val askBook = DepthSnapshot(
            bids = emptyMap(),
            asks = linkedMapOf(0.51 to 2.0, 0.52 to 10.0)
        )
        val shallowBid = DepthSnapshot(bids = linkedMapOf(0.5 to 2.0), asks = emptyMap())
        val shallowAsk = DepthSnapshot(bids = emptyMap(), asks = linkedMapOf(0.51 to 2.0))

        probes.getJSONObject("bid_full_walk").let { exp ->
            val act = CalculationNode.orderBookConversion(2.0, "XRP", "USDT", "XRPUSDT", bidBook)
            relTol(act.value, exp.getDouble("value"))
            assertEquals(exp.getInt("depth"), act.depth)
        }
        probes.getJSONObject("bid_partial_last").let { exp ->
            val act = CalculationNode.orderBookConversion(3.0, "XRP", "USDT", "XRPUSDT", bidBook2)
            relTol(act.value, exp.getDouble("value"))
            assertEquals(exp.getInt("depth"), act.depth)
        }
        probes.getJSONObject("ask_buy").let { exp ->
            val act = CalculationNode.orderBookConversion(2.4, "USDT", "XRP", "XRPUSDT", askBook)
            relTol(act.value, exp.getDouble("value"))
            assertEquals(exp.getInt("depth"), act.depth)
        }
        probes.getJSONObject("shallow_bid").let { exp ->
            try {
                CalculationNode.orderBookConversion(100.0, "XRP", "USDT", "XRPUSDT", shallowBid)
                error("expected ShallowDepthException")
            } catch (e: ShallowDepthException) {
                assertEquals(exp.getString("error"), e.message)
            }
        }
        probes.getJSONObject("shallow_ask").let { exp ->
            try {
                CalculationNode.orderBookConversion(100.0, "USDT", "XRP", "XRPUSDT", shallowAsk)
                error("expected ShallowDepthException")
            } catch (e: ShallowDepthException) {
                assertEquals(exp.getString("error"), e.message)
            }
        }
    }

    @Test
    fun `calculateDustless matches javascript semantics`() {
        val probes = vectors.getJSONObject("unitProbes").getJSONObject("calculateDustless")
        relTol(CalculationNode.calculateDustless(42.0, 4), probes.getDouble("int_passthrough"))
        relTol(CalculationNode.calculateDustless(0.123456789012345, 8), probes.getDouble("trunc_8"))
        relTol(CalculationNode.calculateDustless(12.9, 0), probes.getDouble("trunc_0"))
        relTol(CalculationNode.calculateDustless(3.14159, 2), probes.getDouble("trunc_2"))
    }

    @Test
    fun `orderBookReverseConversion matches javascript unit probes`() {
        val probes = vectors.getJSONObject("unitProbes").getJSONObject("orderBookReverseConversion")
        val askBook = DepthSnapshot(bids = emptyMap(), asks = linkedMapOf(0.51 to 2.0, 0.52 to 10.0))
        val bidBook = DepthSnapshot(bids = linkedMapOf(0.5 to 2.0, 0.49 to 5.0), asks = emptyMap())

        probes.getJSONObject("rev_ask").let { exp ->
            val act = CalculationNode.orderBookReverseConversion(1.0, "XRP", "USDT", "XRPUSDT", askBook)
            relTol(act.value, exp.getDouble("value"))
            assertEquals(exp.getInt("depth"), act.depth)
        }
        probes.getJSONObject("rev_bid").let { exp ->
            val act = CalculationNode.orderBookReverseConversion(1.0, "USDT", "XRP", "XRPUSDT", bidBook)
            relTol(act.value, exp.getDouble("value"))
            assertEquals(exp.getInt("depth"), act.depth)
        }
    }

    @Test
    fun `getOrderBookDepthRequirement matches javascript unit probes`() {
        val probes = vectors.getJSONObject("unitProbes").getJSONObject("depthRequirement")
        val bidBook = DepthSnapshot(bids = linkedMapOf(0.5 to 1.0, 0.49 to 2.0), asks = emptyMap())
        val askBook = DepthSnapshot(bids = emptyMap(), asks = linkedMapOf(0.5 to 0.6, 0.51 to 0.6))
        val oneLevel = DepthSnapshot(bids = linkedMapOf(0.5 to 1.0), asks = emptyMap())

        assertEquals(
            probes.getInt("sell_1_5"),
            CalculationNode.getOrderBookDepthRequirement("SELL", 1.5, bidBook)
        )
        assertEquals(
            probes.getInt("buy_1_0"),
            CalculationNode.getOrderBookDepthRequirement("BUY", 1.0, askBook)
        )
        assertEquals(
            probes.getInt("insufficient"),
            CalculationNode.getOrderBookDepthRequirement("SELL", 100.0, oneLevel)
        )
    }
}
