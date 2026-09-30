/**
 * Validation harness (Phase 13): generates reference vectors from the ORIGINAL
 * JavaScript implementation of CalculationNode.js.
 *
 * Run from the ORIGINAL repository root (which contains src/main/CalculationNode.js):
 *   node <path-to>/genVectors.js > reference-vectors.json
 *
 * The generated JSON is committed under app/src/test/resources and consumed by
 * CalculationNodeParityTest.kt to prove numerical parity.
 */
const fs = require('fs');
const path = require('path');

// Load original modules from the source repo (this script lives in the Android repo,
// so the source path is provided via argv[2]).
const SOURCE_ROOT = process.argv[2];
if (!SOURCE_ROOT || !fs.existsSync(path.join(SOURCE_ROOT, 'src/main/CalculationNode.js'))) {
    console.error('Usage: node genVectors.js /path/to/binance-triangle-arbitrage');
    process.exit(1);
}

const CONFIG_MOCK = {
    EXECUTION: { FEE: 0.1, TEMPLATE: ['*', '*', '*'] },
    SCANNING: { WHITELIST: [] },
    INVESTMENT: {
        BTC: { MIN: 0.005, MAX: 0.015, STEP: 0.005 },
        USDT: { MIN: 100, MAX: 300, STEP: 100 },
    },
};

// Module-level CONFIG injection: CalculationNode requires ../../config/config
const Module = require('module');
const origResolve = Module._resolveFilename;
Module._resolveFilename = function (request, parent, ...rest) {
    if (request === '../../config/config' || request === '../../config/config.json') {
        return path.join(__dirname, 'configMock.js');
    }
    return origResolve.call(this, request, parent, ...rest);
};

fs.writeFileSync(path.join(__dirname, 'configMock.js'), `module.exports = ${JSON.stringify(CONFIG_MOCK)};`);

const CalculationNode = require(path.join(SOURCE_ROOT, 'src/main/CalculationNode.js'));

// Stub the module cache for BinanceApi/Loggers so MarketCache loads without
// the optional npm dependencies (pino, node-binance-api) that this harness
// does not exercise.
const srcMain = path.join(SOURCE_ROOT, 'src/main');
require.cache[require.resolve(path.join(srcMain, 'BinanceApi.js'))] = { exports: {
    sortedDepthCache: {},
    getDepthCacheUnsorted: () => ({ bids: {}, asks: {}, eventTime: 0 }),
    getDepthCacheSorted: () => ({ bids: {}, asks: {}, eventTime: 0 }),
    exchangeInfo: () => Promise.resolve({ symbols: [] }),
}};
require.cache[require.resolve(path.join(srcMain, 'Loggers.js'))] = { exports: {
    LINE: '---',
    performance: stubLogger(), execution: stubLogger(), binance: stubLogger(),
}};
function stubLogger() {
    const noop = () => {};
    return { trace: noop, debug: noop, info: noop, warn: noop, error: noop, fatal: noop };
}

const MarketCache = require(path.join(SOURCE_ROOT, 'src/main/MarketCache.js'));

// --- MarketCache reference: triangle discovery -------------------------------
MarketCache.tickers.trading = {
    ETHBTC: { dustDecimals: 5 },
    BNBETH: { dustDecimals: 2 },
    ETHUSDT: { dustDecimals: 4 },
    BTCUSDT: { dustDecimals: 2 },
    LTCBTC: { dustDecimals: 3 },
    LTCUSDT: { dustDecimals: 3 },
    XRPBTC: { dustDecimals: 1 },
    BNBBTC: { dustDecimals: 6 },
    XRPEth_IGNORED: { dustDecimals: 1 },
    BNBUSDT: { dustDecimals: 3 },
};
MarketCache.tickers.trading['XRPETH'] = { dustDecimals: 1 };

const trade_ab_sell = MarketCache.createTrade('BTC', 'ETH', 'BNB');   // SELL,SELL,BUY path combos
const trade_mixed = MarketCache.createTrade('BTC', 'LTC', 'USDT');
const trade_usdt = MarketCache.createTrade('USDT', 'ETH', 'BTC');

// --- CalculationNode reference: full triangle math ---------------------------
function mkDepth(bids, asks, eventTime) {
    const d = { bids: {}, asks: {}, eventTime };
    // Keys formatted exactly like Binance sends them (fixed decimal strings),
    // so JS object key ordering matches insertion order (no integer-like keys).
    bids.forEach(([p, q]) => d.bids[p.toFixed(8)] = q);
    asks.forEach(([p, q]) => d.asks[p.toFixed(8)] = q);
    return d;
}

const depthAB = mkDepth(
    [[21000.0, 0.5], [20999.5, 1.2], [20998.0, 3.0]],
    [[21001.0, 0.4], [21002.5, 0.9], [21004.0, 2.5]],
    1700000000000
);
const depthBC = mkDepth(
    [[6.15, 8.0], [6.14, 12.0], [6.10, 20.0]],
    [[6.20, 6.0], [6.22, 9.5], [6.25, 15.0]],
    1700000000001
);
const depthCA = mkDepth(
    [[0.0069, 300], [0.0068, 500], [0.0067, 900]],
    [[0.0070, 250], [0.0071, 400], [0.0072, 800]],
    1700000000002
);

