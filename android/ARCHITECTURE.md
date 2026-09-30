# Architecture

## Overview

```
┌───────────────────────────────────────────────────────────┐
│ Jetpack Compose UI (Material 3, dark trading theme)       │
│  Dashboard · Opportunities · Details · Market · Execution │
│  History · Logs · Settings · Connection · Paper/Live      │
└───────────────────────────┬───────────────────────────────┘
                            │ StateFlow (reactive, main-safe)
┌───────────────────────────▼───────────────────────────────┐
│ ArbViewModel (androidx.lifecycle.ViewModel)               │
│  - forwards scanner StateFlows to Compose                 │
│  - user commands: start/stop, save config, enable live    │
└───────────────────────────┬───────────────────────────────┘
                            │
┌───────────────────────────▼───────────────────────────────┐
│ ScannerController  (application-scoped orchestrator)      │
│  = Main.js + HUD.js + Loggers.js equivalents              │
│                                                           │
│  start(): ping×5 → exchangeInfo → MarketCache.initialize  │
│           → register tickers → WS connect + snapshots     │
│           → collect depth updates → arbitrage cycle       │
│  onDepthUpdate(): CalculationNode.analyze(...)            │
│    → armOrExecute(): arm, gate, fire or hold             │
│  ExecutionState tracking, cap pause, performance stats    │
└──────┬──────────────────────────────┬─────────────────────┘
       │                              │
┌──────▼──────────────┐    ┌──────────▼────────────────────┐
│ CORE ENGINE (pure   │    │ MARKET DATA LAYER             │
│ Kotlin, no Android) │    │                               │
│                     │    │ BinanceWebSocketClient        │
│ MarketCache         │    │  combined @depth@100ms stream │
│  triangle discovery │    │  reconnect + watchdog         │
│ CalculationNode     │◄───┤ DepthCacheManager             │
│  book walking,      │    │  snapshot + buffered diffs    │
│  dust, fees, depth  │    │  sorted snapshots, resync     │
│ ArbitrageExecution  │    │ BinanceRestClient             │
│  gating, linear &   │    │  time/exchangeInfo/depth      │
│  parallel execution │    │  signed orders/account        │
│ PreFlightGuard      │    │                               │
│  deadline + re-price│    │                               │
│ Arm / fire gates    │    │ CadenceTracker, freshness     │
│  (arm, wait, fire)  │    │                               │
│ Staged execution    │    │ CadenceTracker (per-ticker    │
│  telemetry          │    │  update rate, for gating)     │
└──────┬──────────────┘    └───────────────────────────────┘
       │ TradeExecutor interface
┌──────▼─────────────────────────────────────────────────────┐
│ EXECUTION BACKENDS                                         │
│  PaperTradingEngine (default)  LiveTradingSession (gated)  │
│  simulated fills vs        real MARKET orders via          │
│  live books, fees,         HMAC-signed REST; balance       │
│  slippage, failures        pre-flight, BNB fee accounting  │
└────────────────────────────────────────────────────────────┘
```

## Key design decisions

### 1. Pure-Kotlin engine core
`core/` has **zero Android imports** — every class is unit-testable on the JVM.
`CalculationNode` mirrors the original's arithmetic operation-for-operation so
results are bit-identical where IEEE-754 is deterministic (see
`CalculationNodeParityTest`).

