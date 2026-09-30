# Testing & Validation Strategy

## Test suite

`./gradlew testDebugUnitTest` — 260 tests, all green.

| Suite | Count | Covers |
|---|---|---|
| `AgeThresholdTunerTest` | 6 | p90 x 1.5 self-tuning, seeding from latency, clamping, and reset on resync |
| `ArbitrageExecutionTest` | 16 | `isSafeToExecute` gate order (legality/profit/age/duplicate/cap), linear strategy (fill, rejected leg, leg recalculation), parallel strategy, BNB fee extraction, cap shutdown, unbounded-history pruning |
| `ArmedOpportunityTest` | 19 | **arming registry**: captured bars, wait counting, fire/abandon/expire releases, slot bounds, and the reported medians |
| `AutoTunerGatesTest` | 21 | staged execution derivations: arm window, fire/drop margins, spread/imbalance/cadence ceilings, in-flight paper slippage (including the no-observation fallback), and the invariants that keep the gates honest |
| `ScanDepthUniverseTest` | 6 | scan depth against the thinnest book and the largest configured notional, floor and subscribed-depth ceiling |
| `AutoTunerTest` | 16 | fee, threshold, lot-size, depth, cap, deadline and strategy derivations |
| `CalculationNodeParityTest` | 5 | **Original-vs-Kotlin validation** (below) |
| `EdgeSamplesTest` | 6 | **the Stage 3 base rate**: percentiles, max, cycles reaching the fire bar, bounded deque |
| `DepthCacheManagerTest` | 8 | snapshot/diff synchronization, stale-event discard, gap-triggered resync, zero-qty deletions, bid/ask ordering, stale-ticker detection, valid-depth resolution |
| `DepthFreshnessTest` | 5 | freshness math, derived feed-dead/degraded/healthy states, stale-ticker listing |
| `ExecutionGatesTest` | 15 | **the two absolute rules**: no round trip below break-even, and no deferral on a wide or unmeasured spread; plus waiting, expiry, and the microstructure gates |
| `FeedHealthTest` | 4 |  |
| `IntegrationScanTest` | 2 | exchangeInfo -> discovery -> optimize -> 3-order execution end-to-end |
| `KellyCriterionTest` | 8 | normal CDF/PDF accuracy, 80%-probability gating, rejection of negative edge / sub-threshold P, Kelly fraction monotonicity, clamp behavior |
| `KellyPaperTraderTest` | 8 | full auto-trading flow with **fixed-fractional sizing** (criterion gates, never sizes), disabled-config, negative-edge, low-probability, cooldown, and budget-safety paths |
| `LegalityCheckTest` | 9 | **pre-submission exchange legality**: LOT_SIZE grid, minQty, NOTIONAL floors, unfiltered symbols skipped, and the execution gate rejecting an illegal position |
| `ResearchRecorderTest` | 7 | **Stage 3 research collector**: CSV header contract (every decision variable present), quoted-reason integrity, header/row shape, background-writer flush, session rollover, stop semantics |
| `LiveOrderSafetyTest` | 2 | **live idempotency**: client ids chosen before send (unique, prefixed) and signed timestamps carrying the measured server offset |
| `LoopTrackerTest` | 9 | coverage-based loop counting, progress, and the derived FeedHealth states |
| `MarketCacheTest` | 11 | triangle discovery **in both traversal directions**, method/dust derivation, whitelist, template gating, related-ticker index, multi-base, opt-in de-duplication accounting |
| `MicrostructureTest` | 14 | spread in bps, crossed/empty books, top-of-book imbalance, tick-cadence tracking, and the rolling samples the thresholds are derived from |
| `OpportunityUiTest` | 1 | UI model ages + leg quantities |
| `PaperTradingEngineTest` | 10 | ask-walk fills, bid-walk partial fills, balance rejection, BNB fee accrual, **symmetric slippage on buys and sells**, fill log, live re-tuning of latency/slippage |
| `PreFlightGuardTest` | 13 |  |
| `StagedExecutionTest` | 10 | **the whole loop end to end**: identify -> arm -> wait on book updates -> fire, with the real optimizer sizing from live books; proves a triangle is never sent at a loss and that a wide spread is never held |
| `TradeFilterTest` | 4 | empty-book skipping and per-ticker accounting |
| `TryBeginTest` | 3 | **atomic in-progress claim**: a claimed triangle blocks any second claim, shared symbols collide, the cap binds at claim time |
| `UnwindOnFailureTest` | 5 | reversing filled legs newest-first, partial unwinds, stranded-asset reporting, manual-close flagging |
| `ValidationTest` | 6 | port of Validation.js configuration checks |

