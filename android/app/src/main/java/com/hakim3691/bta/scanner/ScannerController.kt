package com.hakim3691.bta.scanner

import com.hakim3691.bta.core.ArbitrageExecution
import com.hakim3691.bta.core.CalculationNode
import com.hakim3691.bta.core.DepthSnapshot
import com.hakim3691.bta.core.ExecutionConfig
import com.hakim3691.bta.core.AgeThresholdTuner
import com.hakim3691.bta.core.ArmOutcome
import com.hakim3691.bta.core.ArmRecord
import com.hakim3691.bta.core.ArmingStats
import com.hakim3691.bta.core.ArmedOpportunityBook
import com.hakim3691.bta.core.AutoTuner
import com.hakim3691.bta.core.CadenceTracker
import com.hakim3691.bta.core.ExecutionGates
import com.hakim3691.bta.core.Microstructure
import com.hakim3691.bta.core.EdgeSamples
import com.hakim3691.bta.core.MicrostructureSamples
import com.hakim3691.bta.core.ExecutionState
import com.hakim3691.bta.core.CalculatedPosition
import com.hakim3691.bta.core.InvestmentSpec
import com.hakim3691.bta.core.MarketCache
import com.hakim3691.bta.core.SymbolInfo
import com.hakim3691.bta.core.OrderResponse
import com.hakim3691.bta.core.TradeExecutor
import com.hakim3691.bta.core.Trade
import com.hakim3691.bta.core.TradeFilter
import com.hakim3691.bta.kelly.KellyConfig
import com.hakim3691.bta.kelly.KellyCriterion
import com.hakim3691.bta.kelly.KellyPaperTrader
import com.hakim3691.bta.live.LiveTradingSession
import com.hakim3691.bta.ScannerForegroundService
import com.hakim3691.bta.log.LogLevel
import com.hakim3691.bta.log.LogRepository
import com.hakim3691.bta.market.BinanceRestClient
import com.hakim3691.bta.market.BinanceWebSocketClient
import com.hakim3691.bta.market.DepthCacheManager
import com.hakim3691.bta.market.WsStatus
import com.hakim3691.bta.paper.PaperTradingEngine
import com.hakim3691.bta.research.ArmEpisode
import com.hakim3691.bta.research.IntervalSummary
import com.hakim3691.bta.research.ResearchRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class TradingMode { PAPER, LIVE }

enum class ScannerState { STOPPED, INITIALIZING, RUNNING, WAITING_FOR_DEPTH, PAUSED_CAP_REACHED, ERROR }

/**
 * Application-level orchestrator: owns the MarketCache, DepthCacheManager,
 * websocket/REST clients, the CalculationNode pipeline, and the execution state
 * machine. Equivalent of Main.js + its wiring of BinanceApi/MarketCache/HUD.
 *
 * The engine is started only after the user presses Start; the default mode is
 * always PAPER.
 */
