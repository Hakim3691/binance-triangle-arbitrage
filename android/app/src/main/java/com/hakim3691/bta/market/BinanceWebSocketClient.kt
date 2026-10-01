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

enum class WsStatus { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

/**
 * Binance market WebSocket client for `<symbol>@depth@100ms` streams,
 * with staggered subscription initialization, reconnect with exponential
 * backoff, stale-connection watchdog, and malformed-message tolerance.
 *
 * The subscription set is sharded across as many sockets as it needs. Binance
 * caps a combined stream at 1024 streams per connection, and the stream list
 * travels in the URL, so a universe discovered from the whole exchange
 * (every asset that roots a triangle) no longer fits on one socket. Sharding
 * keeps each connection well inside both limits; the cache, the watchdog and
 * the resync path treat the shards as one feed.
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

        /**
         * Streams per socket. Under [MAX_COMBINED_STREAMS] because every stream
         * name is embedded in the request URL (~22 chars each) and oversized
         * request lines are rejected before Binance's own stream limit is
         * reached. 500 streams is ~12 KB of URL, comfortably above the 325-stream
         * subscription every earlier build ran on a single socket, and a
         * whole-exchange universe fits in three sockets.
         */
        const val MAX_STREAMS_PER_SOCKET = 500

        /** Splits a universe into per-socket subscription groups, order preserved. */
        fun shard(symbols: List<String>): List<List<String>> =
            symbols.chunked(MAX_STREAMS_PER_SOCKET)
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

    /** One socket and the slice of the universe it carries. */
    private class Shard(val symbols: List<String>) {
        var socket: WebSocket? = null
        var status: WsStatus = WsStatus.DISCONNECTED
        var lastMessageAt: Long = 0L
        var reconnectAttempts: Int = 0
        var openCount: Int = 0
        var reconnectJob: Job? = null
        var resyncJob: Job? = null
    }

    private var shards: List<Shard> = emptyList()
    private var subscribedSymbols: List<String> = emptyList()
    private var watchdogJob: Job? = null
    private var manualClose = false

    /** How many times [connect] has been called; >1 means a re-subscription. */
    private var connectCount = 0

    /** Whether this connect generation has already reported the feed as up. */
    private var generationNotified = false

    /**
     * Invoked when the feed (re)establishes itself: once when every shard of a
     * connect generation has opened, and again whenever a dropped shard comes
     * back.
     *
     * The owner uses it to discard observations collected while the feed was
     * down. Ages recorded during an outage describe a disconnection, not the
     * market, and must not train the age gate open.
     */
    var onResync: (() -> Unit)? = null

    /** Opens the combined stream(s) for the given symbols. */
    fun connect(symbols: List<String>) {
        if (symbols.isEmpty()) return
        manualClose = false
        subscribedSymbols = symbols
        closeSockets()
        connectCount += 1
        generationNotified = false
        shards = shard(symbols).map { Shard(it) }
        _status.value = WsStatus.CONNECTING
        for (s in shards) openSocket(s)
        startWatchdog()
    }

    private fun openSocket(shard: Shard) {
        // Binance caps a combined stream at 1024 streams per connection and
        // rejects the handshake beyond that, so an oversized shard must fail
        // loudly here rather than as a mysterious disconnect loop.
        check(shard.symbols.size <= MAX_COMBINED_STREAMS) {
            "Combined stream would subscribe ${shard.symbols.size} tickers; " +
                "Binance allows at most $MAX_COMBINED_STREAMS per connection"
        }
        val streams = shard.symbols.joinToString("/") { "${it.lowercase()}@depth@100ms" }
        val request = Request.Builder().url("$WS_URL?streams=$streams").build()
        shard.socket = http.newWebSocket(request, listenerFor(shard))
    }

    private fun listenerFor(shard: Shard) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            shard.reconnectAttempts = 0
            shard.openCount += 1
            shard.status = WsStatus.CONNECTED
            shard.lastMessageAt = System.currentTimeMillis()
            _lastError.value = null
            updateStatus()
            if (shard.openCount > 1) {
                // This shard dropped and came back: its books are gapped.
                // Re-seed proactively instead of waiting for the watchdog.
                requestFullResync(shard)
                onResync?.invoke()
            } else if (shards.all { it.openCount >= 1 } && !generationNotified) {
                generationNotified = true
                onResync?.invoke()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            shard.lastMessageAt = System.currentTimeMillis()
            _lastMessageAt.value = shard.lastMessageAt
            _messagesReceived.value += 1
            handleMessage(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _lastError.value = t.message ?: "websocket failure"
            scheduleReconnect(shard)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!manualClose) scheduleReconnect(shard)
            else {
                shard.status = WsStatus.DISCONNECTED
                updateStatus()
            }
        }
    }

    /** Aggregate status: the feed is only CONNECTED when every shard is. */
    private fun updateStatus() {
        val states = shards.map { it.status }
        _status.value = when {
            states.isEmpty() -> WsStatus.DISCONNECTED
            states.all { it == WsStatus.CONNECTED } -> WsStatus.CONNECTED
            states.any { it == WsStatus.RECONNECTING } -> WsStatus.RECONNECTING
            states.any { it == WsStatus.CONNECTING } -> WsStatus.CONNECTING
            states.all { it == WsStatus.DISCONNECTED } -> WsStatus.DISCONNECTED
            // Mixed live/closed without a scheduled retry is transient; the
            // closed shard schedules its own reconnect on the way out.
            else -> WsStatus.RECONNECTING
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

    private fun scheduleReconnect(shard: Shard) {
        if (manualClose || shard.status == WsStatus.RECONNECTING) return
        shard.status = WsStatus.RECONNECTING
        updateStatus()
        shard.reconnectJob = scope.launch {
            // Exponential backoff capped at 30s
            val backoffMs = (1000L shl shard.reconnectAttempts.coerceAtMost(5))
            shard.reconnectAttempts += 1
            delay(backoffMs)
            if (!manualClose) openSocket(shard)
        }
    }

    /**
     * Stale-connection watchdog: if a shard receives no message within 15s,
     * force that shard to reconnect. Binance pushes depth for active symbols
     * far more often than that; quietness implies a dead connection. A silent
     * shard whose symbols are all permanently quiet produces no messages at
     * all - the dead-book probe removes those tickers from the universe, so
     * "silent shard" stays a connection-level signal.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive && !manualClose) {
                delay(5000)
                val now = System.currentTimeMillis()
                for (shard in shards) {
                    val last = shard.lastMessageAt
                    if (shard.status == WsStatus.CONNECTED && last != 0L &&
                        now - last > 15_000
                    ) {
                        _lastError.value = "stale connection detected (no messages for 15s)"
                        shard.socket?.cancel()
                        scheduleReconnect(shard)
                    }
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
     * Re-seeds one shard's tickers with fresh REST snapshots, in batches so a
     * reconnect does not fire hundreds of simultaneous requests.
     */
    private fun requestFullResync(shard: Shard) {
        shard.resyncJob?.cancel()
        shard.resyncJob = scope.launch {
            for (batch in shard.symbols.chunked(RESYNC_BATCH_SIZE)) {
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

    private fun closeSockets() {
        for (shard in shards) {
            shard.reconnectJob?.cancel()
            shard.resyncJob?.cancel()
            shard.socket?.close(1000, "resubscribe")
            shard.status = WsStatus.DISCONNECTED
        }
    }

    fun close() {
        manualClose = true
        watchdogJob?.cancel()
        closeSockets()
        shards = emptyList()
        _status.value = WsStatus.DISCONNECTED
    }
}
