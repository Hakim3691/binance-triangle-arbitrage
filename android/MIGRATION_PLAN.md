# Migration Plan: Node.js → Kotlin/Android

Behavioral source of truth: **bmino/binance-triangle-arbitrage v6.2.0**
(`https://github.com/Hakim3691/binance-triangle-arbitrage`, a clone of the
original BMino project). This document maps every original module to its
Kotlin replacement and records all intentional differences.

---

## 1. Module Mapping

| Original (Node.js) | Kotlin (this repo) | Notes |
|---|---|---|
| `src/main/Main.js` | `scanner/ScannerController.kt`, `ArbApplication.kt` | Startup chain (latency check → exchangeInfo → triangle discovery → websocket init → wait-for-depth) preserved as a suspend pipeline. `uncaughtException` → coroutine error handling + LogRepository. |
| `src/main/MarketCache.js` | `core/MarketCache.kt` | Exact port of `initialize`, `createTrade`, `getRelationship` incl. whitelist + EXECUTION.TEMPLATE gating and `dustDecimals = max(minQty.indexOf('1') - 1, 0)`. |
| `src/main/CalculationNode.js` | `core/CalculationNode.kt` | Floating-point-exact port of `analyze/optimize/calculate/orderBookConversion/orderBookReverseConversion/getOrderBookDepthRequirement/calculateDustless/recalculateTradeLeg`. Verified against JS reference vectors (see §4). |
| `src/main/ArbitrageExecution.js` | `core/ArbitrageExecution.kt` | `isSafeToExecute` gate order preserved; linear & parallel strategies ported incl. leg recalculation from actual fills; `parseActualResults` BNB-fee extraction ported. `process.exit(0)` on cap → `ScannerState.PAUSED_CAP_REACHED` (mobile-appropriate; see §5). |
| `src/main/BinanceApi.js` | `market/BinanceRestClient.kt`, `market/BinanceWebSocketClient.kt`, `market/DepthCacheManager.kt` | `sortBids/sortAsks` → `SortedDepth`; `depthCacheStaggered/depthCacheCombined` → combined-stream websocket + staggered REST snapshot fetch (50/batch, 200 ms); `getDepthSnapshots` re-sort caching → `getSortedSnapshot(ticker, maxDepth)`. |
| `src/main/HUD.js` | Compose `DashboardScreen` + `OpportunitiesScreen` | Top-N by profit, refreshed reactively from scanner state. |
| `src/main/Loggers.js` | `log/LogRepository.kt` | performance/execution/binance channels preserved as `channel` field; ring buffer shown in Logs screen. |
| `src/main/SpeedTest.js` | `BinanceRestClient.ping()`, multiPing loop in `ScannerController.start()` | |
| `src/main/Util.js` | `util/Util.kt` | `sum/average/prune/secondsSince/millisecondsSince`. |
| `src/main/Validation.js` | `core/ExecutionConfig.validate()` + `config/ConfigurationStore` | Same validation messages. |
| `config/config.json.example` | `config/ConfigurationStore.kt` (DataStore) | Public config persisted locally; secrets in Keystore (never in DataStore). |
| `node-binance-api` depth sync | `market/DepthCacheManager.kt` | Snapshot + buffered diffs + out-of-sync teardown/resync, `context && cb()` semantics preserved. |

## 2. Configuration Mapping

| config.json | Android |
|---|---|
| `KEYS.API` / `KEYS.SECRET` | Android Keystore-backed `CredentialStore` (EncryptedSharedPreferences) |
| `INVESTMENT.[BASE].{MIN,MAX,STEP}` | Settings screen → DataStore → `InvestmentSpec.DEFAULTS` |
| `SCANNING.DEPTH` | Settings screen → `ExecutionConfig.scanningDepth` |
| `SCANNING.WHITELIST` | Preserved in `MarketCache(whitelist)` (not yet exposed in UI; default empty = full scan) |
| `EXECUTION.ENABLED` | Replaced by mode switch: PAPER (default) / LIVE (explicit). Paper = original's `test: true` behavior. |
| `EXECUTION.CAP` | Settings → `ExecutionConfig.cap`; cap reached → `PAUSED_CAP_REACHED` with reset button |
| `EXECUTION.STRATEGY` | linear / parallel (Settings) |
| `EXECUTION.TEMPLATE` | Preserved in `MarketCache(executionTemplate)` (default `["*","*","*"]`) |
| `EXECUTION.FEE` | Settings → `ExecutionConfig.feePercent` |
| `EXECUTION.THRESHOLD.PROFIT` / `.AGE` | Settings → `ExecutionConfig` |
| `HUD.*` | Native Compose UI replaces the blessed HUD entirely |
| `LOG.*` | LogRepository; channel filtering in Logs screen |
| `WEBSOCKET.BUNDLE_SIZE` | Combined-stream subscription (single socket, all tickers) — see §5 |
| `WEBSOCKET.INITIALIZATION_INTERVAL` | 200 ms stagger between snapshot batches (hardcoded equivalent) |
| `BINANCE_OPTIONS` | Not applicable (native client) |