class ScannerController(
    private val credentialProvider: suspend () -> Pair<String, String>,
    /** App context, used to hold the foreground service while scanning. */
    private val appContext: android.content.Context? = null
) {

    companion object {
        /** Re-derive AUTO settings every N scan cycles (~a few seconds at typical rates). */
        const val AUTO_TUNE_EVERY_CYCLES = 250L
    /** Minimum gap between scan cycles, independent of how fast books move. */
    const val SCAN_TICK_MS = 150L
    /** Window the displayed cycle rate is measured over. */
    const val CYCLE_RATE_WINDOW_MS = 5_000L
    /** Repeat-line throttle for per-cycle scan chatter. */
    const val SKIP_LOG_THROTTLE_MS = 30_000L
    /** Even with nothing changing, restate the derived settings this often. */
    const val AUTO_TUNE_LOG_HEARTBEAT_MS = 300_000L
    /** Books sampled when measuring how deep the scan has to reach. */
    const val DEPTH_SAMPLE_SIZE = 40
    /** Investment specs priced when sizing the depth requirement. */
    const val NOTIONAL_SAMPLE_SIZE = 12

        /** Below this BNB balance the fee path silently changes on Binance. */
        const val LIVE_MIN_BNB = 0.001

        /** A leg may run up to this many of its OWN median update gaps before
         *  it is called stale - quiet books are slow, not dead. */
        const val QUIET_BOOK_TOLERANCE = 5.0

        /**
         * Triangles per cycle whose books are measured for the gate thresholds.
         * Sampling the best few candidates keeps the cost of the measurements
         * off the hot path while still describing the market rather than only
         * the trades that happened to be profitable.
         */
        const val SAMPLES_PER_CYCLE = 3

        /**
         * Dead-book probe: how long to wait for every ticker's initial REST
         * snapshot before giving up and probing with whatever synced.
         */
        const val PROBE_DEADLINE_MS = 60_000L

        /**
         * Dead-book probe: a ticker that produces no book update for this long
         * after its snapshot is excluded for the session. Deliberately far
         * longer than the quiet-book tolerance so a genuinely slow market is
         * not mistaken for a delisted one - the books this removes never tick
         * at all, for hours, in every run.
         */
        const val PROBE_WINDOW_MS = 45_000L

        /**
         * Phase 2b: a book silent this long at runtime is quarantined the same
         * way the startup probe excludes never-synced books. The watchdog runs
         * every 30s, so detection latency is 30-90s. Deliberately far above the
         * quiet-book tolerance for the same reason the probe window is.
         */
        const val STALE_TICKER_GRACE_MS = 120_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val depthCache = DepthCacheManager()
    private var wsClient: BinanceWebSocketClient? = null
    private var restClient: BinanceRestClient? = null

    private var marketCache = MarketCache()
    private var paperEngine: PaperTradingEngine? = null
    private var liveSession: LiveTradingSession? = null
    private var arbExecution: ArbitrageExecution? = null
    private var kellyTrader: KellyPaperTrader? = null

    private val _kellyStats = MutableStateFlow<KellyPaperTrader.Stats?>(null)
    val kellyStats: StateFlow<KellyPaperTrader.Stats?> = _kellyStats

    private val _lastExecutedOpportunity = MutableStateFlow<OpportunityUi?>(null)
    val lastExecutedOpportunity: StateFlow<OpportunityUi?> = _lastExecutedOpportunity

    private val _gateStatus = MutableStateFlow("idle")
    val gateStatus: StateFlow<String> = _gateStatus

    private val _wsMessages = MutableStateFlow(0L)
    val wsMessages: StateFlow<Long> = _wsMessages

    /** Self-tuning execution freshness limit (mobile networks vary widely). */
    private val ageTuner = AgeThresholdTuner()
    private val _ageThresholdInfo = MutableStateFlow("auto: measuring...")
    val ageThresholdInfo: StateFlow<String> = _ageThresholdInfo

    private val _autoTuneInfo = MutableStateFlow("auto: waiting for market data ...")
    val autoTuneInfo: StateFlow<String> = _autoTuneInfo

    private val _feedHealth = MutableStateFlow(FeedHealth())
    val feedHealth: StateFlow<FeedHealth> = _feedHealth

    /** Executions that could not be unwound and need closing on the exchange. */
    private val _manualCloseRequired = MutableStateFlow<List<String>>(emptyList())
    val manualCloseRequired: StateFlow<List<String>> = _manualCloseRequired

    // ------------------------------------------------------------------
    // Research collection for the Stage 3 decision
    // ------------------------------------------------------------------

    /** CSV collector for arm episodes and interval summaries (see ResearchRecorder). */
    var researchRecorder: ResearchRecorder? = null
        private set

    private val _researchInfo = MutableStateFlow("research: not collecting")
    val researchInfo: StateFlow<String> = _researchInfo

    private var summaryAnchor = 0L
    private var summaryArmsCreated = 0
    private var summaryFired = 0
    private var summaryAbandoned = 0
    private var summaryExpired = 0
    private var summaryOpportunities = 0

    /**
     * Attaches CSV collection in the app's external files directory.
     * Called from start() when a context is available; harmless to call again.
     */
    private fun ensureResearchRecorder() {
        if (researchRecorder != null) return
        val ctx = appContext
        if (ctx == null) {
            // Loudly, because silence here is what made the Stage 3 study look
            // like a market result when it was really a wiring bug.
            LogRepository.error(
                "research",
                "CSV collection unavailable: no application context - arm episodes and " +
                    "interval summaries will NOT be recorded this session"
            )
            _researchInfo.value = "research: unavailable (no context)"
            return
        }
        try {
            val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val dir = java.io.File(base, "research")
            if (!dir.exists() && !dir.mkdirs()) {
                LogRepository.error("research", "Could not create " + dir.absolutePath)
                _researchInfo.value = "research: could not create output directory"
                return
            }
            researchRecorder = ResearchRecorder(dir).also { it.newSession() }
            LogRepository.info(
                "research",
                "Collecting arm episodes + interval summaries to ${dir.absolutePath}"
            )
            _researchInfo.value = "research: collecting (5-min summaries)"
        } catch (e: Exception) {
            LogRepository.error("research", "Could not start CSV collection: ${e.message}")
            _researchInfo.value = "research: failed to start"
        }
    }

    /** One episode row per finished arm, with the features it was armed on. */
    private fun recordArmEpisode(
        id: String,
        outcome: String,
        arm: com.hakim3691.bta.core.ArmedOpportunity,
        features: com.hakim3691.bta.core.TriangleFeatures,
        verdict: ExecutionGates.Verdict
    ) {
        val recorder = researchRecorder ?: return
        val worst = maxOf(features.ab.imbalance, features.bc.imbalance, features.ca.imbalance)
        val sign = if (worst > 0.0) 1 else if (worst < 0.0) -1 else 0
        recorder.recordEpisode(
            ArmEpisode(
                epochMs = System.currentTimeMillis(),
                triangleId = id,
                armedPercent = arm.armedPercent,
                fireBar = arm.fireBar,
                abandonBar = arm.abandonBar,
                ttlMs = arm.ttlMs,
                armSpreadBps = features.maxSpreadBps,
                armImbalance = features.maxAbsImbalance,
                armInterArrivalMs = features.maxInterArrivalMs,
                armBookImbalanceSign = sign,
                outcome = outcome,
                endPercent = verdict.projectedPercent,
                bestPercent = arm.bestPercent,
                waitedMs = arm.ageMs(System.currentTimeMillis()),
                pingCount = arm.pings,
                waitCount = arm.waits,
                verdictReason = verdict.reason
            )
        )
    }

    /**
     * An arm that outlived its TTL is a distinct outcome - "held the whole
     * window and never made it" - and it used to be logged but never recorded,
     * so the CSV only ever contained the episodes that ended in a decision.
     */
    private fun recordExpiredEpisode(record: com.hakim3691.bta.core.ArmRecord) {
        val recorder = researchRecorder ?: return
        val f = record.features
        recorder.recordEpisode(
            ArmEpisode(
                epochMs = System.currentTimeMillis(),
                triangleId = record.id,
                armedPercent = record.armedPercent,
                fireBar = ExecutionConfig.profitThreshold + ExecutionConfig.armingMarginPercent,
                abandonBar = ExecutionConfig.profitThreshold - ExecutionConfig.abandonMarginPercent,
                ttlMs = ExecutionConfig.armTtlMs,
                armSpreadBps = f?.maxSpreadBps ?: 0.0,
                armImbalance = f?.maxAbsImbalance ?: 0.0,
                armInterArrivalMs = f?.maxInterArrivalMs ?: 0.0,
                armBookImbalanceSign = 0,
                outcome = "EXPIRED",
                endPercent = record.firedPercent,
                bestPercent = record.bestPercent,
                waitedMs = record.durationMs,
                pingCount = record.pings,
                waitCount = record.waits,
                verdictReason = record.reason
            )
        )
    }

    /** One context row per interval: is arming working, and where do the bars sit? */
    private fun maybeRecordSummary(now: Long, opportunitiesSeen: Int, force: Boolean = false) {
        val recorder = researchRecorder ?: return
        summaryOpportunities += opportunitiesSeen
        if (summaryAnchor == 0L) summaryAnchor = now
        if (!force && now - summaryAnchor < recorder.intervalSec * 1000L) return
        val stats = armBook.snapshot()
        recorder.recordSummary(
            IntervalSummary(
                epochMs = now,
                intervalSec = recorder.intervalSec,
                opportunitiesSeen = summaryOpportunities,
                armsCreated = stats.episodes.toInt(),
                fired = stats.fired.toInt(),
                abandoned = stats.abandoned.toInt(),
                expired = stats.expired.toInt(),
                medianWaitMsFired = stats.medianTimeToFireMs,
                medianArmedPercent = stats.medianArmedPercent,
                medianFiredPercent = stats.medianFiredPercent,
                medianBestPercent = stats.medianBestPercent,
                p10SpreadBps = microstructureSamples.spreadPercentile(0.10),
                medianSpreadBps = microstructureSamples.spreadPercentile(0.50),
                p90SpreadBps = microstructureSamples.spreadPercentile(0.90),
                p90Imbalance = microstructureSamples.imbalancePercentile(0.90),
                fireBar = stats.fireBarPercent,
                abandonBar = stats.abandonBarPercent,
                tightSpreadBps = ExecutionConfig.spreadTightMaxBps,
                imbalanceLimit = ExecutionConfig.imbalanceMaxAbs,
                cadenceLimitMs = ExecutionConfig.cadenceMaxInterArrivalMs,
                ttlMs = ExecutionConfig.armTtlMs,
                medianInterArrivalMs = medianInterArrivalMs(),
                scanCycles = cycleCounter.get(),
                edgesSeen = edgeSamples.size(),
                edgesAboveFireBar = edgeSamples.countAtOrAbove(stats.fireBarPercent),
                p10Percent = edgeSamples.percentile(0.10),
                medianPercent = edgeSamples.percentile(0.50),
                p90Percent = edgeSamples.percentile(0.90),
                maxPercent = edgeSamples.max()
            )
        )
        summaryAnchor = now
        summaryOpportunities = 0
    }

    /** Cached so the hot scan path can skip work without recomputing. */
    @Volatile private var feedStale = false

    // ------------------------------------------------------------------
    // Staged execution: arm -> wait -> fire
    // ------------------------------------------------------------------

    /**
     * Book update cadence per ticker. Feeds the "is this book in flux" gate.
     * Fed from the depth stream itself, so it measures what the books are
     * actually doing rather than what was configured.
     */
    private val cadence = CadenceTracker()

    /** Rolling book measurements used to derive the gate thresholds. */
    private val microstructureSamples = MicrostructureSamples()

    /** Best edge seen per cycle. The base rate Stage 3 is decided against. */
    private val edgeSamples = EdgeSamples()

    /** Set once the first snapshot lands, so resyncs can tell sync from recovery. */
    @Volatile private var initialSyncComplete = false
    /** Whether the feed has actually been unhealthy since the last resync. */
    @Volatile private var sawDegradedFeed = false

    /** Triangles recognised but not yet sent. */
    private val armBook = ArmedOpportunityBook()

    /**
     * Observed opportunity lifetimes: how long an identified edge stayed
     * alive before it fired or collapsed. The arm window is a claim about the
     * market, so it is derived from this rather than from our own latency.
     */
    private val opportunityLifetimes = MicrostructureSamples(capacity = 100)

    /** Observed median book-update gap across the universe, for the cadence ceiling. */
    private val cadenceSamples = MicrostructureSamples(capacity = 500)

    private fun medianInterArrivalMs(): Double {
        // The tracker has per-ticker EMAs; sample them through the same
        // median machinery the other gates use.
        val values = cadence.tickersSnapshot()
        return MicrostructureSamples.median(values)
    }

    private fun medianOpportunityLifetimeMs(): Double =
        opportunityLifetimes.medianStored()

    private val _armingStats = MutableStateFlow(ArmingStats())
    val armingStats: StateFlow<ArmingStats> = _armingStats

    private val _armLog = MutableStateFlow<List<ArmRecord>>(emptyList())
    val armLog: StateFlow<List<ArmRecord>> = _armLog

    private val _mode = MutableStateFlow(TradingMode.PAPER)
    val mode: StateFlow<TradingMode> = _mode

    private val _state = MutableStateFlow(ScannerState.STOPPED)
    val state: StateFlow<ScannerState> = _state

    private val _connection = MutableStateFlow(ConnectionStatus())
    val connection: StateFlow<ConnectionStatus> = _connection

    private val _opportunities = MutableStateFlow<List<OpportunityUi>>(emptyList())
    val opportunities: StateFlow<List<OpportunityUi>> = _opportunities

    private val _currentOpportunity = MutableStateFlow<OpportunityUi?>(null)
    val currentOpportunity: StateFlow<OpportunityUi?> = _currentOpportunity

    private val _executionStates = MutableStateFlow<List<ExecutionState>>(emptyList())
    val executionStates: StateFlow<List<ExecutionState>> = _executionStates

    private val _performance = MutableStateFlow(ScanPerformance())
    val performance: StateFlow<ScanPerformance> = _performance

    private val _tradesFeed = MutableStateFlow<List<OpportunityUi>>(emptyList())
    val tradesFeed: StateFlow<List<OpportunityUi>> = _tradesFeed

    private val _paperBalances = MutableStateFlow<Map<String, Double>>(emptyMap())
    val paperBalances: StateFlow<Map<String, Double>> = _paperBalances

    val paperFillLog
        get() = paperEngine?.fillLog

    private val cycleCounter = AtomicLong(0)
    private var updateJob: Job? = null
    private var latencyJob: Job? = null
    private var bnbWatchdog: Job? = null
    private var stalenessJob: Job? = null
    private var statusJob: Job? = null
    private var syncJob: Job? = null
    private var wsCountJob: Job? = null

    /** Phase 2b: quarantine/revival watchdog loop. */
    private var quarantineJob: Job? = null

    /**
     * Phase 2b: tickers currently excluded by the runtime staleness monitor.
     * Distinct from the probe's one-shot dead set: this population can shrink
     * again when a book revives, so each change rebuilds the universe.
     */
    private val quarantinedTickers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var initializedAt = 0L

    /** Full exchangeInfo snapshot for the session; the dead-book probe re-filters it. */
    private var exchangeSymbols: List<SymbolInfo> = emptyList()

    /** Guards the startup probe against a stop() landing mid-window. */
    @Volatile private var stopRequested = false

    /**
     * Feed-resync handling shared by the initial connect and any
     * re-subscription after the dead-book rebuild: a reconnect proves the
     * books we were pricing against are gone, so drop everything the tuner
     * learned while the feed was down.
     */
    private val feedResyncHandler: () -> Unit = {
        ageTuner.reset()
        cadence.reset()
        if (initialSyncComplete) {
            // Only a genuine recovery is worth a WARN. Resyncs also
            // happen on the initial snapshot and on routine REST
            // re-fetches, and calling those a recovery cried wolf on
            // every single start.
            if (sawDegradedFeed) {
                LogRepository.warn(
                    "binance",
                    "Feed recovered - age gate re-learning from fresh books"
                )
            } else {
                LogRepository.debug("binance", "Books resynced - feed still healthy")
            }
        } else {
            LogRepository.info("binance", "Initial depth snapshot synced")
            initialSyncComplete = true
        }
        sawDegradedFeed = false
        refreshFeedHealth()
    }

    // ------------------------------------------------------------------
    // Start / stop
    // ------------------------------------------------------------------

    suspend fun start() {
        if (_state.value == ScannerState.RUNNING || _state.value == ScannerState.INITIALIZING) return
        try {
            _state.value = ScannerState.INITIALIZING
            stopRequested = false
            // Attach CSV collection before anything can record. This call was
            // missing entirely, so researchRecorder stayed null for the whole
            // session and every recorder guarded by `?: return` dropped its rows
            // silently - the Stage 3 panel read "0 rows" forever with no error
            // anywhere, and the summary CSV that decides whether a model is
            // warranted was never written.
            ensureResearchRecorder()
            LogRepository.info("main", "Checking latency ...")

            // Credentials are resolved OUTSIDE the keystore path so a broken
            // EncryptedSharedPreferences instance (the documented failure mode
            // after a process is frozen and discarded in the background) cannot
            // block a PAPER session: the simulator needs no keys at all. This
            // used to be the first statement inside the try, so the only thing
            // the user ever saw after reopening the app was ERROR with
            // WEBSOCKET DISCONNECTED / REST DOWN and a 0 ms latency - the
            // failure happened before the first REST call was ever made.
            val credentials = runCatching { credentialProvider() }
                .onFailure {
                    LogRepository.warn(
                        "main",
                        "Secure credential store unavailable (${it.javaClass.simpleName}); " +
                            "continuing without API keys - paper mode works, live trading and " +
                            "the account fee rate do not"
                    )
                }
                .getOrDefault("" to "")
            val (apiKey, apiSecret) = credentials
            val rest = BinanceRestClient(http, { apiKey }, { apiSecret })
            restClient = rest

            // SpeedTest.multiPing(5) equivalent + clock skew measurement
            val pings = mutableListOf<Long>()
            repeat(5) { pings.add(rest.ping()) }
            val avg = pings.average()
            LogRepository.info("performance", "Experiencing ${avg.toInt()} ms of latency")

            // Seed the self-tuning age threshold with observed latency
            ageTuner.seedFromLatency(avg.toLong())

            // Measure server-vs-device clock skew so opportunity ages stay >= 0
            runCatching {
                val before = System.currentTimeMillis()
                val serverTime = rest.time()
                depthCache.updateClockSkew(serverTime, System.currentTimeMillis())
                // The same measurement drives signed-request timestamps, so a
                // device clock drifting more than the recvWindow does not fail
                // every live order with -1021 - which in this engine happens
                // mid-triangle, at the worst possible moment.
                rest.updateServerTimeOffset(serverTime, System.currentTimeMillis())
                LogRepository.info(
                    "main",
                    "Clock skew vs Binance: ${depthCache.clockSkewMs} ms (server ${if (depthCache.clockSkewMs > 0) "ahead" else "behind"}); " +
                        "signed requests use server time"
                )
            }
            _connection.value = _connection.value.copy(restOk = true, latencyMs = avg.toLong())

            LogRepository.info("main", "Fetching exchange info ...")
            val symbols = rest.exchangeInfo()
            exchangeSymbols = symbols
            marketCache = MarketCache(whitelist = emptySet(), executionTemplate = listOf("*", "*", "*"))
            // The universe is discovered from the live pair graph, not from a
            // configured base: passing an empty restriction means every asset
            // the exchange lists can root a triangle. The old code passed the
            // Settings base here, which is why every opportunity the scanner
            // ever reported started and ended with BTC regardless of market.
            val result = marketCache.initialize(symbols, emptySet())
            LogRepository.info(
                "main",
                "Found ${result.tradingSymbolCount}/${result.totalSymbolCountFound} currently trading tickers"
            )
            LogRepository.info(
                "main",
                "Found ${result.trades.size} triangular trades across " +
                    "${result.bases.size} base assets (largest: ${result.referenceBase})"
            )
            if (marketCache.resultMirrorsSkipped > 0) {
                LogRepository.info(
                    "main",
                    "De-duplicated ${marketCache.resultMirrorsSkipped} mirrored triangles " +
                        "(A-B-C == A-C-B): " +
                        "turned ${result.trades.size + marketCache.resultMirrorsSkipped} candidates " +
                        "into ${result.trades.size} unique round trips"
                )
            }

            if (result.trades.isEmpty()) {
                _state.value = ScannerState.ERROR
                LogRepository.error("execution", "No triangular trades were identified")
                return
            }

            // Paper universe for the simulator (base/quote per ticker).
            // Rebuilt from scratch each start: a previous session's tickers
            // must not linger, or the simulator keeps markets that no longer
            // exist and the funding leg can target a stale pair.
            PaperTradingEngine.paperUniverse.clear()
            for ((ticker, info) in result.tradingSymbols) {
                PaperTradingEngine.paperUniverse[ticker] = info.baseAsset to info.quoteAsset
            }

            // Configure balance simulation
            // Friction is derived from the measured round trip and the
            // observed spreads, not left at zero: a frictionless simulator
            // fills every order at exactly the projected price, so deadlines,
            // pre-flight guards and unwinds could never fire and the staged
            // executor would be tuning itself against a fiction.
            paperEngine = PaperTradingEngine(
                depthProvider = { ticker -> depthCache.getSortedSnapshot(ticker, ExecutionConfig.scanningDepth) },
                startingBalance = defaultPaperBalance(result.bases),
                latencyMs = ExecutionConfig.paperLatencyMs.toLong(),
                slippagePercent = ExecutionConfig.paperSlippagePercent
            )
            // Seed the simulated portfolio from the persisted USDT budget so
            // the Kelly ledger always starts from the user's configured amount
            paperEngine!!.balances["USDT"] = KellyConfig.budgetUsdt
            _paperBalances.value = paperEngine!!.balances.toMap()
            arbExecution = ArbitrageExecution(paperEngine!!).also { exec ->
                exec.shutdownListener = { _state.value = ScannerState.PAUSED_CAP_REACHED }
            }
            kellyTrader = KellyPaperTrader(
                paperEngine = paperEngine!!,
                arbExecution = arbExecution!!,
                onTradeCompleted = { state, calculated ->
                    recordExecution(state, calculated)
                    _lastExecutedOpportunity.value = OpportunityUi.from(calculated, System.currentTimeMillis())
                },
                onStatsChanged = { _kellyStats.value = kellyTrader?.snapshotStats() }
            )
            _kellyStats.value = kellyTrader!!.snapshotStats()

            // Register watched tickers
            depthCache.register(result.watching)

            // Open websocket and fetch snapshots (staggered init equivalent: fetch in batches)
            val validDepth = BinanceWebSocketClient.resolveValidDepth(ExecutionConfig.scanningDepth)
            wsClient = BinanceWebSocketClient(http, depthCache, validDepth)
            wsClient!!.onResync = feedResyncHandler
            _connection.value = _connection.value.copy(
                totalTickers = result.watching.size,
                syncedTickers = 0
            )
            LogRepository.info(
                "binance",
                "Opening depth websocket for ${result.watching.size} tickers (depth $validDepth) ..."
            )
            wsClient!!.connect(result.watching)

            // Fetch initial snapshots in staggered batches of 50, mirroring mapLimit(symbols, 50, ...)
            result.watching.chunked(50).forEachIndexed { idx, batch ->
                batch.forEach { sym -> wsClient!!.requestSnapshot(sym) }
                delay(200)
            }

            _state.value = ScannerState.WAITING_FOR_DEPTH

            // Real account fee rate (Phase 4c), replacing the 0.10% guess when
            // keys exist. One weight-1 signed call; gracefully skipped without.
            fetchRealFeeRate(rest)

            // Dead-book probe (Phase 2a). The initial snapshots are in flight,
            // so the next ~minute tells us which tickers the exchange actually
            // streams for. Anything that never produces a single update is a
            // permanently dead book - every triangle through it would be
            // skipped as stale on every cycle for the whole session, padding
            // the universe, the idle count and the degraded-feed banner with
            // markets that cannot be traded. Excluded once, here, and never
            // revisited: the universe is rebuilt without them before the first
            // scan cycle runs.
            probeAndPrune()
            if (stopRequested) return

            // Subscribe to depth updates -> run arbitrage cycle (port of arbitrageCycleCallback)
            updateJob = scope.launch {
                depthCache.updates.collect { event ->
                    onDepthUpdate(event.ticker)
                }
            }

            // Publish websocket status + sync progress reactively
            statusJob = scope.launch {
                wsClient?.status?.collect { s ->
                    _connection.value = _connection.value.copy(wsStatus = s.name)
                }
            }
            syncJob = scope.launch {
                depthCache.syncedCount.collect { synced ->
                    _connection.value = _connection.value.copy(syncedTickers = synced)
                }
            }
            wsCountJob = scope.launch {
                wsClient?.messagesReceived?.collect { _wsMessages.value = it }
            }

            // Periodic status update + latency monitoring (port of displayStatusUpdate)
            // Tracks whether the books can be trusted, so the scan loop can
            // refuse to burn CPU on frozen data.
            stalenessJob = scope.launch {
                while (isActive) {
                    delay(2_000)
                    refreshFeedHealth()
                    expireStaleArms()
                }
            }

            // Phase 2b watchdog: a book that went silent at runtime joins the
            // same exclusion as the startup probe's dead books, and a revived
            // book re-enters the counts automatically. Bookkeeping only - no
            // resubscription - so it is cheap enough to run often.
            quarantineJob = scope.launch {
                while (isActive) {
                    delay(30_000)
                    if (!stopRequested && _state.value == ScannerState.RUNNING) {
                        runCatching { quarantineAndRecheckStaleTickers() }
                            .onFailure {
                                LogRepository.error(
                                    "universe",
                                    "Staleness watchdog failed: " + LogRepository.stackTrace(it)
                                )
                            }
                    }
                }
            }

            latencyJob = scope.launch {
                while (true) {
                    delay(120_000)
                    val latency = runCatching { rest.ping() }.getOrDefault(-1L)
                    // Re-measure the server offset alongside the latency so a
                    // drifting device clock is tracked over a long session.
                    runCatching {
                        val before = System.currentTimeMillis()
                        val serverTime = rest.time()
                        rest.updateServerTimeOffset(serverTime, System.currentTimeMillis())
                        depthCache.updateClockSkew(serverTime, before)
                    }
                    _connection.value = _connection.value.copy(latencyMs = latency)
                    val stale = depthCache.getTickersWithoutRecentUpdate(120_000)
                    if (stale.isNotEmpty()) {
                        LogRepository.warn(
                            "performance",
                            "Tickers without recent depth cache update: [${stale.sorted().take(20).joinToString(",")}]"
                        )
                    }
                    LogRepository.debug(
                        "performance",
                        "Depth cache updates per second: ${_performance.value.cyclesPerSecond}"
                    )
                }
            }

            updateJob?.let { _state.value = ScannerState.RUNNING }
            initializedAt = System.currentTimeMillis()

            // Hold a foreground service for as long as the scanner runs, so
            // backgrounding the app cannot freeze the process mid-triangle.
            appContext?.let { ctx ->
                ScannerForegroundService.ensureChannel(ctx)
                ScannerForegroundService.start(ctx)
            }

            // Derive fee / lot size / depth / cap / strategy before the first cycle
            runAutoTune("startup")

            // Prime the loop counter with the full combination universe
            loopTracker.reset(marketCache.result.trades.map { it.id })
            publishArming()
            summaryAnchor = 0L
            summaryOpportunities = 0
        } catch (e: Exception) {
            _state.value = ScannerState.ERROR
            LogRepository.error(
                "main",
                "Initialization failed: " + e.javaClass.name + ": " + e.message +
                    " | " + LogRepository.stackTrace(e)
            )
            return
        }
    }

    /**
     * Phase 4c: the fee the projections actually deserve.
     *
     * exchangeInfo publishes no commission, so the scanner previously ran on a
     * 0.10% guess. With API keys present, GET /sapi/v1/asset/tradeFee returns
     * the account's real taker rate - including the BNB-discount 0.075% that
     * materially changes whether a marginal edge survives three legs. Keys are
     * optional: without them the configured fallback stays and the skip is
     * logged once.
     */
    private suspend fun fetchRealFeeRate(rest: BinanceRestClient) {
        try {
            val rate = rest.accountTakerCommission()
            if (rate == null) {
                LogRepository.info(
                    "settings",
                    "Fee rate: no API key or no tradeFee row; keeping ${ExecutionConfig.feePercent}% fallback"
                )
                return
            }
            val percent = rate * 100.0
            if (percent > 0.0) {
                val previous = ExecutionConfig.feePercent
                if (!ExecutionConfig.feeAuto) {
                    LogRepository.info(
                        "settings",
                        "Fee rate ${percent}% fetched but fee is MANUAL; keeping ${previous}%"
                    )
                    return
                }
                ExecutionConfig.feePercent = percent
                LogRepository.info(
                    "settings",
                    "Fee rate from account: ${percent}% taker (was ${previous}% fallback)"
                )
            }
        } catch (e: Exception) {
            LogRepository.info(
                "settings",
                "Fee rate unavailable: ${e.javaClass.simpleName}: ${e.message}; keeping " +
                    "${ExecutionConfig.feePercent}% fallback"
            )
        }
    }

    /**
     * Phase 2b: runtime staleness quarantine.
     *
     * The startup probe (Phase 2a) classifies books once, from the
     * never-produced-a-snapshot signal. It cannot see a book that streamed
     * normally for an hour and then went silent - and a silently frozen book is
     * worse than a dead one, because isSynced stays true and the freshness gate
     * alone would either halt the whole scan (feedDead) or drag the banner to
     * DEGRADED forever.
     *
     * Quarantine is therefore bookkeeping-level: a long-silent book is excluded
     * from the freshness counts and flagged, but its feed subscription and
     * depth cache stay alive - so revival is directly observable (ageOf drops
     * back under the gate the moment its shard reconnects and resnapshots) and
     * re-admission is automatic. Rebuilding the universe here instead would
     * prune the very context that proves the book came back.
     *
     * Books are only quarantined once their cadence has been learned, and never
     * when their own median gap excuses them - quiet markets are slow, not
     * broken, and they are exactly where dislocations live.
     */
    private fun quarantineAndRecheckStaleTickers() {
        // After a feed resync the cadence map is deliberately reset; give the
        // universe a few seconds to re-record arrival gaps before classifying.
        if (cadence.tickers() < 5) return
        val maxAge = ExecutionConfig.ageThresholdMs.toLong()
        val now = System.currentTimeMillis()
        val frozen = depthCache.getTickersWithoutRecentUpdate(STALE_TICKER_GRACE_MS, now)
            .filter { ticker ->
                // A never-seen ticker (no cadence) is the startup probe's
                // population, not this monitor's; a quiet-but-alive book ticks
                // slower than the gate by nature and is excused per-leg.
                val gap = cadence.interArrivalMs(ticker)
                gap > 0.0 && gap * QUIET_BOOK_TOLERANCE < maxAge
            }
        val newlyQuarantined = frozen.filter { it !in quarantinedTickers }
        if (newlyQuarantined.isNotEmpty()) {
            newlyQuarantined.forEach { quarantinedTickers.add(it) }
            val sample = newlyQuarantined.sorted().take(20).joinToString(",")
            LogRepository.warn(
                "universe",
                "Quarantined ${newlyQuarantined.size} silent tickers (no update in " +
                    "${STALE_TICKER_GRACE_MS / 1000}s): [$sample]"
            )
        }
        // Re-admission: a quarantined ticker that produced a fresh update is
        // alive again - its shard reconnected and resnapshotted. It rejoins the
        // freshness counts and its triangles resume being priced.
        if (quarantinedTickers.isNotEmpty()) {
            val revived = quarantinedTickers.filter { ticker ->
                depthCache.ageOf(ticker, now)?.let { it <= maxAge } == true
            }
            if (revived.isNotEmpty()) {
                quarantinedTickers.removeAll(revived.toSet())
                LogRepository.info(
                    "universe",
                    "Re-admitted ${revived.size} revived tickers: ${revived.sorted().take(20)}"
                )
            }
        }
    }

    /**
     * Phase 2a: dead-book probe.
     *
     * Runs once, between the initial snapshot fetch and the first scan cycle.
     * A ticker is declared dead when it has produced no book update for
     * [PROBE_WINDOW_MS] after its snapshot landed - which is exactly the
     * population that made every previous session log
     * "Tickers without recent depth cache update" for the entire run while
     * their triangles were skipped as stale on every single cycle.
     *
     * A quiet-but-live book (one that updates rarely) is not dead: it ticks at
     * least once inside the window and survives. Only books the exchange
     * simply does not stream for are removed.
     */
    private suspend fun probeAndPrune() {
        val watching = marketCache.result.watching
        LogRepository.info(
            "main",
            "Waiting for all tickers to receive initial depth snapshot " +
                "(dead-book probe: up to ${PROBE_DEADLINE_MS / 1000}s for snapshots, " +
                "${PROBE_WINDOW_MS / 1000}s of book updates) ..."
        )
        val deadline = System.currentTimeMillis() + PROBE_DEADLINE_MS
        while (depthCache.syncedTickers() < watching.size &&
            System.currentTimeMillis() < deadline && !stopRequested
        ) {
            delay(500)
        }
        delay(PROBE_WINDOW_MS)
        if (stopRequested) return

        // A snapshot can fail transiently - a rate-limited second or a network
        // hiccup. A ticker that never synced gets one retry round before it can
        // be called dead, so a bad REST moment cannot permanently drop a live
        // book from the universe.
        val neverSynced = watching.filter { !depthCache.isSynced(it) }
        if (neverSynced.isNotEmpty()) {
            LogRepository.info(
                "universe",
                "Dead-book probe: ${neverSynced.size} tickers never received a snapshot; retrying once before exclusion"
            )
            neverSynced.chunked(50).forEach { batch ->
                batch.forEach { sym -> wsClient?.requestSnapshot(sym) }
                delay(200)
            }
            delay(5_000)
            if (stopRequested) return
        }

        val dead = depthCache.getTickersWithoutRecentUpdate(PROBE_WINDOW_MS).toSet()
        if (dead.isEmpty()) {
            LogRepository.info(
                "universe",
                "Dead-book probe: every one of ${watching.size} tickers produced a book update; universe unchanged"
            )
            return
        }
        val sorted = dead.sorted()
        val sample = sorted.take(40).joinToString(",") + if (sorted.size > 40) ", ..." else ""
        LogRepository.warn(
            "universe",
            "Dead-book probe: ${dead.size} of ${watching.size} tickers produced no update in " +
                "${PROBE_WINDOW_MS / 1000}s; excluding permanently for this session: [$sample]"
        )
        rebuildUniverse(dead)
    }

    /**
     * Rebuilds the entire universe with [deadTickers] excluded.
     *
     * This is the "never revisited" half of the dead-book filter: the excluded
     * books are dropped from the triangle universe, from the related-trade
     * indexes, from the depth cache, and from the websocket subscription - so
     * no later cycle can spend work on them and nothing can re-add them this
     * session. A new session re-probes from scratch.
     */
    private suspend fun rebuildUniverse(deadTickers: Set<String>) {
        val keptSymbols = exchangeSymbols.filter { it.symbol !in deadTickers }
        marketCache.initialize(keptSymbols, emptySet())
        val result = marketCache.result

        depthCache.pruneTo(result.watching)
        PaperTradingEngine.paperUniverse.clear()
        for ((ticker, info) in result.tradingSymbols) {
            PaperTradingEngine.paperUniverse[ticker] = info.baseAsset to info.quoteAsset
        }

        // Re-subscribe to the surviving set only: the dead books are gone from
        // the feed, not merely ignored by the scanner.
        val validDepth = BinanceWebSocketClient.resolveValidDepth(ExecutionConfig.scanningDepth)
        wsClient?.close()
        wsClient = BinanceWebSocketClient(http, depthCache, validDepth)
        wsClient!!.onResync = feedResyncHandler
        statusJob?.cancel()
        statusJob = scope.launch {
            wsClient?.status?.collect { s ->
                _connection.value = _connection.value.copy(wsStatus = s.name)
            }
        }
        wsCountJob?.cancel()
        wsCountJob = scope.launch {
            wsClient?.messagesReceived?.collect { _wsMessages.value = it }
        }
        _connection.value = _connection.value.copy(
            totalTickers = result.watching.size,
            syncedTickers = depthCache.syncedTickers()
        )
        LogRepository.info(
            "binance",
            "Re-subscribing depth websocket for ${result.watching.size} surviving tickers ..."
        )
        wsClient!!.connect(result.watching)
        result.watching.chunked(50).forEach { batch ->
            batch.forEach { sym -> wsClient!!.requestSnapshot(sym) }
            delay(200)
        }
        if (stopRequested) {
            wsClient?.close()
            return
        }

        LogRepository.info(
            "universe",
            "Universe rebuilt: ${result.trades.size} triangles across ${result.bases.size} base assets " +
                "(largest: ${result.referenceBase}); watching ${result.watching.size} tickers"
        )
        refreshFeedHealth()
    }

    /**
     * Reference pair for an asset: its USDT pair when one exists, otherwise any
     * live pair it takes part in. Used for sizing and lot grids, never as a
     * restriction on the universe.
     */
    private fun referenceSymbolInfo(asset: String): SymbolInfo? {
        val trading = marketCache.result.tradingSymbols
        trading[asset + "USDT"]?.let { return it }
        trading.values.firstOrNull { it.baseAsset == asset }?.let { return it }
        return trading.values.firstOrNull { it.quoteAsset == asset }
    }

    /**
     * Populates [InvestmentSpec.DEFAULTS] for every base asset the discovered
     * universe roots a triangle at, each sized to [perTradeUsdt] at that
     * asset's own USDT price.
     *
     * In AUTO investment mode every spec is re-derived here. In MANUAL mode the
     * operator's configured base is left verbatim and only the bases that have
     * no configured spec are filled in - without them, [CalculationNode.optimize]
     * would throw for every triangle rooted outside the configured asset.
     */
    private fun seedInvestmentSpecs(perTradeUsdt: Double) {
        if (marketCache.result.bases.isEmpty()) return
        val prices = usdtValues()
        val lots = lotFilters()
        var unpriceable = 0
        for (base in marketCache.result.bases) {
            if (!ExecutionConfig.investmentAuto && base in InvestmentSpec.DEFAULTS) continue
            val lot = lots[base]
            val (min, max, step) = AutoTuner.deriveInvestment(
                base, perTradeUsdt, prices[base] ?: 0.0,
                lot?.first ?: 0.0, lot?.second ?: 0.0
            )
            if (prices[base] == null) unpriceable++
            InvestmentSpec.DEFAULTS[base] = InvestmentSpec(base, min, max, step)
        }
        if (unpriceable > 0) {
            LogRepository.debug(
                "settings",
                "Sizing fallback for $unpriceable base assets with no USDT-reachable price " +
                    "(sized at minimum lot; refreshed as books arrive)"
            )
        }
    }

    /**
     * USDT value of every asset, resolved from live book mid prices by
     * relaxation over the pair graph: USDT is 1 by definition, a direct USDT
     * pair prices its other side, and each further pass prices assets through
     * the ones already valued. No asset names are hard-coded, and an asset the
     * graph cannot reach is simply absent (it cannot be sized, so it gets no
     * investment spec and no triangles are traded from it).
     */
    private fun usdtValues(): Map<String, Double> {
        val legsByAsset = HashMap<String, MutableList<SymbolInfo>>()
        for (sym in marketCache.result.tradingSymbols.values) {
            legsByAsset.getOrPut(sym.baseAsset) { ArrayList() }.add(sym)
            if (sym.quoteAsset != sym.baseAsset) {
                legsByAsset.getOrPut(sym.quoteAsset) { ArrayList() }.add(sym)
            }
        }
        val prices = HashMap<String, Double>()
        prices["USDT"] = 1.0
        repeat(3) {
            for ((asset, legs) in legsByAsset) {
                if (asset in prices) continue
                for (sym in legs) {
                    val other = if (asset == sym.baseAsset) sym.quoteAsset else sym.baseAsset
                    val otherPrice = prices[other] ?: continue
                    val mid = depthCache.getSortedSnapshot(sym.symbol, 5)?.let { midPrice(it) } ?: continue
                    if (mid <= 0.0) continue
                    prices[asset] = if (asset == sym.baseAsset) mid * otherPrice else otherPrice / mid
                    break
                }
            }
        }
        return prices
    }

    /**
     * LOT_SIZE grid per base asset, preferred from its stable-quoted pairs.
     * The grid constrains investment quantities, so it must belong to a ticker
     * where the asset is the base; an asset that is only ever a quote falls
     * back to the generic step in [AutoTuner.deriveInvestment].
     */
    private fun lotFilters(): Map<String, Pair<Double, Double>> {
        val map = HashMap<String, Pair<Double, Double>>()
        val trading = marketCache.result.tradingSymbols.values
        for (quote in listOf("USDT", "USDC", "FDUSD")) {
            for (sym in trading) {
                if (sym.quoteAsset == quote) {
                    map.putIfAbsent(sym.baseAsset, (sym.lotStep ?: 0.0) to (sym.lotMinQty ?: 0.0))
                }
            }
        }
        for (sym in trading) {
            map.putIfAbsent(sym.baseAsset, (sym.lotStep ?: 0.0) to (sym.lotMinQty ?: 0.0))
        }
        return map
    }

    private fun defaultPaperBalance(bases: Set<String>): Map<String, Double> = buildMap {
        for (base in bases) {
            when (base) {
                "USDT" -> put(base, 1000.0)
                "USDC" -> put(base, 1000.0)
                "BTC" -> put(base, 1.0)
                "ETH" -> put(base, 10.0)
                "BNB" -> put(base, 10.0)
                else -> put(base, 100.0)
            }
        }
        // BNB for fees, as the original assumes
        if ("BNB" !in this) put("BNB", 10.0)
    }

    /**
     * Port of arbitrageCycleCallback: on every depth update, analyze all trades
     * related to the updated ticker and execute when profitable.
     */
    private val cycleStart = AtomicLong(0)
    private var autoTuneCounter = 0L
    private var lastAutoTuneSummary = ""
    private var lastAutoTuneLogAtMs = 0L
    private var lastScanAtMs = 0L

    // ------------------------------------------------------------------
    // Full-loop bookkeeping
    //
    // The scan is event driven: every depth update re-evaluates only the
    // triangles containing the ticker that moved. Those events recur forever,
    // so the same combinations are revisited continuously - but never in a
    // fixed order, because the order is dictated by whichever ticker updates
    // first. `loopCount` therefore tracks *coverage*: it advances once every
    // known combination has been evaluated at least one time, which is the
    // closest honest analogue of "one round trip around the universe".
    // ------------------------------------------------------------------
    private val loopTracker = LoopTracker()


    private fun onDepthUpdate(ticker: String) {
        // Cadence is measured from the raw event stream, before any of the
        // short-circuits below, so a book that keeps moving is still described
        // accurately while the scanner is otherwise busy.
        cadence.record(ticker, System.currentTimeMillis())

        if (_state.value == ScannerState.PAUSED_CAP_REACHED) return
        if (arbExecution == null) return
        if (arbExecution!!.inProgressIds.isNotEmpty()) return // isSafeToCalculateArbitrage()
        if (initializedAt == 0L) return

        // Duty cycle. A depth event is a signal that a book moved, not a reason
        // to re-optimise every related triangle: at full rate this ran several
        // hundred cycles a second and dominated both battery and log volume.
        // The arm TTL is measured in seconds and the execution deadline is
        // ~2s, so resolving faster than this cannot change a decision - and the
        // cadence statistics above stay exact because they are recorded before
        // this gate, straight off the raw event stream.
        val start = System.currentTimeMillis()
        if (start - lastScanAtMs < SCAN_TICK_MS) return
        lastScanAtMs = start
        val now = start
        cycleCounter.incrementAndGet()

        // Evaluating triangles against books that are minutes old produces
        // numbers that look like work but mean nothing, so the cycle is skipped
        // while any part of the universe is stale.
        if (feedStale) {
            _opportunities.value = emptyList()
            _currentOpportunity.value = null
            _gateStatus.value = "suspended: " + _feedHealth.value.message()
            return
        }

        val maxAge = ExecutionConfig.ageThresholdMs.toLong()
        val relatedTickers = marketCache.result.relatedTickers[ticker] ?: return
        // Build snapshot clone for all tickers involved in the related trades
        val snapshotClone = HashMap<String, DepthSnapshot>(relatedTickers.size)
        for (t in relatedTickers) {
            depthCache.getSortedSnapshot(t, ExecutionConfig.scanningDepth)?.let { snapshotClone[t] = it }
        }
        val allTrades = marketCache.result.relatedTrades[ticker] ?: return
        val filterResult = TradeFilter.filter(allTrades, snapshotClone)
        // Degraded feed: keep scanning the books that ARE current and skip
        // only the triangles that would be priced from stale legs.
        val trades = filterResult.complete.filter { t ->
            val d = tradeDepth(t, snapshotClone) ?: return@filter true
            // A quiet book is not a stale book. Illiquid pairs legitimately
            // tick far slower than the universe-wide p90, so the global gate
            // alone would exclude exactly the books where dislocations are
            // largest. Each leg gets the more generous of the global
            // threshold and a few of its own median update gaps.
            fun fresh(eventTime: Long, ticker: String): Boolean {
                val age = now - eventTime
                if (age <= maxAge) return true
                val own = cadence.interArrivalMs(ticker) * QUIET_BOOK_TOLERANCE
                return age <= maxOf(maxAge, own.toLong())
            }
            fresh(d.ab.eventTime, t.ab.ticker) && fresh(d.bc.eventTime, t.bc.ticker) &&
                fresh(d.ca.eventTime, t.ca.ticker)
        }
        val skippedStale = filterResult.complete.size - trades.size
        if (skippedStale > 0) {
            // Throttled on a stable key: the count changes every cycle, so the
            // message-level dedupe never fires and this used to be the single
            // largest contributor to log volume and CPU.
            LogRepository.throttled(
                LogLevel.DEBUG,
                "performance",
                "stale-legs",
                SKIP_LOG_THROTTLE_MS
            ) {
                "Skipped $skippedStale trades on stale legs " +
                    "(per-ticker quiet-book tolerance " + QUIET_BOOK_TOLERANCE + "x)"
            }
        }
        // Coverage counts everything this cycle reached a decision about, not
        // just the triangles that produced a price. Crediting only `trades` made
        // a loop impossible to close whenever any combination was skipped as
        // stale, because a permanently quiet pair is skipped on every cycle for
        // the life of the process - so loopCount stayed pinned at 0 with
        // progress stuck just short of 100%, and "LOOPS/MIN 0.0" read like the
        // scanner was not working at all.
        if (loopTracker.record(trades.map { it.id }, filterResult.complete.map { it.id })) {
            val snap = loopTracker.snapshot()
            LogRepository.info(
                "performance",
                "Loop #${snap.loopCount} complete: all ${snap.trianglesTotal} combinations " +
                    "reached in ${snap.lastLoopMs}ms (${snap.trianglesEvaluated} priced so far)"
            )
        } else if (loopTracker.blocked > 0) {
            // A tail that never shrinks means those combinations are unreachable,
            // which is worth saying out loud rather than showing as a stalled 99%.
            LogRepository.throttled(
                LogLevel.DEBUG,
                "performance",
                "loop-blocked",
                SKIP_LOG_THROTTLE_MS
            ) {
                "Loop coverage stalled: ${loopTracker.blocked} of ${loopTracker.total} " +
                    "combinations never reached (all their legs are inactive)"
            }
        }
        if (filterResult.skippedTradeCount > 0) {
            val tickers = filterResult.skippedByTicker.entries
                .sortedByDescending { it.value }
                .take(5)
                .joinToString(", ") { "${it.key}(x${it.value})" }
            LogRepository.throttled(
                LogLevel.WARN,
                "performance",
                "empty-books",
                SKIP_LOG_THROTTLE_MS
            ) {
                "Skipped ${filterResult.skippedTradeCount} trades on empty books: $tickers"
            }
        }

        val exec = arbExecution ?: return
        val results = CalculationNode.analyze(
            trades = trades,
            depthCacheClone = snapshotClone,
            errorCallback = { msg -> recordShallowDepth(msg) },
            executionCheckCallback = { calculated ->
                armOrExecute(exec, calculated, snapshotClone, now)
            },
            executionCallback = { calculated ->
                // The in-progress registry must be claimed on the caller's
                // thread, before the coroutine is launched: two depth updates
                // arriving back to back would otherwise both pass the
                // isSafeToCalculateArbitrage check before the first execution
                // registered itself, and run two triangles concurrently.
                if (exec.tryBegin(calculated)) {
                    scope.launch {
                        val state = exec.executeCalculatedPosition(calculated)
                        recordExecution(state, calculated)
                    }
                }
            },
            hudEnabled = true
        )

        // Update UI-facing state
        val uiNow = System.currentTimeMillis()
        val uiList = results.values
            .map { OpportunityUi.from(it, uiNow) }
            .sortedByDescending { it.percent }
        _opportunities.value = uiList
        _currentOpportunity.value = uiList.firstOrNull()
        // uiList is sorted by percent, so the head is the best edge on the
        // board this cycle - the quantity the arming bar has to be judged on.
        uiList.firstOrNull()?.let { edgeSamples.observe(it.percent) }

        if (ExecutionConfig.stagedExecutionEnabled) {
            // Sample the cadence of the tickers involved too: the median of
            // the per-ticker update gaps is the market-side signal the
            // cadence ceiling is derived from.
            for (candidate in results.values.sortedByDescending { it.percent }.take(SAMPLES_PER_CYCLE)) {
                Microstructure.triangleFeatures(
                    candidate.trade, snapshotClone, cadence::interArrivalMs, uiNow
                )?.let { f ->
                    microstructureSamples.observe(f)
                    cadenceSamples.observe(f)
                }
            }
        }

        // Self-tune the execution age gate from observed market freshness
        val stalestAge = uiList.maxOfOrNull { it.ageMs } ?: 0L
        if (stalestAge > 0 && !feedStale) {
            ageTuner.record(stalestAge)
            if (ExecutionConfig.ageThresholdAuto) {
                ageTuner.computeThreshold()?.let { tuned ->
                    ExecutionConfig.ageThresholdMs = tuned.toInt()
                    _ageThresholdInfo.value =
                        "auto: ${tuned}ms (from ${ageTuner.sampleCount()} obs, p90 x1.5)"
                }
            }
        }

        // Periodically re-derive the AUTO settings as the books and balance move.
        // Cheap (one book read), and it keeps fee, lot size and depth honest
        // without the operator ever touching Settings.
        if (++autoTuneCounter % AUTO_TUNE_EVERY_CYCLES == 0L) runAutoTune("periodic")

        // Kelly-gated auto execution (paper mode only) + transparent gate status
        val trader = kellyTrader
        val best = results.values.maxByOrNull { it.percent }
        if (best != null) {
            if (trader == null || !KellyConfig.enabled || _mode.value != TradingMode.PAPER) {
                _gateStatus.value = "auto-trading off"
            } else if (best.percent <= 0.0) {
                _gateStatus.value =
                    "waiting: best is ${"%.4f".format(best.percent)}% (fees+spread > edge); age gate " +
                        ExecutionConfig.ageThresholdMs + "ms"
            } else {
                _gateStatus.value =
                    "evaluating: ${"%.4f".format(best.percent)}% edge vs sigma ${"%.4f".format(trader.sigma())}%"
                scope.launch { trader.consider(best) }
            }
        }

        val elapsed = System.currentTimeMillis() - start
        updatePerformance(elapsed, uiList.size, uiList.count { it.percent > 0 })
        // This existed but had no call site, so arm_summaries_*.csv could
        // never receive a row at all.
        maybeRecordSummary(now, uiList.size)
    }

    /**
     * Runs [AutoTuner] against live data and applies only the fields whose AUTO
     * flag is still on. Any field the user has switched to MANUAL in Settings is
     * left exactly as they typed it.
     */
    private fun runAutoTune(reason: String) {
        // Tuning is advisory. A derivation failure must never propagate: this
        // runs inside the scan cycle, where an uncaught exception kills the
        // scan thread and takes the whole process with it.
        try {
            deriveAndApply(reason)
        } catch (e: Exception) {
            LogRepository.throttled(
                LogLevel.ERROR, "settings", "auto-tune-failed", AUTO_TUNE_LOG_HEARTBEAT_MS
            ) {
                "Auto-tune (" + reason + ") failed: " + e.javaClass.simpleName + ": " + e.message
            }
        }
    }

    private fun deriveAndApply(reason: String) {
        val anyAuto = ExecutionConfig.investmentAuto || ExecutionConfig.feeAuto ||
            ExecutionConfig.profitThresholdAuto || ExecutionConfig.capAuto ||
            ExecutionConfig.depthAuto || ExecutionConfig.strategyAuto ||
            ExecutionConfig.armTtlAuto || ExecutionConfig.armingMarginAuto ||
            ExecutionConfig.abandonMarginAuto || ExecutionConfig.spreadAuto ||
            ExecutionConfig.imbalanceAuto || ExecutionConfig.cadenceAuto ||
            ExecutionConfig.maxArmedAuto || ExecutionConfig.paperLatencyAuto ||
            ExecutionConfig.paperSlippageAuto
        // Kelly f* for the opportunity currently at the top of the book. Kept
        // ahead of the MANUAL early-return because per-base sizing needs the
        // same per-trade notional the tuner derives.
        val bestPercent = _currentOpportunity.value?.percent ?: 0.0
        val sigma = kellyTrader?.sigma() ?: KellyConfig.defaultSigmaPercent
        val kellyFraction = if (KellyConfig.enabled && bestPercent > 0.0 && sigma > 0.0) {
            KellyCriterion.evaluate(
                expectedPercent = bestPercent,
                sigmaPercent = sigma,
                requiredProbability = KellyConfig.requiredProbability,
                maxKellyFraction = KellyConfig.maxKellyFraction
            ).kellyFraction
        } else {
            0.0
        }

        if (!anyAuto) {
            // Every knob is operator-owned, but per-base sizing is still
            // load-bearing: optimize() sweeps the spec of each trade's root
            // asset, and roots come from the market rather than Settings. The
            // configured base keeps its verbatim numbers; the rest of the
            // discovered universe gets mechanical sizing so the scan can run.
            seedInvestmentSpecs(
                AutoTuner.derivePerTradeUsdt(
                    KellyConfig.budgetUsdt, kellyFraction, KellyConfig.maxAllocationPerTrade,
                    KellyConfig.investmentFractionOfKelly, KellyConfig.minTradeUsdt
                )
            )
            _autoTuneInfo.value = "all settings MANUAL"
            return
        }
        // Reference book: the base with the most triangles - the deepest, most
        // liquid market in the discovered universe. Representative only; the
        // per-base specs below already cover every base individually.
        val base = marketCache.result.referenceBase
            ?: InvestmentSpec.DEFAULTS.keys.firstOrNull()
            ?: return
        val info = referenceSymbolInfo(base)
        val pair = info?.symbol ?: (base + "USDT")

        val snapshot = depthCache.getSortedSnapshot(pair, AutoTuner.VALID_DEPTHS.last())
        val price = snapshot?.let { midPrice(it) } ?: 0.0
        // Measured across the universe, low percentile: the depth has to serve
        // the THINNEST book we scan, not the base pair. Sizing it on BTCUSDT -
        // a book so deep that one level covers everything - is what left the
        // alt books unable to cover a single leg.
        val levelNotional = thinnestBookLevelNotional()

        val result = AutoTuner.tune(
            AutoTuner.Inputs(
                base = base,
                quote = info?.quoteAsset ?: "USDT",
                budgetUsdt = KellyConfig.budgetUsdt,
                basePriceUsdt = price,
                lotStep = info?.lotStep ?: 0.0,
                lotMinQty = info?.lotMinQty ?: 0.0,
                kellyFraction = kellyFraction,
                investmentFractionOfKelly = KellyConfig.investmentFractionOfKelly,
                maxAllocationPerTrade = KellyConfig.maxAllocationPerTrade,
                minTradeUsdt = KellyConfig.minTradeUsdt,
                levelNotional = levelNotional,
                largestConfiguredNotionalUsdt = largestConfiguredNotionalUsdt(),
                currentStrategy = ExecutionConfig.strategy,
                preFundedRatio = preFundedRatio(),
                takerCommissionRate = info?.takerCommission ?: typicalTakerCommission(),
                feePercentFallback = ExecutionConfig.feePercent,
                latencyMs = _connection.value.latencyMs.toDouble(),
                observedWorstLegSpreadBps = microstructureSamples.worstLegSpreadBps(),
                observedP90AbsImbalance = microstructureSamples.p90AbsImbalance(),
                observedMedianInterArrivalMs = medianInterArrivalMs(),
                observedMedianOpportunityMs = medianOpportunityLifetimeMs()
            )
        )

        // Per-base sizing: every base asset in the discovered universe gets a
        // spec sized to the same per-trade notional at its own USDT price. This
        // is what makes an all-asset universe runnable - the roots are no
        // longer fixed, so no single configured spec can cover them.
        seedInvestmentSpecs(result.perTradeUsdt)
        if (ExecutionConfig.feeAuto) ExecutionConfig.feePercent = result.feePercent
        if (ExecutionConfig.profitThresholdAuto) ExecutionConfig.profitThreshold = result.profitThreshold
        if (ExecutionConfig.capAuto) ExecutionConfig.cap = result.cap
        if (ExecutionConfig.depthAuto) ExecutionConfig.scanningDepth = result.depth
        if (ExecutionConfig.strategyAuto) ExecutionConfig.strategy = result.strategy
        if (ExecutionConfig.executionDeadlineAuto) ExecutionConfig.executionDeadlineMs = result.deadlineMs
        if (ExecutionConfig.armTtlAuto) ExecutionConfig.armTtlMs = result.armTtlMs
        if (ExecutionConfig.armingMarginAuto) ExecutionConfig.armingMarginPercent = result.armingMarginPercent
        if (ExecutionConfig.abandonMarginAuto) ExecutionConfig.abandonMarginPercent = result.abandonMarginPercent
        if (ExecutionConfig.spreadAuto) ExecutionConfig.spreadTightMaxBps = result.spreadTightMaxBps
        if (ExecutionConfig.imbalanceAuto) ExecutionConfig.imbalanceMaxAbs = result.imbalanceMaxAbs
        if (ExecutionConfig.cadenceAuto) ExecutionConfig.cadenceMaxInterArrivalMs = result.cadenceMaxInterArrivalMs
        if (ExecutionConfig.maxArmedAuto) ExecutionConfig.maxArmedOpportunities = result.maxArmedOpportunities
        if (ExecutionConfig.paperLatencyAuto) ExecutionConfig.paperLatencyMs = result.paperLatencyMs
        if (ExecutionConfig.paperSlippageAuto) ExecutionConfig.paperSlippagePercent = result.paperSlippagePercent
        // Applied outside the AUTO branches so MANUAL values are honoured too.
        paperEngine?.setFriction(
            ExecutionConfig.paperLatencyMs.toLong(),
            ExecutionConfig.paperSlippagePercent
        )

        val summary = "auto($reason): " + result.summary() + "  [" + ExecutionConfig.autoModeSummary() + "]"
        _autoTuneInfo.value = summary
        // Gate the log on the DERIVED VALUES, not on the rendered string. The
        // string embeds live market observations ("observed 10.30"), so it
        // differs on nearly every run and the old string comparison suppressed
        // nothing - one INFO line per second, every second.
        val signature = ExecutionConfig.derivedSignature()
        val nowMs = System.currentTimeMillis()
        val changed = signature != lastAutoTuneSummary
        val heartbeatDue = nowMs - lastAutoTuneLogAtMs >= AUTO_TUNE_LOG_HEARTBEAT_MS
        if (changed || heartbeatDue) {
            lastAutoTuneSummary = signature
            lastAutoTuneLogAtMs = nowMs
            LogRepository.info("settings", summary)
        }
    }

    /** Tickers whose book could not cover a leg since the last flush. */
    private val shallowDepthTickers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * A book that cannot cover a leg is skipped, which is correct, but it
     * happened on every cycle for every thin book and each throw was logged at
     * WARN - 45 of 78 lines in one session. The offenders are accumulated and
     * reported as one line per throttle window, so the set is still visible
     * without drowning everything else.
     */
    private fun recordShallowDepth(message: String) {
        val ticker = message.substringAfterLast(" using ", "").ifBlank { "unknown" }
        shallowDepthTickers.add(ticker)
        LogRepository.throttled(
            LogLevel.WARN, "performance", "shallow-depth", SKIP_LOG_THROTTLE_MS
        ) {
            val tickers = shallowDepthTickers.toList().sorted()
            shallowDepthTickers.clear()
            "Book too shallow at depth " + ExecutionConfig.scanningDepth + ": " +
                tickers.take(10).joinToString(",") +
                if (tickers.size > 10) " (+" + (tickers.size - 10) + " more)" else ""
        }
    }

    /**
     * Level notional of the thinnest books in the universe, as a low
     * percentile of a spread sample. A single median says nothing useful when
     * one book is a million times deeper than another.
     */
    private fun thinnestBookLevelNotional(): Double {
        val tickers = marketCache.result.relatedTickers.keys
        if (tickers.isEmpty()) return 0.0
        val step = maxOf(1, tickers.size / DEPTH_SAMPLE_SIZE)
        val samples = ArrayList<Double>(DEPTH_SAMPLE_SIZE)
        var i = 0
        for (t in tickers) {
            if (i++ % step != 0) continue
            if (samples.size >= DEPTH_SAMPLE_SIZE) break
            val snap = depthCache.getSortedSnapshot(t, 5) ?: continue
            val notional = maxOf(
                AutoTuner.measureLevelNotional(snap.bids, 5),
                AutoTuner.measureLevelNotional(snap.asks, 5)
            )
            if (notional.isFinite() && notional > 0.0) samples.add(notional)
        }
        if (samples.isEmpty()) return 0.0
        return MicrostructureSamples.percentile(samples, 0.10)
    }

    /**
     * Largest notional one leg is ever asked to convert, across every base that
     * has an investment spec. An operator-configured spec for an expensive
     * base can be orders of magnitude larger than the base pair's own.
     */
    private fun largestConfiguredNotionalUsdt(): Double {
        var worst = 0.0
        var considered = 0
        for ((base, spec) in InvestmentSpec.DEFAULTS) {
            if (considered >= NOTIONAL_SAMPLE_SIZE) break
            if (spec.max <= 0.0) continue
            val snap = depthCache.getSortedSnapshot(base.uppercase() + "USDT", 5) ?: continue
            val price = midPrice(snap)
            if (price <= 0.0) continue
            considered++
            worst = maxOf(worst, spec.max * price)
        }
        return worst
    }

    /** Mid price of a snapshot, or 0 when either side is empty. */
    private fun midPrice(snapshot: DepthSnapshot): Double {
        val bestBid = snapshot.bids.keys.maxOrNull() ?: return 0.0
        val bestAsk = snapshot.asks.keys.minOrNull() ?: return 0.0
        return (bestBid + bestAsk) / 2.0
    }

    /** Median taker commission across trading symbols; Binance publishes one rate. */
    private fun typicalTakerCommission(): Double? {
        val rates = marketCache.result.tradingSymbols.values.mapNotNull { it.takerCommission }
        if (rates.isEmpty()) return null
        return rates.sorted()[rates.size / 2]
    }

    /**
     * Fraction of the best triangle's three legs whose base asset the portfolio
     * already holds. `parallel` needs all of them, `linear` needs one.
     */
    private fun preFundedRatio(): Double {
        val balances = paperEngine?.balances ?: return 0.0
        val best = _currentOpportunity.value ?: return 0.0
        val assets = listOf(best.abTicker, best.bcTicker, best.caTicker)
            .mapNotNull { marketCache.result.tradingSymbols[it]?.baseAsset }
            .distinct()
        if (assets.isEmpty()) return 0.0
        val held = assets.count { (balances[it] ?: 0.0) > 0.0 }
        return held.toDouble() / assets.size
    }

    /**
     * Decides whether a qualifying triangle is sent now.
     *
     * With staging off this is the original behaviour. With staging on, the
     * triangle is *armed* on first sight and then re-decided on every depth
     * update that touches one of its three books - which is free, because this
     * callback is only reached for triangles containing the ticker that just
     * moved. The size and the percent handed to the gates come from
     * [CalculationNode.optimize] re-running the step sweep against the books as
     * they are right now, so the decision is always made on a fresh, freshly
     * sized triangle rather than the one that was detected.
     */
    private fun armOrExecute(
        exec: ArbitrageExecution,
        calculated: CalculatedPosition,
        books: Map<String, DepthSnapshot>,
        now: Long
    ): Boolean {
        if (!exec.isSafeToExecute(calculated, now)) return false
        if (!ExecutionConfig.stagedExecutionEnabled) return true

        val trade = calculated.trade
        val features = Microstructure.triangleFeatures(trade, books, cadence::interArrivalMs, now)
            ?: return false

        if (!armBook.isArmed(trade.id)) {
            armBook.arm(
                id = trade.id,
                ttlMs = ExecutionConfig.armTtlMs,
                fireBar = ExecutionConfig.profitThreshold + ExecutionConfig.armingMarginPercent,
                abandonBar = ExecutionConfig.profitThreshold - ExecutionConfig.abandonMarginPercent,
                percent = calculated.percent
            )
        }
        val arm = armBook.armOf(trade.id) ?: return false

        val verdict = ExecutionGates.evaluate(
            ExecutionGates.Input(
                features = features,
                projectedPercent = calculated.percent,
                armedAt = arm.armedAt,
                now = now,
                fireBar = arm.fireBar,
                abandonBar = arm.abandonBar
            ),
            ttlMs = arm.ttlMs
        )
        // Every arm that leaves the book is a measurement of how long an
        // edge actually survives here - the honest basis for the window.
        val lifetime = arm.ageMs(now)
        val features0 = features
        opportunityLifetimes.observe(
            com.hakim3691.bta.core.TriangleFeatures(
                com.hakim3691.bta.core.BookFeatures(
                    features0.ab.ticker, features0.ab.spreadBps, features0.ab.imbalance,
                    features0.ab.interArrivalMs, lifetime
                ),
                com.hakim3691.bta.core.BookFeatures(
                    features0.bc.ticker, features0.bc.spreadBps, features0.bc.imbalance,
                    features0.bc.interArrivalMs, lifetime
                ),
                com.hakim3691.bta.core.BookFeatures(
                    features0.ca.ticker, features0.ca.spreadBps, features0.ca.imbalance,
                    features0.ca.interArrivalMs, lifetime
                )
            )
        )

        val outcome = armBook.apply(trade.id, verdict, features)

        return when (outcome) {
            ArmOutcome.FIRED -> {
                LogRepository.info(
                    "arming",
                    "FIRED " + trade.id + " after " + arm.ageMs(now) + "ms and " + arm.waits +
                        " waits: " + verdict.reason
                )
                recordArmEpisode(trade.id, "FIRED", arm, features, verdict)
                publishArming()
                true
            }

            ArmOutcome.ABANDONED -> {
                LogRepository.info(
                    "arming",
                    "ABANDONED " + trade.id + " after " + arm.ageMs(now) + "ms: " + verdict.reason
                )
                recordArmEpisode(trade.id, "ABANDONED", arm, features, verdict)
                _gateStatus.value = "held " + trade.id + " " + arm.ageMs(now) + "ms, then dropped: " +
                    verdict.reason
                publishArming()
                false
            }

            ArmOutcome.WAITING -> {
                _gateStatus.value = "holding " + trade.id + " " + arm.ageMs(now) + "ms (" +
                    arm.waits + " waits): " + verdict.reason
                false
            }

            ArmOutcome.EXPIRED -> {
                publishArming()
                false
            }
        }
    }

    /**
     * In live mode, watches the fee balance drain. When BNB runs out Binance
     * deducts fees from the received asset instead, which silently breaks the
     * fee accounting every projection and every recordExecution relies on -
     * so the warning has to fire before that, not after.
     */
    private fun startBnbWatchdog() {
        bnbWatchdog?.cancel()
        bnbWatchdog = scope.launch {
            while (isActive && _mode.value == TradingMode.LIVE) {
                delay(60_000)
                val session = liveSession ?: break
                val bnb = session.bnbBalance()
                if (bnb < LIVE_MIN_BNB) {
                    LogRepository.error(
                        "execution",
                        "MANUAL ACTION: BNB balance ${bnb} below ${LIVE_MIN_BNB} - fees will be " +
                            "deducted from received assets and every projection will be wrong. " +
                            "Top up BNB or stop live trading."
                    )
                    _manualCloseRequired.value =
                        (listOf("BNB-FEES top up BNB (holding $bnb)") + _manualCloseRequired.value)
                            .take(50)
                }
            }
        }
    }

    /**
     * Drops arms whose window has closed. Driven by a timer because a triangle
     * whose books go quiet is never re-evaluated by the event-driven scan and
     * would otherwise stay armed forever.
     */
    private fun expireStaleArms() {
        val expired = armBook.expire()
        if (expired.isNotEmpty()) {
            for (record in expired) {
                LogRepository.info("arming", "EXPIRED " + record.id + ": " + record.reason)
                recordExpiredEpisode(record)
            }
            publishArming()
        }
    }

    private fun publishArming() {
        _armingStats.value = armBook.snapshot()
        _armLog.value = armBook.recentRecords(12)
    }

    private fun recordExecution(state: ExecutionState, calculated: CalculatedPosition) {
        val now = System.currentTimeMillis()
        val ui = OpportunityUi.from(calculated, now)
        _tradesFeed.value = (listOf(ui) + _tradesFeed.value).take(100)
        paperEngine?.let { _paperBalances.value = it.balances.toMap() }
        _executionStates.value = (listOf(state) + _executionStates.value).take(100)
        if (state.requiresManualClose) {
            kellyTrader?.recordStranded(state.strandedAssets)
            val note = state.id + (if (state.strandedAssets.isNotEmpty())
                " still holding " + state.strandedAssets.joinToString(", ") else "")
            _manualCloseRequired.value = (listOf(note) + _manualCloseRequired.value.filter { !it.startsWith(state.id) }).take(50)
            LogRepository.error(
                "execution",
                "MANUAL ACTION: could not unwind " + state.id +
                    " - close it on Binance" + (if (state.strandedAssets.isNotEmpty())
                        " (holding " + state.strandedAssets.joinToString(", ") + ")" else "")
            )
        }
        LogRepository.info(
            "execution",
            "${_mode.value} ${state.status} ${state.id} " +
                "delta=${state.actual?.a?.delta}" +
                " fees=${state.actual?.fees}" +
                " in ${System.currentTimeMillis() - state.startTime}ms" +
            (if (state.aborted) " ABORTED: ${state.abortReason} (unwound=${state.unwound})" else "")
        )
    }

    private val recentCycleTimes = ArrayDeque<Long>()
    private val recentCycleStarts = ArrayDeque<Long>()

    /** Completed loops per minute since the scanner started. */
    private fun loopsPerMinute(): Double {
        val loops = loopTracker.loopCount
        if (initializedAt == 0L || loops == 0L) return 0.0
        val minutes = (System.currentTimeMillis() - initializedAt) / 60_000.0
        if (minutes <= 0.0) return 0.0
        return loops / minutes
    }

    private fun updatePerformance(cycleMs: Long, scanned: Int, profitable: Int) {
        synchronized(recentCycleTimes) {
            recentCycleTimes.addLast(cycleMs)
            if (recentCycleTimes.size > 500) recentCycleTimes.removeFirst()
            val avg = recentCycleTimes.average()
            // Rate from when cycles actually STARTED, not from how long they
            // took: 1000/avgDuration measures how fast a cycle runs, which is
            // not the same thing at all once a duty cycle throttles the rate.
            val now = System.currentTimeMillis()
            recentCycleStarts.addLast(now)
            while (recentCycleStarts.size > 2 &&
                now - recentCycleStarts.first() > CYCLE_RATE_WINDOW_MS
            ) {
                recentCycleStarts.removeFirst()
            }
            val span = if (recentCycleStarts.size >= 2) {
                (now - recentCycleStarts.first()).toDouble()
            } else {
                0.0
            }
            val loop = loopTracker.snapshot()
            _performance.value = ScanPerformance(
                cycleCount = cycleCounter.get(),
                cyclesPerSecond = if (span > 0.0) {
                    (recentCycleStarts.size - 1) * 1000.0 / span
                } else {
                    0.0
                },
                lastCycleMs = cycleMs,
                avgCycleMs = avg,
                scannedCount = scanned,
                profitableCount = profitable,
                loopCount = loop.loopCount,
                trianglesEvaluated = loop.trianglesEvaluated,
                trianglesTotal = loop.trianglesTotal,
                loopProgress = loop.progress,
                blockedCount = loop.blocked,
                lastLoopMs = loop.lastLoopMs,
                loopsPerMinute = loopsPerMinute()
            )
        }
    }

    // ------------------------------------------------------------------
    // Mode switching (paper vs live)
    // ------------------------------------------------------------------

    /**
     * Switches execution to LIVE mode. This must be invoked by an explicit user
     * action from the PaperLiveScreen after acknowledging the safety warnings.
     */
    fun enableLiveTrading(apiKey: String, apiSecret: String, confirmed: Boolean) {
        require(confirmed) { "Live trading requires explicit confirmation" }
        val session = LiveTradingSession(apiKey, apiSecret, http, depthCache)
        liveSession = session
        startBnbWatchdog()
        arbExecution = ArbitrageExecution(session).also { exec ->
            exec.shutdownListener = { _state.value = ScannerState.PAUSED_CAP_REACHED }
        }
        KellyConfig.enabled = false
        _mode.value = TradingMode.LIVE
        LogRepository.warn("execution", "LIVE TRADING ENABLED - real orders will be placed")
    }

    fun disableLiveTrading() {
        liveSession = null
        bnbWatchdog?.cancel()
        paperEngine?.let { pe ->
            arbExecution = ArbitrageExecution(pe).also { exec ->
                exec.shutdownListener = { _state.value = ScannerState.PAUSED_CAP_REACHED }
            }
            kellyTrader = KellyPaperTrader(
                paperEngine = pe,
                arbExecution = arbExecution!!,
                onTradeCompleted = { state, calculated ->
                    recordExecution(state, calculated)
                    _lastExecutedOpportunity.value = OpportunityUi.from(calculated, System.currentTimeMillis())
                },
                onStatsChanged = { _kellyStats.value = kellyTrader?.snapshotStats() }
            )
            _kellyStats.value = kellyTrader!!.snapshotStats()
        }
        _mode.value = TradingMode.PAPER
        LogRepository.info("execution", "Reverted to PAPER TRADING mode")
    }

    fun updateKellySettings(enabled: Boolean, budgetUsdt: Double, requiredProbability: Double, investmentFraction: Double, minTradeUsdt: Double) {
        KellyConfig.enabled = enabled
        KellyConfig.requiredProbability = requiredProbability
        KellyConfig.investmentFractionOfKelly = investmentFraction
        KellyConfig.minTradeUsdt = minTradeUsdt
        val trader = kellyTrader
        if (budgetUsdt != KellyConfig.budgetUsdt) {
            if (trader != null) {
                trader.reset(budgetUsdt)
                paperEngine?.let { pe ->
                    pe.balances["USDT"] = budgetUsdt
                    _paperBalances.value = pe.balances.toMap()
                }
            } else {
                KellyConfig.budgetUsdt = budgetUsdt
            }
        }
        _kellyStats.value = trader?.snapshotStats()
        LogRepository.info(
            "kelly",
            "Kelly auto-execution ${if (enabled) "ENABLED" else "disabled"} budget=$budgetUsdt USDT p>$requiredProbability"
        )
    }

    fun kellySigma(): Double = kellyTrader?.sigma() ?: KellyConfig.defaultSigmaPercent

    /** The three book snapshots a trade would be priced from, or null if any is missing. */
    private fun tradeDepth(t: Trade, books: Map<String, DepthSnapshot>): CalculationNode.TradeDepthSnapshot? {
        val ab = books[t.ab.ticker] ?: return null
        val bc = books[t.bc.ticker] ?: return null
        val ca = books[t.ca.ticker] ?: return null
        return CalculationNode.TradeDepthSnapshot(ab, bc, ca)
    }

    /**
     * Recomputes how much of the universe is genuinely current and flips
     * [feedStale], which the scan loop uses to stop working on dead books.
     */
    private fun refreshFeedHealth() {
        // Only a fully dead feed suspends scanning. Some books always tick
        // slower than the gate - illiquid pairs, quiet markets - so "any stale
        // ticker" would suspend the scanner forever on a healthy universe.
        val maxAge = ExecutionConfig.ageThresholdMs.toLong()
        // Deadness is judged on the FULL universe: quarantined books are
        // excluded from the display counts below, but if every book in the
        // universe is frozen that is still a dead feed - excluding them here
        // would turn "everything stale" into "total 0, unknown" and the
        // scanner would keep burning cycles on frozen books.
        feedStale = depthCache.freshness(maxAge).feedDead
        // Quarantined books are left out of the counts entirely: they are
        // subscribed-but-frozen, and counting them re-created the permanent
        // DEGRADED banner this monitor exists to remove.
        val f = depthCache.freshness(maxAge, exclude = quarantinedTickers)

        // A pair that ticks slower than the global gate is illiquid, not
        // broken. The scan filter already excuses those per ticker, so the
        // banner has to excuse the same books or it sits on DEGRADED forever
        // on an otherwise healthy universe - which is exactly what it did:
        // 294/325, permanently alarmed by a handful of exotic pairs.
        val quiet = if (f.total > 0 && f.fresh < f.total) {
            f.staleTickers.count { ticker ->
                cadence.interArrivalMs(ticker) * QUIET_BOOK_TOLERANCE >= maxAge
            }
        } else {
            0
        }
        val effectiveFresh = f.fresh + quiet
        val effectiveDegraded = f.total > 0 && !f.feedDead && effectiveFresh < f.total
        if (initialSyncComplete && effectiveDegraded) sawDegradedFeed = true
        _feedHealth.value = FeedHealth(
            total = f.total,
            synced = f.synced,
            fresh = minOf(effectiveFresh, f.total),
            stalestAgeMs = f.stalestAgeMs,
            maxFreshAgeMs = maxAge,
            quietExempt = quiet
        )
        _connection.value = _connection.value.copy(freshTickers = f.fresh)
    }

    /** Re-runs the tuner immediately (used by the Settings "re-derive now" action). */
    fun refreshAutoTune() = runAutoTune("manual refresh")

    /** Clears the manual-close warnings once the user has handled them. */
    fun clearManualCloseRequired() {
        _manualCloseRequired.value = emptyList()
    }

    fun resetCapCounter() {
        arbExecution?.attemptedPositions?.clear()
        if (_state.value == ScannerState.PAUSED_CAP_REACHED) _state.value = ScannerState.RUNNING
    }

    /**
     * Fire-and-forget start for non-coroutine callers (the foreground service
     * restoring a killed session). Idempotent: [start] itself refuses a second
     * run, and any failure lands in the controller's own ERROR state rather
     * than vanishing into a detached job.
     */
    fun requestStart() {
        scope.launch { start() }
    }

    fun stop() {
        stopRequested = true
        updateJob?.cancel()
        latencyJob?.cancel()
        bnbWatchdog?.cancel()
        statusJob?.cancel()
        syncJob?.cancel()
        wsCountJob?.cancel()
        stalenessJob?.cancel()
        quarantineJob?.cancel()
        wsClient?.close()
        // A short session used to produce no summary row at all, because the
        // interval had not elapsed when the scanner stopped. The partial
        // interval is real data, so it is written out before the state goes.
        if (summaryAnchor != 0L) {
            maybeRecordSummary(System.currentTimeMillis(), 0, force = true)
        }
        armBook.clear()
        cadence.reset()
        microstructureSamples.reset()
        publishArming()
        // A clean stop is a new session: the runtime quarantine is wiped so the
        // next start re-probes from scratch, exactly like the startup probe.
        quarantinedTickers.clear()
        // A clean stop is a new session: the runtime quarantine is wiped so the
        // next start re-probes from scratch, exactly like the startup probe.
        quarantinedTickers.clear()
        _state.value = ScannerState.STOPPED
        appContext?.let { ScannerForegroundService.stop(it) }
        LogRepository.info("main", "Scanner stopped")
    }

    fun shutdown() {
        stalenessJob?.cancel()
        stop()
        scope.cancel()
        http.dispatcher.executorService.shutdown()
    }
}