## Original-vs-Kotlin validation (Phase 13)

### Vector generation
`validation/genVectors.js` is executed **against the original repository**:

```bash
node validation/genVectors.js /path/to/binance-triangle-arbitrage
```

It loads the real `src/main/CalculationNode.js` and `MarketCache.js` (with
stubbed loggers/API so no npm install or network is needed) and emits
`app/src/test/resources/reference-vectors.json` containing:

- three triangles across method combinations (BUY/BUY/SELL, BUY/SELL/BUY, BUY/SELL/SELL)
- every investment-step calculation for each triangle (8 positions)
- unit probes: conversion walks (full, partial-last-fill, ask-buy), shallow-depth
  error strings, reverse conversions, depth-requirement levels, dust truncation

### Comparison semantics
| Operation class | Tolerance |
|---|---|
| `+ - *` only (books walks on bids, deltas, spends) | **exact bit equality** |
| division (`amountFrom / rate` on asks, percent) | relative 1e-12 |
| error messages | byte equality (incl. JS number formatting, e.g. `98` not `98.0`) |
| depth level indices, ids, methods | exact |

### Findings during validation
1. **Harness ordering bug (fixed in harness, not engine):** rebuilding the
   reference book from JSON destroyed best-first level order; the production
   `DepthCacheManager` always sorts. The harness now sorts identically. After
   the fix all 8 position calculations match to ≤1e-10 relative error.
2. **Number formatting:** JavaScript prints whole doubles without a decimal
   (`98`), Kotlin prints `98.0`. Fixed with `CalculationNode.jsNum` so shallow
   -depth messages are byte-identical.
3. **Post-loop depth index:** the original's `getOrderBookDepthRequirement`
   returns the loop counter *after* an unsuccessful full walk; ported with a
   `while` loop to reproduce the quirk (covered by the `insufficient` vector).

## Test safety

Tests that pick a specific traversal direction pin
`ExecutionConfig.dedupeMirroredTriangles = false` first, because the default
universe now contains both directions of every triangle.

## Test safety

- **No test places a real order.** All execution tests run against scripted
  `TradeExecutor` doubles or the paper engine. The live `BinanceRestClient`
  path is only reachable through `LiveTradingSession`, which requires stored
  credentials + explicit user confirmation at runtime, and is never
  constructed in tests.
- WebSocket cache tests use synthetic diff events (no network).
- Parity vectors are static fixtures committed under `app/src/test/resources`.

## Running

```bash
./gradlew testDebugUnitTest                      # full suite
./gradlew testDebugUnitTest --tests "*CalculationNodeParityTest*"   # parity only
```

Reports: `app/build/reports/tests/testDebugUnitTest/index.html`

## Regenerating reference vectors

After any change to the validation configuration (triangles, books, config):

```bash
node validation/genVectors.js /path/to/binance-triangle-arbitrage
cp validation/reference-vectors.json app/src/test/resources/
./gradlew testDebugUnitTest
```

The harness intentionally pins the vector books to Binance-style fixed-decimal
price keys to avoid JavaScript's integer-key reordering artifact (see
MIGRATION_PLAN.md §5.6).
| `StrategyHysteresisTest` | 6 | both strategies reachable, hysteresis band, boundary noise cannot oscillate, unusable readings ignored |
