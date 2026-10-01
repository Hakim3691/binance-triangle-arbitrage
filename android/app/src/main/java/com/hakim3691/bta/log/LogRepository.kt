package com.hakim3691.bta.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.Executors

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val channel: String,
    val message: String
)

/**
 * Log repository exposed to the Logs screen, backed by a ring buffer AND an
 * append-only file.
 *
 * It used to be memory-only, which quietly destroyed the most valuable data the
 * app produces. The scanner is explicitly designed to survive being killed in
 * the background (a foreground service restarts it), and on every such restart
 * the ring buffer started empty - so the Logs screen and every export showed
 * only what happened since the last process death. A twenty-minute session that
 * was killed and resumed five times exported the final fourteen lines and
 * nothing else: the resume banner, then the tail. That is not a log, it is a
 * coincidence, and it makes exactly the failure it is needed for - a scanner
 * that dies and resumes - impossible to diagnose after the fact.
 *
 * The file sink fixes that: lines are appended as they are logged, the previous
 * run's tail is loaded back on startup, and the buffer is pre-seeded with it so
 * the screen and exports show the whole story. Writes go through a single
 * background thread, because logging happens on the scan hot path and must not
 * block it on file I/O.
 *
 * Mirrors the original's separated execution/performance/binance loggers
 * through the [channel] field. Never log credentials through this.
 */
object LogRepository {
    private const val MAX_ENTRIES = 2000
    private const val DEDUPE_WINDOW_MS = 1_000L

    /** Lines replayed into the buffer from the previous process's log file. */
    private const val REPLAY_LINES = 400

    /** Rotate rather than grow without bound; sessions are hours, not weeks. */
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024