### 2. Single source of truth for market state
`DepthCacheManager` owns the synchronized local order book (the port of
node-binance-api's depthCache semantics):

- REST snapshot per ticker (`/api/v3/depth?limit=<valid depth>`)
- websocket diffs buffered until the snapshot lands, then replayed
- events older than `lastUpdateId + 1` discarded; gaps trigger teardown +
  resnapshot (out-of-sync safety, identical to the library the original used)
- consumers receive **sorted, bounded** snapshots (bids desc, asks asc) via a
  `SharedFlow<TickerUpdateEvent>` with a 4096-element drop-oldest buffer, so a
  slow consumer can never leak memory during bursts.

### 3. Reactive, main-safe data flow
Every layer exposes `StateFlow`/`SharedFlow`. The engine runs on
`Dispatchers.Default`; OkHttp work on `Dispatchers.IO`; Compose collects with
`collectAsState()`. No market-data processing ever touches the main thread,
and recomposition is driven by state changes (no polling timers).

### 4. Execution behind an interface
`TradeExecutor` abstracts order placement + depth access:

| Implementation | Used when | Notes |
|---|---|---|
| `PaperTradingEngine` | default | fills walk live books; fee/slippage/latency/failure injection; portfolio accounting |
| `LiveTradingSession` | only after explicit confirmation | HMAC-signed MARKET orders; balance pre-flight; BNB fee extraction |

`ArbitrageExecution` (strategies + gating) is backend-agnostic — the exact
same linear/parallel logic runs against either backend, which is what makes
paper-trading results meaningful.

### 5. Failure handling map

| Failure | Detection | Behavior |
|---|---|---|
| WS disconnect | OkHttp callback | exponential backoff reconnect (1s→30s cap) |
| Stale WS (silent) | 15 s watchdog | forced reconnect |
| Out-of-sync book | update-id gap | teardown + fresh REST snapshot |
| Malformed WS message | JSON parse | logged, skipped (stream continues) |
| REST failure / rate limit | HTTP status + token bucket | typed `BinanceApiException`; latency monitor reports |
| Shallow book | `ShallowDepthException` | analysis of that trade skipped (as in original) |
| Rejected leg | `orderId == null` | execution state FAILED; remaining legs still attempted (linear) exactly like the original |
| Cap reached | attempted count | scanner pauses (`PAUSED_CAP_REACHED`) — user resets |

### 6. Staged execution: measure, do not forecast

A triangle is priced from one book print and sent as three sequential orders,
so by the time the round trip finishes it is trading books that have already
moved. Rather than firing on the first print that clears the gate,
`ScannerController.armOrExecute` **arms** the triangle and re-decides on every
depth update that touches one of its own three books.

Three properties keep this from being a bet on the future:

- **The ping is free.** The scan loop is already event-driven and already
  re-evaluates exactly the triangles containing the ticker that moved, so
  "wait until the conditions are met" needs no new socket traffic, no timer and
  no extra loop. It is the same callback, consulted again.
- **The decision is always made on current books.** `CalculationNode.optimize`
  re-runs the full investment-step sweep on every ping, so both the percent and
  the size handed to the gates come from the books as they are at that moment,
  never from the books that produced the detection.
- **Nothing is predicted.** `Microstructure` measures the top-of-book spread,
  the size imbalance and the tick cadence; `ExecutionGates` uses them only to
  decide whether to be patient. A move predictable from this feed is arbitraged
  away within milliseconds, and the imbalances that survive are reactions to
  liquidity already passing through - adverse selection, not an edge. So the
  engine measures the cost of the round trip on live books and refuses to fire
  unless it clears a margin.

Two rules in `ExecutionGates` are absolute and not configurable: nothing is
sent below break-even, and the imbalance/cadence gates may only delay a fire
while the spread is also tight - on a wide (or unmeasurable) spread the
triangle is sent or dropped, never held.

An arm whose window closes is dropped by a timer rather than by a book event,
because a triangle whose books go quiet is never re-evaluated by the
event-driven scan and would otherwise stay armed forever. Every drop carries a
reason string through to the dashboard, since an unexplained silence is
indistinguishable from a bug.

### 7. Paper execution has to have friction

The paper engine fills against the live book, so at zero latency and zero
slippage it reproduces the projection exactly - which makes the deadline, the
pre-flight abort and the unwind unreachable, and leaves the staged executor
tuning itself against a fiction. Both values are derived from the measured REST
round trip and the observed book spreads
(`AutoTuner.derivePaperLatencyMs` / `derivePaperSlippagePercent`) and can be
retuned while the scanner runs.

### 8. Both traversal directions are kept

`A-B-C` and `A-C-B` cross opposite sides of the same three books. If the
mid-price loop return of the first is `m` and the three half-spreads sum to
`s`, the second returns roughly `-m - 2s`: at most one direction is ever
profitable, and which one changes with the dislocation. A "mirror dedupe"
therefore does not remove duplicates - it removes whichever half of the
opportunity set the canonical ordering guesses wrong about, silently. The
switch remains as an opt-in CPU lever and is documented as such.

### 9. Everything the exchange would reject is rejected here first

A rejected leg is not a skipped trade. By the time Binance refuses leg two,
leg one is filled and unwinding costs taker fees twice to end up roughly
where it started - or worse, stranded if an unwind leg fails. So
`LegalityCheck` validates every leg against the symbol's LOT_SIZE grid and
notional minimum before any order is sent, `isSafeToExecute` consults it
first, and the Kelly sizer halves-and-retries through it.

Live orders carry two more defences against the failure modes that cost
capital rather than just skipping a trade:

- **Idempotency.** Client order ids are generated before the request is
  sent, and a network failure triggers query-by-id before the engine
  decides the order was rejected. Treating a timeout as a rejection is how
  a triangle ends up believing it is flat while holding two legs.
- **Server-clock timestamps.** Signed requests use the measured Binance
  offset, so a device clock drifting beyond the recvWindow cannot fail
  every order with -1021 - which in this engine happens mid-triangle.

### 10. The gate gates; it does not size

The Kelly machinery is a P(win) filter only. The criterion assumes
independent bets with a stationary, correctly-estimated edge distribution;
neither holds when the expected percent is a projection made on the same
book that will consume the order. Position size is a plain fixed fraction
of the budget under the per-trade clamp, and the criterion's fraction is
deliberately not trusted with it.

## Threading model

| Thread | Work |
|---|---|
| Main | Compose rendering only |
| `Dispatchers.Default` (app scope) | scanner orchestration, CalculationNode cycles |
| `Dispatchers.IO` | OkHttp REST calls |
| OkHttp WS threads | frame receipt → `DepthCacheManager` (synchronized, O(depth) sort on change) |

Performance notes: snapshot maps are rebuilt only when the underlying book
changed (mirroring `getDepthSnapshots`' eventTime check); opportunity lists
are rebuilt per cycle but capped to related trades of the changed ticker
(fan-out identical to the original's `related.trades[ticker]` index).

### 11. The scan is rate-limited, not event-driven

Every depth event used to trigger a full re-optimisation of every triangle
containing the moved ticker. At the combined-stream message rate that is
several hundred cycles a second, and it dominated battery, log volume and
thermal load. Cycles are now coalesced to at most one per `SCAN_TICK_MS`.
The decision horizon is seconds, so the resolution thrown away was never
worth the heat; the raw event stream still feeds the cadence statistics
unchanged, so the market-side signal is untouched.

### 12. Log lines are throttled on identity, not on text

The existing dedupe hashes the rendered message, so any line that embeds a
varying number defeats it. The skip counters and the auto-tune summary are now
gated on stable keys and on derived values respectively, which is the
difference between a log you can read and one that is mostly the log.

### 13. Slippage models the order in flight, not the spread

The paper engine fills against the real book, so the crossing cost of the
spread is already in the fill price. Charging the half-spread again would
double-count it. The remaining exposure - the move between the snapshot the
decision was taken from and the book the order meets `latencyMs` later - is
modelled as the arrival half-spread scaled by the number of book-update
intervals elapsed, and defaults to a typical spread when nothing has been
observed yet.
