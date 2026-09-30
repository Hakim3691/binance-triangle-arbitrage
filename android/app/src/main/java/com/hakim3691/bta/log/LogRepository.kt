package com.hakim3691.bta.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val channel: String,
    val message: String
)

/**
 * In-memory ring-buffer log repository exposed to the Logs screen.
 * Mirrors the original's separated execution/performance/binance loggers
 * through the [channel] field. Never log credentials through this.
 */
object LogRepository {
    private const val MAX_ENTRIES = 2000
    private const val DEDUPE_WINDOW_MS = 1_000L
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private val lastSeenByKey = HashMap<Int, Long>()
    private val lastThrottle = HashMap<String, Long>()

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    fun log(level: LogLevel, channel: String, message: String) {
        val now = System.currentTimeMillis()
        val key = 31 * channel.hashCode() + message.hashCode()
        synchronized(buffer) {
            val last = lastSeenByKey[key]
            if (last != null && now - last < DEDUPE_WINDOW_MS) {
                return  // identical message within the window: suppress
            }
            lastSeenByKey[key] = now
            if (lastSeenByKey.size > 512) lastSeenByKey.clear()
            buffer.addLast(LogEntry(now, level, channel, message))
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        }
        _entries.value = synchronized(buffer) { buffer.toList() }
    }

    fun debug(channel: String, message: String) = log(LogLevel.DEBUG, channel, message)
    fun info(channel: String, message: String) = log(LogLevel.INFO, channel, message)
    fun warn(channel: String, message: String) = log(LogLevel.WARN, channel, message)
    fun error(channel: String, message: String) = log(LogLevel.ERROR, channel, message)

    /** The full buffer, oldest first, for the text export. */
    fun snapshot(): List<LogEntry> = synchronized(buffer) { buffer.toList() }

    /**
     * Compact stack trace for a failure line. An exception message alone
     * ("f != java.lang.Integer") is not enough to locate the defect that
     * produced it, and the buffer is small, so only the first few frames
     * of this app's own code are kept.
     */
    fun stackTrace(t: Throwable, maxFrames: Int = 6): String {
        val sb = StringBuilder()
        for (f in t.stackTrace.take(maxFrames)) {
            if (sb.isNotEmpty()) sb.append(" <- ")
            sb.append(f.className.substringAfterLast('.'))
                .append('.').append(f.methodName)
                .append(':').append(f.lineNumber)
        }
        return sb.toString()
    }

    /**
     * Emits at most one line per [key] per [windowMs].
     *
     * The message-level dedupe above cannot collapse a line whose text carries
     * a varying number ("Skipped 2", "Skipped 4", ...), so per-cycle chatter
     * passes straight through it. Callers pass a stable key instead and the
     * message is only built when the line is actually going to be emitted.
     */
    fun throttled(
        level: LogLevel,
        channel: String,
        key: String,
        windowMs: Long,
        message: () -> String
    ) {
        val now = System.currentTimeMillis()
        synchronized(lastThrottle) {
            val last = lastThrottle[key]
            if (last != null && now - last < windowMs) return
            if (lastThrottle.size > 512) lastThrottle.clear()
            lastThrottle[key] = now
        }
        log(level, channel, message())
    }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
        _entries.value = emptyList()
    }
}
