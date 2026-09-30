# Binance Triangle Arbitrage — Android (Kotlin + Jetpack Compose)

A fully native Android port of the
[binance-triangle-arbitrage](https://github.com/Hakim3691/binance-triangle-arbitrage)
desktop application (originally by Brandon Mino). It monitors the Binance spot
market in real time over WebSocket, evaluates every executable A→B→C→A triangle
against live order-book depth, and surfaces profitable opportunities — with
**paper trading as the default mode** and live trading locked behind an
explicit, irreversible-feeling confirmation flow.

> This repository is a standalone Android project. It does not depend on the
> original Node.js repository at runtime or build time.

## Features

- **Real-time scanner**: combined-stream `@depth@100ms` WebSocket with
  snapshot/diff synchronization, reconnect with exponential backoff, stale
  watchdog, and out-of-sync resnapshotting.
- **Exact engine port**: triangle discovery, investment-step optimization,
  bid/ask book walking, dust truncation, depth accounting, and fee math are
  floating-point-validated against the original JavaScript
  (`CalculationNodeParityTest`).
- **Paper trading (default)**: simulated portfolio fills against live Binance
  books with configurable fees, slippage, latency, and failure injection.
- **Kelly-criterion paper auto-trading**: set a USDT budget; qualifying
  opportunities — expected edge with **P(net profit) ≥ 80%** (configurable) and
  positive Kelly fraction — are auto-executed: USDT funds the base leg, the
  triangle runs, and proceeds settle back to USDT. Position size follows the
  generalized Kelly fraction, clamped to a configurable per-trade maximum.
- **Skew-free freshness**: opportunity ages use local receive timestamps
  (Binance server-clock skew measured and smoothed at startup), eliminating
  negative-age displays and false staleness rejections on devices with fast
  clocks.
- **Staged execution (arm - wait - fire)**: a qualifying triangle is armed
  rather than sent, then re-sized and re-priced on every update to its own
  three books until the gates say go, the edge collapses, or the window
  closes. A round trip is never sent below break-even, and patience is only
  ever spent while the spread is tight.
- **Gated live trading**: requires stored credentials + typed confirmation
  phrase; the UI shows an unmistakable red LIVE banner.
- **Reactive dashboard**: connection status, latency, sync state, cycles/sec,
  top opportunities with per-leg ages, execution states, and trade history.
- **Secure credential storage**: Android Keystore (EncryptedSharedPreferences);
  secrets are never logged or included in BuildConfig.

## Requirements

- Android Studio Koala (or newer) with AGP 8.5
- JDK 17
- Android SDK: compileSdk 34, minSdk 26 (Android 8.0+)
- Target device class: OnePlus 10 Pro / Snapdragon 8 Gen 1 / Android 15
  (no OEM-specific dependencies)

## Build

```bash
./gradlew assembleDebug          # produces app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # runs the full unit + parity test suite
```

Or open the project in Android Studio and press Run.

## Installation

1. Enable *Install unknown apps* for your file manager or use `adb`:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
2. Launch **Binance Triangle Arbitrage**.

## Binance Configuration

1. Open **Settings → Binance API Credentials**.
2. Enter an API key/secret generated at
   [binance.com → API Management](https://www.binance.com/en/my/settings/api-management).
   - For scanning + paper trading, **no credentials are required**.
   - For live trading, enable spot trading on the key and (recommended)
     restrict withdrawals.
3. Keys are stored encrypted at rest (Android Keystore). They can be removed
   at any time with *REMOVE*.

Trading parameters (investment base/MIN/MAX/STEP, fee %, profit threshold,
age threshold, strategy, execution cap, scanning depth) live in **Settings**
and persist across restarts (DataStore).

### Triangle universe and scan loops

Discovery walks every ordered `A-B-C` permutation, so both `A-B-C` and `A-C-B`
appear — and both belong in the universe. They are **not** the same round trip:
the two directions cross opposite sides of the same three books. If the
mid-price loop return of the first is `m` and the three half-spreads sum to
`s`, the second returns roughly `-m - 2s`, so at most one direction is ever
profitable and which one it is depends on the sign of the dislocation.
Canonicalizing to the alphabetically-ordered permutation silently drops
whichever direction is wrong for the current market — half of all profitable
opportunities.

De-duplication therefore exists as an **opt-in CPU lever**, off by default
(Settings → *De-dupe mirrored triangles*). Enabling it halves the scan work at
the cost of seeing only one direction per triangle.

Scanning itself is event driven: each websocket depth update re-evaluates only
the triangles containing the ticker that moved, so the same combinations recur
constantly and never in a fixed round-robin order. Because a "cycle" therefore
counts *events* and says nothing about coverage, the Dashboard reports a
separate loop counter: `LOOPS` advances only once every known combination has
been evaluated, alongside `LOOP PROG`, `LAST LOOP`, `COMBINATIONS` and
`EVALUATED`.

### Duty cycle

A book update is a signal that something moved, not a reason to re-optimise
every related triangle. The scanner coalesces depth events and runs at most
one cycle every `SCAN_TICK_MS` (150ms), which took it from several hundred
cycles a second to about seven. Nothing is lost: the arm TTL is measured in
seconds and the execution deadline is ~2s, so a faster scan cannot change a
decision. Cadence statistics are recorded *before* the gate, straight off the
raw event stream, so the derived cadence ceiling stays exact.

### Logging

Repeat-line chatter is throttled on a stable key rather than on the message
text, because the message-level dedupe cannot collapse a line whose text
carries a varying number ("Skipped 2", "Skipped 4", ...). The auto-derived
settings line is gated on the *derived values* rather than the rendered string
- the string embeds live market observations, so it differs on nearly every
run - and is restated at most once every five minutes.

### Background operation

Switching apps used to kill the scan: Android freezes backgrounded processes,
which drops the websocket and throttles REST until the feed dies. The scanner
now runs inside a **foreground service** - a persistent, silent notification
("Scanner running") appears while the scan is active, and that is what tells
the OS to leave the process alone. The service is tied to the scanner
lifecycle: it starts when scanning starts and stops when you press STOP, so no
notification lingers when nothing is running. Backgrounding the app no longer
interrupts scanning, order placement, or the Kelly book.

### Feed health and outages

Switching away from the app can drop the websocket: Android does not guarantee
the socket survives backgrounding. The app now detects this rather than
scanning on frozen books.

- **`FRESH n/total`** replaces the old `SYNCED` count. `SYNCED` only meant "a
  snapshot arrived at some point", which stayed green while the books were
  minutes stale. Freshness is measured against the age gate.
- **Scanning is suspended** while any ticker is stale, and the gate reads
  `suspended: ...`. Evaluating triangles against books that are not moving
  produces numbers that look like work and mean nothing.
- **The age gate is reset on reconnect.** Ages recorded while the feed is down
  describe a disconnection, not the market, and used to train the threshold to
  its 15 s ceiling.
- **Reconnects re-seed proactively.** A dead feed produces no diffs, so gap
  detection can never flag the affected tickers and the cache stays frozen
  indefinitely. Every watched ticker is re-snapshotted in batches on open.
- A red banner names the problem and the age of the oldest book; stale
  opportunities are labelled `STALE, will not be traded`.

### Partial and failed triangles

A leg that fills and is followed by a rejected leg leaves a real position open.
The engine now unwinds what it filled, in reverse, on the same tickers with the
opposite side — and does not submit the remaining legs, which would compound
the imbalance. A triangle whose first leg is rejected is not continued at all.

If the unwind itself does not fill, the app cannot fix it, so it says so
loudly: the execution is flagged `requiresManualClose`, the stranded asset is
named, a red `CLOSE MANUALLY ON BINANCE` banner appears on the dashboard with a
dismiss action, and the log records `MANUAL ACTION`.

### Execution guards (deadline + pre-flight re-pricing)

A triangle is priced from the books at the moment it is identified, but it is
sent as three *sequential* orders. Between those two points the books move, and
the percent that justified the trade may no longer exist. The engine already
resizes each leg from the previous actual fill, but that only corrects
*quantity* drift, not *opportunity* drift. Two guards close the gap:

- **Pre-flight re-price** (on by default). Before every order, the remaining
  legs are walked against freshly read books and the projected net percent is
  recomputed with the same arithmetic the engine uses. If the projection no
  longer clears `THRESHOLD.PROFIT` (plus an optional `MARGIN %`), the trade is
  abandoned. A book that cannot cover the remaining legs also aborts, rather
  than throwing.
- **Deadline** (auto by default). A wall-clock budget for the whole round trip,
  derived from the measured REST latency as `latency x 3 legs x 3`, clamped to
  250–5000 ms. Past it, the books being traded are no longer the books the
  trade was priced from.

When either guard fires, the legs that already filled are **unwound** in
reverse on the same tickers with the opposite side, so an abandoned round trip
returns to the base asset instead of parking the position in the middle asset.
Aborted executions appear in the execution history with the reason, the
projected percent at abort, and whether the unwind filled.

Both are configurable in Settings, and both are bypassed by the deadline being
`0` / pre-flight being off if you want to observe the original behaviour.

### Scan depth

`SCANNING.DEPTH` is a single global applied to every ticker, so it has to be
sized for the *hardest* book in the universe, not the easiest:

- the level notional is measured as a **low percentile across a spread sample
  of books**, because one median says nothing when BTCUSDT is a million times
  deeper than XAUTUSDT
- the required notional is the **largest notional any configured investment
  spec can ask for**, priced off each base's own book - an operator-configured
  spec for an expensive base is orders of magnitude larger than the base pair's
- the result is clamped to `MIN_SCAN_DEPTH` (10) and `MAX_SCAN_DEPTH` (50). The
  ceiling is what the depth stream subscribes with; asking the cache for more
  than the socket holds cannot be satisfied, so the clamp is applied *inside*
  the ladder walk, not only on its fallback — the loop is the path almost every
  real universe takes, and leaving it unclamped produced depth-500 requests
  against a depth-50 socket

### Strategy hysteresis

Both `linear` and `parallel` stay reachable, but the decision carries
hysteresis: `parallel` is entered at a pre-funded ratio ≥ 0.999 and only left
below 0.95. The ratio is a continuous quantity that sits on the boundary in
normal operation, so a bare threshold flip-flopped several times a minute — and
each flip counted as a derived-value change, re-logging the whole settings line
and shifting the gates under the engine. A reading that is not finite is
treated as no evidence and leaves the live strategy alone.

### Feed health

A pair that ticks slower than the global age gate is illiquid, not broken. The
banner now excuses exactly the books the scan filter already excuses, using the
same per-ticker quiet-book tolerance, so a healthy universe does not sit on
`DEGRADED FEED` forever because of a few exotic pairs. Books that stopped
updating entirely are still counted, and the count of excused books is shown
when any were. The first depth snapshot is also reported as an initial sync
rather than as a recovery — only a feed that was actually unhealthy is a
recovery.

Sizing depth on the base pair alone is what made every alt leg throw
`ShallowDepthException` on every cycle. When a book genuinely cannot cover a
leg the triangle is skipped, and the offenders are accumulated into a single
throttled line instead of one WARN per throw.

### Build identity

`versionCode` and `versionName` are derived from git (`1.0.<commit count>+<short
sha>`) so no two builds look alike in the launcher, and **Connection → Build**
shows the running app's version, code, package and flavour.

### Auto-derivation

Every one of those parameters has its own `AUTO` / `MANUAL` badge, and they
all default to `AUTO`, so the app is usable without configuring anything. In
AUTO the value is computed from live data by `core/AutoTuner.kt` and refreshed
on startup and every few seconds of scanning:

| Field | Derived from |
|---|---|
| `FEE %` | `standardCommission.taker` from `/api/v3/exchangeInfo` (VIP0 0.10% if absent) |
| `THRESHOLD.PROFIT %` | `3 x FEE` — the real three-leg break-even edge (`0` admits trades that lose to slippage alone) |
| `MIN` / `MAX` / `STEP` | The Kelly per-trade notional divided by the live price, floored onto the symbol's `LOT_SIZE` grid and raised to `minQty` |
| `SCANNING.DEPTH` | Smallest Binance-valid rung (5…5000) whose book still holds the trade, from the median resting notional of the top 20 levels |
| `CAP` | How many Kelly-sized trades the budget can fund back to back (max 10) |
| `STRATEGY` | `parallel` only when the triangle's three legs are already held, otherwise `linear` |
| `THRESHOLD.AGE` | p90 x 1.5 of observed book staleness, seeded from measured REST latency |
| `WINDOW ms` | Four execution deadlines - long enough for a better version of the same triangle to be offered |
| `FIRE MARGIN %` | One fee of extra edge: firing at the detection print spends the opportunity for nothing |
| `DROP MARGIN %` | Half a fee; always smaller than the fire margin, so the wait/drop band is never inverted |
| `TIGHT SPREAD bps` | 1.25x the median worst-leg spread the books have actually been showing |
| `IMBALANCE 0-1` | 0.6x the 90th-percentile top-of-book imbalance - tighter than typical, not unreachable |
| `CADENCE ms` | Twice the measured REST round trip; faster than that and the book is reacting to something |

Tapping a badge to `MANUAL` hands that one field back to you; it is never
overwritten again. The live reasoning is shown above the badges and in the log
under the `settings` tag, and **RE-DERIVE AUTO SETTINGS NOW** forces a refresh.

## Staged execution: arm, wait, fire

A triangle is priced from one order-book print and then sent as **three
sequential market orders**. By the time the round trip finishes it is trading
books that have already moved, so the percent that justified it may no longer
exist. The original engine fired on the first print that looked good and never
looked again.

By default the scanner now **arms** a qualifying triangle instead of sending
it, and re-evaluates it on every depth update that touches any of its three
books. That ping is free: the scan loop is already event-driven and already
re-evaluates exactly the triangles containing the ticker that moved. On every
ping the triangle is re-sized and re-priced from the books as they are right
now - never from the books it was detected on.

```
   identify            arm                    ping (on every book update)
  ---------  --------------------  ------------------------------------------
  percent   arm(percent, window,   re-size from live books
  clears     fire bar, drop bar)   re-project the percent
  gate                                 |
                                    +---+---+---+
                                    |   |   |
                                  FIRE WAIT ABANDON
                                 (send) (hold) (edge gone /
                                          explain) window closed)
```

### The two absolute rules

1. **No loss inside an opportunity.** Nothing is ever sent whose projected
   percent is below break-even. The floor is not configurable and no gate can
   override it - a gate can only ever *withhold* a trade, never authorise a
   losing one.
2. **Fire late only when the spread is also tight.** The microstructure gates
   below may only make the engine patient while crossing the books is cheap.
   On a wide spread, holding costs more than the patience is worth, so the
   triangle is sent immediately or dropped - it is never delayed on the
   strength of an imbalance alone.

### The gates

| Gate | What it measures | Effect |
| --- | --- | --- |
| Fire bar | projected percent | `WAIT` below it |
| Drop bar | projected percent | `ABANDON` - the edge is gone, not merely reduced |
| Window | time since detection | `ABANDON` - these are no longer the books that were measured |
| Freshness | age of the oldest leg | `WAIT` - a stale leg recovers on its own |
| Spread | widest per-leg spread, bps | not tight -> no deferral is permitted at all |
| Imbalance | top-of-book size skew | `WAIT`, but only while the spread is tight |
| Cadence | mean gap between book updates | `WAIT`, but only while the spread is tight |

Spread, imbalance and cadence are **gating heuristics, not predictions**. They
describe the book as it is right now. Any price move predictable from this feed
is visible to every competitor on the same `@depth@100ms` stream and arbitraged
away within a few hundred milliseconds; the imbalances that survive are the
ones caused by an order passing through, which makes them a description of
adverse selection rather than an edge. So the engine measures the cost of the
round trip on live books and refuses to fire unless it clears a margin. It
never tries to know where the price is going.

### Settings

**Settings -> Staged execution**. Every numeric field carries its own
AUTO/MANUAL badge like the rest of the app, and defaults to AUTO: `WINDOW ms`,
`FIRE MARGIN %`, `DROP MARGIN %`, `TIGHT SPREAD bps`, `IMBALANCE 0-1`,
`CADENCE ms` and `MAX ARMED`. Turn **Arm before firing** off to get the
original fire-immediately behaviour.

### Telemetry

The dashboard reports armed/total, fired, dropped, the median time to fire and
to drop, the median waits before a fire, and the reason for every recent
outcome. The median time-to-drop is the number that says whether waiting for a
better moment is worth anything on this market at all.

### Paper execution realism

The paper engine fills against live books, so with zero latency and zero
slippage it would fill every order at exactly the projected price: deadlines,
pre-flight aborts and unwinds could never fire, and none of the above could be
measured. Both values are therefore derived from the measured REST round trip
and the observed book spreads - **Settings -> Paper execution realism**, AUTO
by default.

Because every fill already walks the real book, the spread is priced honestly
and must not be charged twice. The slippage knob models only what happens
*while the order is in flight*: the half-spread a taker pays on arrival, scaled
by how many book-update intervals the order was exposed for. Before any book
has been observed the fallback is a typical 10bps spread rather than zero -
having no observation is not evidence of a frictionless market.

## Stage 3 research data

The scanner continuously writes two CSV files that decide whether Stage 3 (a
statistical model) is worth building, and what it would model:

| File | One row per | The Stage 3 question it answers |
|---|---|---|
| `arm_episodes_*.csv` | armed triangle | Do the book features at detection (spread, imbalance, cadence, imbalance sign) separate FIRED from ABANDONED episodes? Does waiting actually pay - is `end_percent` better than `armed_percent`? |
| `arm_summaries_*.csv` | 5-minute interval | Is there enough edge at all? Where do the fire/drop bars sit relative to the market's observed spread distribution? |

### The base rate

`arm_summaries_*.csv` also carries the distribution of the **best edge on the
board each cycle** - `edges_seen`, `edges_above_fire_bar`, `p10_percent`,
`median_percent`, `p90_percent`, `max_percent`.

This is the number every other decision depends on. Arm episodes only ever
record triangles that *already cleared the profit gate*, so on their own they
are a sample conditioned on success: they can say what happened to the
opportunities that looked good, and nothing at all about how often those are.
Without the distribution there is no evidence for what the arming bar should
be - it would be a guess - and no way to say how rare a given threshold is.

Read `edges_above_fire_bar / edges_seen` as the answer to "how often is this
reachable at all". If it is a tiny fraction over a long session, the bar is
too high to study, and the honest response is to lower it or to track
near-misses, not to model a regime that never occurs.

Both live in the app's external files directory under `research/` and both are
exportable from **Logs → Stage 3 research data → SHARE CSV** (system share
sheet - Drive, mail, anything installed). *NEW SESSION* starts a fresh file
pair; earlier sessions' files are kept. The log itself can be copied to the
clipboard or saved to `exports/` from the same screen.

What would justify Stage 3: a feature (or combination) that separates outcomes
with a margin larger than the fee, and a fire-bar that is reachable often
enough to matter. What would kill it: `end_percent` ≤ `armed_percent` across
episodes - then the gates should be loosened, not modelled.

## Paper Trading

- Default mode. The green **PAPER TRADING** banner is visible on every screen.
- Market orders are simulated against the live synchronized order book: fills
  walk real bids/asks, BNB fees accrue per `EXECUTION.FEE`, balances update,
  and thin books produce partial fills or rejections exactly like real
  execution would.
- No order request is ever sent to Binance in paper mode.

### Paper auto-trading (Kelly criterion)

Open **Settings → PAPER / LIVE → Paper Auto-Trading (Kelly Criterion)**:

1. Set the **budget in USDT** (seeded as your simulated USDT balance).
2. Set **P(win) %** — the minimum probability of net profit required to
   execute (default **80%**).
3. Toggle **AUTO** and press **APPLY**.

When enabled, every analyzed opportunity with positive expected percent is
evaluated by the Kelly criterion (see `kelly/KellyCriterion.kt`): realized
returns are modeled as normal around the engine's expected percent with
volatility σ estimated from your rolling paper outcomes (falls back to a
configurable default until enough samples exist). An opportunity executes only
when `P(net profit) ≥ threshold` **and** the Kelly fraction is positive.
Allocation is `budget × f*` clamped to the per-trade maximum; the base asset is
bought with USDT, the triangle executes (linear strategy), and the result is
sold back to USDT. The Dashboard shows equity, trades, win rate, P&L, σ, and
the last evaluated P(win).

## Live Trading

⚠️ **Live trading places real orders with real funds.** Triangle arbitrage is
latency-sensitive; retail connections usually lose to fees and slippage.

1. Store credentials (Settings).
2. Open **Settings → PAPER / LIVE**.
3. Type the confirmation phrase shown on screen.
4. Press **ENABLE LIVE TRADING** — the banner turns red everywhere.
5. Returning to paper is a single tap.

## Security

See [SECURITY.md](SECURITY.md). Summary: Keystore-encrypted credentials,
no secrets in logs/BuildConfig/screenshots-sensitive UI, live trading
double-gated, no telemetry.

## Testing

See [TESTING.md](TESTING.md). Summary: 47 unit tests including a
JavaScript-parity harness driven by vectors generated from the original
implementation (`validation/genVectors.js`), covering calculations, triangle
discovery, execution gating/strategies, paper-trading fills, and websocket
cache synchronization.

## Troubleshooting

| Symptom | Cause / Fix |
|---|---|
| Scanner stuck in `WAITING_FOR_DEPTH` | Some tickers have no active depth stream. Check *Connection* screen synced/total; restart scanner to refetch snapshots. |
| Frequent `out of sync` resyncs | Unstable network; the watchdog reconnects automatically. Consider Wi-Fi over mobile data. |
| `Bid/Ask depth too shallow` warnings | Normal when investment exceeds book depth. Reduce `INVESTMENT.MAX` or increase `SCANNING.DEPTH` (depth >100 requires Binance-weight awareness). |
| No opportunities | Normal — genuine triangles above 0.3% (fees×3) are rare. Lower `THRESHOLD.PROFIT` to 0 to see all evaluated positions. |
| Live orders rejected | Check key permissions, IP restrictions, and BNB balance for fees. Error codes appear in Logs (`binance` channel). |

## Attribution & License

Engine behavior, algorithms, and configuration semantics derive from
[binance-triangle-arbitrage](https://github.com/bmino/binance-triangle-arbitrage)
© 2018 Brandon Michael Mino (MIT). This port preserves that license and
attribution in [LICENSE](LICENSE).
