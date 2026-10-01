package com.hakim3691.bta.market

import com.hakim3691.bta.core.DepthSnapshot
import com.hakim3691.bta.core.SortedDepth
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the synchronized local order book for every watched ticker.
 *
 * This is the Kotlin equivalent of node-binance-api's `depthCache` support as
 * used by the original app (snapshot + buffered diff updates + out-of-sync
 * teardown). Semantics preserved:
 *
 *  - A REST snapshot is fetched per symbol; websocket diffs received before the
 *    snapshot arrives are queued and replayed afterwards.
 *  - Diff events with `u < lastUpdateId + 1` are discarded; events with
 *    `U > lastUpdateId + 1` trigger a full resync (new snapshot), mirroring the
 *    library's out-of-sync handling.
 *  - The consumer callback fires only for events applied *after* the snapshot
 *    (the original's `context && cb(ticker)` behavior).
 *  - eventTime tracks the latest applied event time `E`.
 */
class DepthCacheManager(
    /** Injectable so freshness maths can be tested without sleeping. */
    private val clock: () -> Long = System::currentTimeMillis
) {

    data class TickerUpdateEvent(val ticker: String, val snapshot: DepthSnapshot)

    private class Context {
        var snapshotUpdateId: Long? = null
        var lastEventUpdateId: Long? = null
        var messageQueue = ArrayDeque<DiffEvent>()
        var bids = LinkedHashMap<Double, Double>()
        var asks = LinkedHashMap<Double, Double>()
        /** Binance server event time (E) of the last applied update. */
        var eventTime = 0L
        /** Local receive time of the last applied update - used for age math. */
        var localEventTime = 0L
    }

    data class DiffEvent(
        val symbol: String,
        val firstUpdateId: Long,   // U
        val finalUpdateId: Long,   // u
        val eventTime: Long,       // E
        val bids: List<Pair<Double, Double>>,
        val asks: List<Pair<Double, Double>>
    )

    private val contexts = ConcurrentHashMap<String, Context>()

    /**
     * Offset = Binance server time - local time (ms), measured from REST responses.
     * Binance event timestamps (E) are server-clock; using them directly against
     * local System.currentTimeMillis() produces negative ages when the device
     * clock runs ahead. All age math goes through [toLocalTimeline].
     */
    @Volatile
    var clockSkewMs: Long = 0L
        private set

    /** Updates the measured skew (called from REST time sync). */
    fun updateClockSkew(serverTimeMs: Long, localTimeAtReceiveMs: Long) {
        val measured = serverTimeMs - localTimeAtReceiveMs
        // Exponential smoothing to reject outliers
        clockSkewMs = if (clockSkewMs == 0L) measured else (clockSkewMs * 3 + measured) / 4
    }

    /** Converts a Binance server timestamp to the local timeline. */
    fun toLocalTimeline(serverTimeMs: Long): Long = serverTimeMs - clockSkewMs

    private val _updates = MutableSharedFlow<TickerUpdateEvent>(
        extraBufferCapacity = 4096,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val updates: SharedFlow<TickerUpdateEvent> = _updates

    private val _syncedCount = MutableStateFlow(0)
    val syncedCount: StateFlow<Int> = _syncedCount

    /** Registers tickers and resets their state. Port of depthCacheStaggered init. */
    fun register(tickers: Collection<String>) {
        for (t in tickers) {
            contexts[t] = Context()
        }
        _syncedCount.value = 0
    }

    fun watching(): List<String> = contexts.keys.toList()

    fun isSynced(ticker: String): Boolean =
        contexts[ticker]?.snapshotUpdateId != null

    fun syncedTickers(): Int = contexts.values.count { it.snapshotUpdateId != null }

    /**
     * Whether a ticker holds a usable snapshot that is also *recent*.
     *
     * [isSynced] only answers "did a snapshot ever arrive", which stays true
     * forever once seeded - so it reports a healthy-looking 325/325 while the
     * books are ten minutes old. Freshness is the question that actually
     * matters for trading, so it is tracked separately.
     */
    fun isFresh(ticker: String, maxAgeMs: Long, now: Long = System.currentTimeMillis()): Boolean {
        val ctx = contexts[ticker] ?: return false
        if (ctx.snapshotUpdateId == null) return false
        val last = ctx.localEventTime
        return last != 0L && now - last <= maxAgeMs
    }

    /** Snapshot of how much of the universe is genuinely current. */
    data class Freshness(
        val total: Int,
        val synced: Int,
        val fresh: Int,
        /** Age of the oldest book in the universe, ms. */
        val stalestAgeMs: Long,
        /** Age of the newest book, ms. */
        val freshestAgeMs: Long,
        val staleTickers: List<String>
    ) {
        /** True when the whole universe has gone quiet, i.e. the feed is dead. */
        val feedDead: Boolean get() = total > 0 && fresh == 0
    }

    fun freshness(maxAgeMs: Long, now: Long = System.currentTimeMillis()): Freshness {
        var synced = 0
        var fresh = 0
        var stalest = 0L
        var freshest = Long.MAX_VALUE
        val stale = ArrayList<String>()
        for ((ticker, ctx) in contexts) {
            if (ctx.snapshotUpdateId == null) {
                stale.add(ticker)
                continue
            }
            synced++
            val age = if (ctx.localEventTime == 0L) Long.MAX_VALUE / 4 else now - ctx.localEventTime
            if (age > stalest) stalest = age
            if (age < freshest) freshest = age
            if (age <= maxAgeMs) fresh++ else stale.add(ticker)
        }
        return Freshness(
            total = contexts.size,
            synced = synced,
            fresh = fresh,
            stalestAgeMs = stalest,
            freshestAgeMs = if (freshest == Long.MAX_VALUE) 0L else freshest,
            staleTickers = stale
        )
    }

    /** Apply a REST snapshot for a symbol; returns true when accepted. */
    @Synchronized
    fun applySnapshot(symbol: String, lastUpdateId: Long, bids: Map<Double, Double>, asks: Map<Double, Double>): Boolean {
        val ctx = contexts[symbol] ?: return false
        ctx.bids = LinkedHashMap(bids)
        ctx.asks = LinkedHashMap(asks)
        ctx.snapshotUpdateId = lastUpdateId
        ctx.localEventTime = clock()
        // Replay queued diffs with u > lastUpdateId
        val queue = ctx.messageQueue
        ctx.messageQueue = ArrayDeque()
        for (event in queue) {
            if (event.finalUpdateId > lastUpdateId) {
                applyDiff(event)
            }
        }
        _syncedCount.value = syncedTickers()
        return true
    }

    /** Apply a websocket diff event. Returns true when the book changed and is synced. */
    @Synchronized
    fun applyDiff(event: DiffEvent): Boolean {
        val ctx = contexts[event.symbol] ?: return false

        if (ctx.snapshotUpdateId == null) {
            // Snapshot not yet arrived: queue like the original messageQueue
            ctx.messageQueue.addLast(event)
            return false
        }

        val lastUpdateId = ctx.lastEventUpdateId
        if (lastUpdateId != null) {
            val expected = lastUpdateId + 1
            if (event.firstUpdateId > expected) {
                // Out of sync: tear down and re-request a snapshot (original throws -> reconnect)
                ctx.snapshotUpdateId = null
                ctx.lastEventUpdateId = null
                ctx.messageQueue.clear()
                outOfSyncSymbols.add(event.symbol)
                return false
            }
            if (event.finalUpdateId < expected) {
                // Already-contained event; ignore
                return false
            }
        } else {
            val snapshotId = ctx.snapshotUpdateId!!
            if (event.firstUpdateId > snapshotId + 1) {
                // Gap between snapshot and stream: resync
                ctx.snapshotUpdateId = null
                outOfSyncSymbols.add(event.symbol)
                return false
            }
            if (event.finalUpdateId < snapshotId + 1) {
                // Data older than snapshot; ignore
                return false
            }
        }

        for ((price, qty) in event.bids) {
            if (qty == 0.0) ctx.bids.remove(price) else ctx.bids[price] = qty
        }
        for ((price, qty) in event.asks) {
            if (qty == 0.0) ctx.asks.remove(price) else ctx.asks[price] = qty
        }
        ctx.eventTime = event.eventTime
        ctx.localEventTime = clock()
        ctx.lastEventUpdateId = event.finalUpdateId

        emitUpdate(event.symbol)
        return true
    }

    private val outOfSyncSymbols = ConcurrentHashMap.newKeySet<String>()

    /** Flag a symbol as needing a fresh REST snapshot. */
    fun markOutOfSync(symbol: String) {
        outOfSyncSymbols.add(symbol)
    }

    /** Symbols flagged for resnapshot after out-of-sync detection. */
    fun takeOutOfSyncSymbols(): Set<String> {
        val out = outOfSyncSymbols.toSet()
        outOfSyncSymbols.removeAll { true }
        return out
    }

    private fun emitUpdate(ticker: String) {
        val snap = getSortedSnapshot(ticker)
        if (snap != null) {
            _updates.tryEmit(TickerUpdateEvent(ticker, snap))
        }
    }

    /**
     * Port of BinanceApi.getDepthSnapshots: returns sorted depth limited to
     * [maxDepth] levels, re-sorting only when the underlying cache changed.
     */
    @Synchronized
    fun getSortedSnapshot(ticker: String, maxDepth: Int): DepthSnapshot? {
        val ctx = contexts[ticker] ?: return null
        if (ctx.snapshotUpdateId == null) return null
        return DepthSnapshot(
            bids = SortedDepth.sortBids(ctx.bids, maxDepth),
            asks = SortedDepth.sortAsks(ctx.asks, maxDepth),
            // Snapshots used by the engine carry the LOCAL receive timeline so
            // every age computation is skew-free.
            eventTime = ctx.localEventTime
        )
    }

    fun getSortedSnapshot(ticker: String): DepthSnapshot? =
        getSortedSnapshot(ticker, Int.MAX_VALUE)

    /** Raw (unsorted) map access, port of getDepthCacheUnsorted. */
    @Synchronized
    fun getRawDepth(ticker: String): Pair<Map<Double, Double>, Map<Double, Double>>? {
        val ctx = contexts[ticker] ?: return null
        return ctx.bids to ctx.asks
    }

    /** Server-timeline event time (display). */
    fun getEventTime(ticker: String): Long = contexts[ticker]?.eventTime ?: 0L

    /** Local-timeline event time (age math). */
    fun getLocalEventTime(ticker: String): Long = contexts[ticker]?.localEventTime ?: 0L

    /** Port of MarketCache.getTickersWithoutDepthCacheUpdate. */
    fun getTickersWithoutRecentUpdate(maxAgeMs: Long, now: Long = System.currentTimeMillis()): List<String> =
        contexts.entries
            .filter { it.value.localEventTime == 0L || now - it.value.localEventTime > maxAgeMs }
            .map { it.key }

    /**
     * Drops every context that is not in [tickers].
     *
     * Used by the dead-book probe: books that never produced a single update
     * are removed from the cache entirely so they cannot reappear in freshness
     * counts, sync progress, or the degraded-feed banner - the universe shrinks
     * rather than carrying permanent noise.
     */
    @Synchronized
    fun pruneTo(tickers: Collection<String>) {
        val keep = tickers.toSet()
        contexts.keys.retainAll(keep)
        outOfSyncSymbols.retainAll(keep)
        _syncedCount.value = syncedTickers()
    }

    fun clear() {
        contexts.clear()
        outOfSyncSymbols.clear()
        _syncedCount.value = 0
    }
}