function runCalc(trade) {
    const snapshot = { ab: depthAB, bc: depthBC, ca: depthCA };
    const results = [];
    const base = trade.symbol.a;
    const spec = CONFIG_MOCK.INVESTMENT[base];
    for (let q = spec.MIN; q <= spec.MAX; q += spec.STEP) {
        results.push(CalculationNode.calculate(q, trade, snapshot));
    }
    return results;
}

const vectors = {
    generatedWith: 'original src/main/CalculationNode.js (bmino binance-triangle-arbitrage)',
    config: CONFIG_MOCK,
    depth: {
        ab: depthAB, bc: depthBC, ca: depthCA,
    },
    triangles: {},
    unitProbes: {},
};

// Serialize trades so Kotlin can reconstruct identical Trade objects
function serRelationship(r) {
    return r ? { method: r.method, ticker: r.ticker, base: r.base, quote: r.quote, dustDecimals: r.dustDecimals } : null;
}
function serTrade(t) {
    return t ? { ab: serRelationship(t.ab), bc: serRelationship(t.bc), ca: serRelationship(t.ca), symbol: t.symbol } : null;
}

vectors.triangles = {
    btc_eth_bnb: serTrade(trade_ab_sell),
    btc_ltc_usdt: serTrade(trade_mixed),
    usdt_eth_btc: serTrade(trade_usdt),
};

vectors.calculations = {};
for (const [name, trade] of Object.entries({
    btc_eth_bnb: trade_ab_sell,
    btc_ltc_usdt: trade_mixed,
    usdt_eth_btc: trade_usdt,
})) {
    if (!trade) continue;
    vectors.calculations[name] = runCalc(trade).map((c) => ({
        id: c.id,
        percent: c.percent,
        ab: { quantity: c.ab.quantity, depth: c.ab.depth },
        bc: { quantity: c.bc.quantity, depth: c.bc.depth },
        ca: { quantity: c.ca.quantity, depth: c.ca.depth },
        a: { spent: c.a.spent, earned: c.a.earned, delta: c.a.delta },
        b: { spent: c.b.spent, earned: c.b.earned, delta: c.b.delta },
        c: { spent: c.c.spent, earned: c.c.earned, delta: c.c.delta },
    }));
}

// Unit probes for the conversion primitives
vectors.unitProbes.orderBookConversion = {
    bid_full_walk: (() => { try { return CalculationNode.orderBookConversion(2.0, 'XRP', 'USDT', 'XRPUSDT', { bids: { '0.5': 2, '0.49': 2 }, asks: {} }); } catch (e) { return { error: e.message }; } })(),
    bid_partial_last: (() => { try { return CalculationNode.orderBookConversion(3.0, 'XRP', 'USDT', 'XRPUSDT', { bids: { '0.5': 2, '0.49': 5 }, asks: {} }); } catch (e) { return { error: e.message }; } })(),
    ask_buy: (() => { try { return CalculationNode.orderBookConversion(2.4, 'USDT', 'XRP', 'XRPUSDT', { bids: {}, asks: { '0.51': 2, '0.52': 10 } }); } catch (e) { return { error: e.message }; } })(),
    shallow_bid: (() => { try { return CalculationNode.orderBookConversion(100, 'XRP', 'USDT', 'XRPUSDT', { bids: { '0.5': 2 }, asks: {} }); } catch (e) { return { error: e.message }; } })(),
    shallow_ask: (() => { try { return CalculationNode.orderBookConversion(100, 'USDT', 'XRP', 'XRPUSDT', { bids: {}, asks: { '0.51': 2 } }); } catch (e) { return { error: e.message }; } })(),
};

vectors.unitProbes.calculateDustless = {
    int_passthrough: CalculationNode.calculateDustless(42, 4),
    trunc_8: CalculationNode.calculateDustless(0.123456789012345, 8),
    trunc_0: CalculationNode.calculateDustless(12.9, 0),
    trunc_2: CalculationNode.calculateDustless(3.14159, 2),
};

vectors.unitProbes.orderBookReverseConversion = {
    rev_ask: (() => { try { return CalculationNode.orderBookReverseConversion(1.0, 'XRP', 'USDT', 'XRPUSDT', { bids: {}, asks: { '0.51': 2, '0.52': 10 } }); } catch (e) { return { error: e.message }; } })(),
    rev_bid: (() => { try { return CalculationNode.orderBookReverseConversion(1.0, 'USDT', 'XRP', 'XRPUSDT', { bids: { '0.5': 2, '0.49': 5 }, asks: {} }); } catch (e) { return { error: e.message }; } })(),
};

vectors.unitProbes.depthRequirement = {
    sell_1_5: CalculationNode.getOrderBookDepthRequirement('SELL', 1.5, { bids: { '0.5': 1, '0.49': 2 }, asks: {} }),
    buy_1_0: CalculationNode.getOrderBookDepthRequirement('BUY', 1.0, { bids: {}, asks: { '0.5': 0.6, '0.51': 0.6 } }),
    insufficient: CalculationNode.getOrderBookDepthRequirement('SELL', 100, { bids: { '0.5': 1 }, asks: {} }),
};

fs.writeFileSync(path.join(__dirname, 'reference-vectors.json'), JSON.stringify(vectors, null, 2));
console.log('Wrote reference-vectors.json');
console.log(JSON.stringify({ triangles: Object.keys(vectors.triangles), calcs: Object.keys(vectors.calculations) }));