    /** Bytes read from the end of the log file when replaying it on startup. */
    private const val TAIL_WINDOW_BYTES = 128L * 1024

    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private val lastSeenByKey = HashMap<Int, Long>()
    private val lastThrottle = HashMap<String, Long>()

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    /** Dedicated writer thread: logging must never block the scan loop. */
    private val writer: java.util.concurrent.ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "bta-log-writer").apply { isDaemon = true }
        }

    @Volatile private var logFile: File? = null

    @Volatile private var sink: BufferedWriter? = null

    /** Guards [sink] and [rotate]; every writer task is serialised by the
     *  executor anyway, but the field is also read from [attach]. */
    private val sinkLock = Any()

    /**
     * Binds the on-disk log. Safe to call more than once; the last call wins.
     * Called from Application.onCreate so every process - including one started
     * only to redeliver the foreground service - has a sink before it logs.
     */
    fun attach(file: File) {
        logFile = file
        // Every step runs on the writer thread: attach() is called from
        // Application.onCreate, i.e. the main thread, and tailing a log file is
        // file I/O. Nothing here may block startup.
        writer.execute {
            runCatching {
                file.parentFile?.mkdirs()
                replayFrom(file)
                synchronized(sinkLock) {
                    sink?.close()
                    sink = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8), 8192)
                }
            }
        }
    }

    /**
     * Loads the tail of the previous process's log into the buffer so the Logs
     * screen and every export start with the run that was interrupted, not with
     * an empty screen. Never throws: a corrupt or unreadable file must not stop
     * the scanner from starting.
     */
    private fun replayFrom(file: File) {
        val replayed = runCatching { tailLines(file) }
            .getOrElse { emptyList() }
            .mapNotNull(::parseLine)
        if (replayed.isEmpty()) return
        synchronized(buffer) {
            // Mark the replayed lines as already seen so a message repeated
            // across the process boundary is not duplicated into the file.
            for (e in replayed) {
                lastSeenByKey[lineKey(e.channel, e.message)] = e.timestamp
            }
            if (lastSeenByKey.size > 512) lastSeenByKey.clear()
            for (e in replayed) {
                buffer.addLast(e)
            }
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        }
        _entries.value = synchronized(buffer) { buffer.toList() }
    }

    /**
     * Last [REPLAY_LINES] lines, read from the end of the file.
     *
     * Only the tail is read: the file can be megabytes after a long session, and
     * a full scan of it on startup would be a main-thread stall for data that
     * is then thrown away by the ring buffer anyway. The last partial line is
     * dropped, since it was cut mid-write by whatever killed the process.
     */
    private fun tailLines(file: File): List<String> {
        if (!file.exists() || file.length() == 0L) return emptyList()
        val window = minOf(file.length(), TAIL_WINDOW_BYTES)
        val start = file.length() - window
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val bytes = ByteArray(window.toInt())
            raf.readFully(bytes)
            val text = String(bytes, Charsets.UTF_8)
            val lines = text.split("\n").toMutableList()
            // A partial first line means the window began mid-record.
            if (start > 0 && lines.isNotEmpty()) lines.removeAt(0)
            while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
            return if (lines.size > REPLAY_LINES) lines.subList(lines.size - REPLAY_LINES, lines.size).toList() else lines
        }
    }

    /** Serialises one line to the file. Failures are dropped, never thrown. */
    private fun persist(entry: LogEntry) {
        val file = logFile ?: return
        writer.execute {
            runCatching {
                if (file.length() > MAX_FILE_BYTES) rotate(file)
                synchronized(sinkLock) {
                    val w = sink ?: return@runCatching
                    w.write(formatLine(entry))
                    w.write("\n")
                    // Flushed per line: the whole point of the file is to
                    // survive the process being killed, and a buffered writer
                    // would lose exactly the lines before the kill.
                    w.flush()
                }
            }
        }
    }

    private fun rotate(file: File) {
        val previous = File(file.parentFile, file.name + ".1")
        runCatching {
            previous.delete()
            file.renameTo(previous)
            sink?.close()
            sink = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8), 8192)
        }
    }

    /**
     * Blocks until every line logged so far has been written to disk.
     *
     * Exists for tests, which assert on the file. Production code must never
     * call it: writes are asynchronous on purpose so that logging a line from
     * the scan hot path cannot block the scan on a disk write.
     */
    internal fun awaitIdle(timeoutMs: Long = 5_000L): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        writer.execute { latch.countDown() }
        return latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** One line: epoch, level, channel, message. Tab separated, no escaping. */
    private fun formatLine(e: LogEntry): String =
        e.timestamp.toString() + "\t" + e.level.name + "\t" + e.channel + "\t" +
            e.message.replace("\n", " ").replace("\t", " ")

    private fun parseLine(line: String): LogEntry? {
        val parts = line.split("\t", limit = 4)
        if (parts.size != 4) return null
        val ts = parts[0].toLongOrNull() ?: return null
        val level = runCatching { LogLevel.valueOf(parts[1]) }.getOrNull() ?: return null
        return LogEntry(ts, level, parts[2], parts[3])
    }

    private fun lineKey(channel: String, message: String) = 31 * channel.hashCode() + message.hashCode()

    fun log(level: LogLevel, channel: String, message: String) {
        val now = System.currentTimeMillis()
        val key = lineKey(channel, message)
        val entry: LogEntry
        synchronized(buffer) {
            val last = lastSeenByKey[key]
            if (last != null && now - last < DEDUPE_WINDOW_MS) {
                return  // identical message within the window: suppress
            }
            lastSeenByKey[key] = now
            if (lastSeenByKey.size > 512) lastSeenByKey.clear()
            entry = LogEntry(now, level, channel, message)
            buffer.addLast(entry)
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        }
        _entries.value = synchronized(buffer) { buffer.toList() }
        persist(entry)
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

    /**
     * Clears the on-screen buffer AND the file.
     *
     * Truncating the file matters: "Clear logs" that only cleared the ring
     * buffer would leave the previous session on disk, and it would silently
     * reappear in the next export after a process restart - the exact
     * confusion this sink exists to remove.
     */
    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            lastSeenByKey.clear()
        }
        _entries.value = emptyList()
        // The file is captured HERE, not inside the task. The writer is
        // asynchronous, so reading logFile when the task happens to run means
        // acting on whatever file is attached by then: a clear queued just
        // before an attach() would truncate the NEW log and silently destroy
        // the session that was about to be written into it.
        val file = logFile
        writer.execute {
            file ?: return@execute
            runCatching {
                synchronized(sinkLock) {
                    sink?.close()
                    sink = BufferedWriter(OutputStreamWriter(FileOutputStream(file, false), Charsets.UTF_8), 8192)
                }
            }
        }
    }
}