## 3. Startup Sequence Parity

Original (Main.js): latency check → `MarketCache.initialize` → `checkBalances` →
`checkMarket` → staggered depth websockets → `waitForAllTickersToUpdate` → HUD.

Android (`ScannerController.start`): `rest.ping()×5` → `rest.exchangeInfo()` →
`MarketCache.initialize` → paper-universe setup → `depthCache.register` →
websocket connect + staggered snapshots (50/batch) → `WAITING_FOR_DEPTH` →
subscribe `updates` flow → `RUNNING`.

Balance pre-flight (`checkBalances`) runs in live mode via
`LiveTradingSession.checkBalances()` before orders are placed; in paper mode the
simulated balance replaces it (as the original's test mode skips balance checks
for order placement but still warns on missing keys).

## 4. Numerical Parity (validation harness)

`validation/genVectors.js` loads the ORIGINAL `CalculationNode.js` from the
source repository, runs it against a fixed synthetic order book, and emits
`app/src/test/resources/reference-vectors.json`:

- 3 triangles (BUY/BUY/SELL, BUY/SELL/BUY, BUY/SELL/SELL combinations)
- every investment step per triangle (8 position calculations)
- unit probes: orderBookConversion (5 cases), orderBookReverseConversion (2),
  getOrderBookDepthRequirement (3), calculateDustless (4)

`CalculationNodeParityTest` replays them through the Kotlin engine:

- Values computed with only `+ - *` match bit-exactly (IEEE-754 determinism).
- Values involving division match to relative tolerance **1e-12** (x87/SSE
  transcendentals are not involved; only `1/rate` and division ordering).
- Error messages (shallow depth) match byte-for-byte including JS number
  formatting (`98.0` → `98` via `CalculationNode.jsNum`).

During validation a real discrepancy was found and fixed: the reference test
harness had rebuilt depth maps in HashMap order (destroying best-first level
ordering). The production `DepthCacheManager` always emits sorted books; the
harness was corrected to sort identically. No engine bug existed.

## 5. Intentional Architectural Differences (behavior preserved)

1. **Process exit on execution cap** → the app pauses the scanner
   (`PAUSED_CAP_REACHED`) with a user-facing reset. A mobile app cannot
   `process.exit(0)` meaningfully; the observable outcome (no further
   executions until user action) is preserved.
2. **Staggered per-symbol websockets** (BUNDLE_SIZE=1) → one combined stream
   socket. Binance's combined endpoint serves the same `depthUpdate` events;
   the app subscribes to all watched tickers on a single connection with
   reconnect + watchdog. `BUNDLE_SIZE` >1 in the original used combined
   streams too, so this is the same transport with a simpler topology.
3. **HUD repainting** → reactive Compose state; recomposition is driven by
   StateFlow updates instead of a 500 ms timer (lower battery use, no
   stale frames).
4. **Test mode** (`binance.test = !EXECUTION.ENABLED`, where node-binance-api
   validates but does not place orders) → formalized into the PAPER/LIVE mode
   architecture. Paper trading is the default and fills against live books
   through `PaperTradingEngine` without ever touching order endpoints.
5. **pino log files** → in-memory ring buffer (2000 entries) in LogRepository,
   channel-tagged; exported via the Logs screen. File persistence is an
   Android anti-pattern for high-frequency logs; capacity is enforced.
6. **Price strings vs doubles**: the original parses Binance price strings to
   doubles immediately (`parseFloat`). The port stores keys as `Double`
   throughout, so ordering and arithmetic match the original's post-parse
   behavior exactly.

## 6. Functionality Not Ported (with rationale)

- `BINANCE_OPTIONS` passthrough — library-specific options for
  node-binance-api; the native client covers the endpoints actually used.
- File-based log rotation — replaced by the bounded ring buffer (§5.5).
- `HUD.REFRESH_RATE` — superseded by reactive recomposition (§5.3).
