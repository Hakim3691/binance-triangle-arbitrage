package com.hakim3691.bta.market

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

enum class WsStatus { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

/**
 * Binance market WebSocket client for `<symbol>@depth@100ms` streams,
 * with staggered subscription initialization, reconnect with exponential
 * backoff, stale-connection watchdog, and malformed-message tolerance.
 */
class BinanceWebSocketClient(
    private val http: OkHttpClient,
    private val depthCache: DepthCacheManager,
    private val snapshotLimit: Int
) {

    companion object {
        const val WS_URL = "wss://stream.binance.com:9443/stream"
        /** Port of the Main.js valid-depth selection for REST snapshot limits. */
        fun resolveValidDepth(requested: Int): Int =
            BinanceRestClient.resolveValidDepth(requested)

        /** Tickers re-snapshotted per REST batch on reconnect. */
        const val RESYNC_BATCH_SIZE = 25

        /** Pause between batches so a reconnect does not trip rate limits. */
        const val RESYNC_BATCH_DELAY_MS = 250L

        /** Binance's documented per-connection limit for combined streams. */
        const val MAX_COMBINED_STREAMS = 1024
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(WsStatus.DISCONNECTED)
    val status: StateFlow<WsStatus> = _status

    private val _messagesReceived = MutableStateFlow(0L)
    val messagesReceived: StateFlow<Long> = _messagesReceived

    private val _lastMessageAt = MutableStateFlow(0L)
    val lastMessageAt: StateFlow<Long> = _lastMessageAt

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private var webSocket: WebSocket? = null
    private var subscribedSymbols: List<String> = emptyList()
    private var watchdogJob: Job? = null
    private var reconnectJob: Job? = null
    private var resyncJob: Job? = null
    private var reconnectAttempts = 0
    private var manualClose = false

    /**
     * Invoked every time a socket opens, including after a reconnect.
     *
     * The owner uses it to discard observations collected while the feed was
     * down. Ages recorded during an outage describe a disconnection, not the
     * market, and must not train the age gate open.
     */
    var onResync: (() -> Unit)? = null

    /** Number of times a socket has been opened; 1 means the initial connect. */
    private var opens = 0

    /** Opens the combined stream for the given symbols with staggered init. */
    fun connect(symbols: List<String>) {
        if (symbols.isEmpty()) return
        manualClose = false
        subscribedSymbols = symbols
        _status.value = WsStatus.CONNECTING
        openSocket()
        startWatchdog()
    }

    private fun openSocket() {
        // Combined stream format: /stream?streams=a@depth@100ms/b@depth@100ms/...
        // Binance caps a combined stream at 1024 streams per connection;
        // URLs beyond that are rejected at handshake, so the failure must be
        // loud and early rather than a mysterious disconnect.
        check(subscribedSymbols.size <= MAX_COMBINED_STREAMS) {
            "Combined stream would subscribe ${subscribedSymbols.size} tickers; " +
                "Binance allows at most $MAX_COMBINED_STREAMS per connection"
        }
        // Binance caps a combined stream at 1024 streams per connection and
        // rejects the handshake beyond that, so an oversized universe must
        // fail loudly here rather than as a mysterious disconnect loop.
        check(subscribedSymbols.size <= MAX_COMBINED_STREAMS) {
            "Combined stream would subscribe ${subscribedSymbols.size} tickers; " +
                "Binance allows at most $MAX_COMBINED_STREAMS per connection"
        }
        val streams = subscribedSymbols.joinToString("/") { "${it.lowercase()}@depth@100ms" }
        val request = Request.Builder().url("$WS_URL?streams=$streams").build()
        webSocket = http.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempts = 0
            _status.value = WsStatus.CONNECTED
            _lastError.value = null
            opens++
            onResync?.invoke()
            // After an outage every cached ticker is potentially gapped, and a
            // dead feed produces no diffs at all - so gap detection can never
            // flag them and the cache would stay frozen indefinitely. Re-seed
            // proactively instead of waiting for the watchdog sweep.
            if (opens > 1) requestFullResync()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            _messagesReceived.value += 1
            _lastMessageAt.value = System.currentTimeMillis()
            handleMessage(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _lastError.value = t.message ?: "websocket failure"
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!manualClose) scheduleReconnect()
            else _status.value = WsStatus.DISCONNECTED
        }
    }

    private fun handleMessage(text: String) {
        try {
            val root = json.parseToJsonElement(text).jsonObject
            val stream = root["stream"]?.jsonPrimitive?.content ?: return
            val data = root["data"]?.jsonObject ?: return
            val symbol = data["s"]?.jsonPrimitive?.content
                ?: stream.substringBefore("@").uppercase()
            val eventType = data["e"]?.jsonPrimitive?.content ?: return
            if (eventType != "depthUpdate") return

            val bids = data["b"]!!.jsonArray.map { row ->
                val r = row.jsonArray
                r[0].jsonPrimitive.double to r[1].jsonPrimitive.double
            }
            val asks = data["a"]!!.jsonArray.map { row ->
                val r = row.jsonArray
                r[0].jsonPrimitive.double to r[1].jsonPrimitive.double
            }
            val event = DepthCacheManager.DiffEvent(
                symbol = symbol,
                firstUpdateId = data["U"]!!.jsonPrimitive.long,
                finalUpdateId = data["u"]!!.jsonPrimitive.long,
                eventTime = data["E"]!!.jsonPrimitive.long,
                bids = bids,
                asks = asks
            )
            depthCache.applyDiff(event)
        } catch (e: Exception) {
            // Malformed message: tolerated, recorded, and skipped
            _lastError.value = "malformed message: ${e.message}"
        }
    }

    private fun scheduleReconnect() {
        if (manualClose || _status.value == WsStatus.RECONNECTING) return
        _status.value = WsStatus.RECONNECTING
        reconnectJob = scope.launch {
            // Exponential backoff capped at 30s
            val backoffMs = (1000L shl reconnectAttempts.coerceAtMost(5))
            reconnectAttempts += 1
            delay(backoffMs)
            if (!manualClose) {
                openSocket()
            }
        }
    }

    /**
     * Stale-connection watchdog: if no message arrives within 15s, force
     * reconnect. Binance pushes depth for active symbols far more often than
     * that; quietness implies a dead connection.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive && !manualClose) {
                delay(5000)
                val last = _lastMessageAt.value
                val connected = _status.value == WsStatus.CONNECTED
                if (connected && last != 0L && System.currentTimeMillis() - last > 15_000) {
                    _lastError.value = "stale connection detected (no messages for 15s)"
                    webSocket?.cancel()
                    scheduleReconnect()
                }
                // Request fresh snapshots for symbols flagged out-of-sync
                val resync = depthCache.takeOutOfSyncSymbols()
                for (symbol in resync) {
                    requestSnapshot(symbol)
                }
            }
        }
    }

    /**
     * Fetches a REST depth snapshot for a symbol and applies it to the cache.
     * Port of the library's getSymbolDepthSnapshot + updateSymbolDepthCache.
     */
    /**
     * Re-seeds every watched ticker with a fresh REST snapshot, in batches so a
     * reconnect does not fire hundreds of simultaneous requests.
     */
    private fun requestFullResync() {
        resyncJob?.cancel()
        resyncJob = scope.launch {
            for (batch in subscribedSymbols.chunked(RESYNC_BATCH_SIZE)) {
                if (manualClose) return@launch
                batch.forEach { requestSnapshot(it) }
                delay(RESYNC_BATCH_DELAY_MS)
            }
        }
    }

    fun requestSnapshot(symbol: String): Job {
        return scope.launch {
            try {
                val rest = BinanceRestClient(
                    http,
                    { "" },
                    { "" }
                )
                val d = rest.depth(symbol, snapshotLimit)
                depthCache.applySnapshot(symbol, d.lastUpdateId, d.bids, d.asks)
            } catch (e: Exception) {
                _lastError.value = "snapshot failed for $symbol: ${e.message}"
                // Retry on the next watchdog cycle
                depthCache.markOutOfSync(symbol)
            }
        }
    }

    fun close() {
        manualClose = true
        watchdogJob?.cancel()
        reconnectJob?.cancel()
        resyncJob?.cancel()
        webSocket?.close(1000, "client shutdown")
        _status.value = WsStatus.DISCONNECTED
    }
}
