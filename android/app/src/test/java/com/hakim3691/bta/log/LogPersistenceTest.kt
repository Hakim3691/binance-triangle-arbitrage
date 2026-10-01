package com.hakim3691.bta.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The log has to outlive the process that wrote it.
 *
 * The scanner is built to survive being killed in the background: a foreground
 * service is restarted by the system and resumes the session. The log used to be
 * a ring buffer in memory only, so every one of those restarts began with an
 * empty log - and a twenty-minute run that was killed and resumed exported only
 * the lines written since the last restart. That is exactly backwards: the
 * process death is the interesting event, and it was the one thing guaranteed
 * not to be recorded.
 *
 * These tests drive the real object (not a fake), against a real file, because
 * the failure mode being guarded against - a line that never reaches the disk -
 * is invisible to any assertion that does not look at the disk.
 */
class LogPersistenceTest {

    private val dir: File = createTempDir(prefix = "bta-log-test")

    @After
    fun tearDown() {
        LogRepository.clear()
        dir.deleteRecursively()
    }

    /**
     * attach()/clear()/log() hand file work to a single background writer, so
     * every assertion about the file has to wait for that queue to drain.
     * awaitIdle submits a barrier behind all pending writes and waits on it.
     */
    private fun settle() {
        assertTrue("log writer did not drain", LogRepository.awaitIdle())
    }

    @Test
    fun `clear queued before an attach does not truncate the new session`() {
        // Both operations go to the same background writer, so their order is
        // the caller's order. clear() used to resolve the log file when its
        // task RAN rather than when it was queued, so a clear issued just
        // before an attach truncated the file that had meanwhile become the
        // live log - destroying the new session's file on its way in. Caught by
        // this suite running the tests in a different order than in isolation,
        // which is exactly the kind of bug a per-method test misses.
        val old = File(dir, "old.log")
        LogRepository.attach(old)
        settle()

        LogRepository.clear()
        val fresh = File(dir, "fresh.log")
        LogRepository.attach(fresh)
        LogRepository.info("main", "the new session")
        settle()

        assertTrue("new log file missing", fresh.exists())
        assertTrue(
            "the new session's line was destroyed by a stale clear: " + fresh.readText(),
            fresh.readText().contains("the new session")
        )
    }

    @Test
    fun `a logged line reaches the log file`() {
        val file = File(dir, "bta.log")
        LogRepository.attach(file)
        LogRepository.info("main", "persisted line")
        settle()
        assertTrue("log file was not created", file.exists())
        val body = file.readText()
        assertTrue("line missing from file: $body", body.contains("persisted line"))
    }

    @Test
    fun `the channel and level survive the round trip to disk`() {
        val file = File(dir, "bta.log")
        LogRepository.attach(file)
        LogRepository.error("binance", "channel and level check")
        settle()
        val line = file.readLines().last { it.contains("channel and level check") }
        val parts = line.split("\t")
        assertEquals(4, parts.size)
        assertEquals("ERROR", parts[1])
        assertEquals("binance", parts[2])
        assertTrue(parts[0].toLong() > 0L)
    }

    @Test
    fun `previous process lines are replayed into the buffer after a restart`() {
        val file = File(dir, "bta.log")
        // Stand in for the process that was killed: write a file directly, the
        // way the dead process would have left it behind.
        file.parentFile?.mkdirs()
        file.writeText(
            listOf(
                "1700000000000\tINFO\tmain\tProcess was killed in the background",
                "1700000001000\tWARN\tuniverse\tDead-book probe: 56 of 1198 tickers"
            ).joinToString("\n") + "\n"
        )

        // A new process attaches to the same file - the resume path.
        LogRepository.attach(file)
        settle()

        val messages = LogRepository.snapshot().map { it.message }
        assertTrue(
            "previous session was not replayed: $messages",
            messages.contains("Process was killed in the background")
        )
        assertTrue(
            "previous session was not replayed: $messages",
            messages.contains("Dead-book probe: 56 of 1198 tickers")
        )
        // Levels come back too, so the replayed history reads correctly in the
        // exported text rather than appearing as undifferentiated INFO.
        val replayed = LogRepository.snapshot().first { it.message.contains("Dead-book probe") }
        assertEquals(LogLevel.WARN, replayed.level)
    }

    @Test
    fun `replayed lines are not re-suppressed, and repeat lines are still deduped live`() {
        val file = File(dir, "bta.log")
        file.writeText("1700000000000\tINFO\tmain\tseen before the kill\n")

        LogRepository.attach(file)
        settle()
        // The same text logged now must still be recorded: it happened after
        // the restart, and the dedupe window is one second, long past.
        LogRepository.info("main", "seen before the kill")
        settle()

        val occurrences = file.readLines().count { it.endsWith("seen before the kill") }
        assertEquals("expected the new line to be appended, file: " + file.readText(), 2, occurrences)
    }

    @Test
    fun `a repeated message inside the dedupe window is written once`() {
        val file = File(dir, "bta.log")
        LogRepository.attach(file)
        repeat(5) { LogRepository.info("performance", "identical chatter") }
        settle()
        val occurrences = file.readLines().count { it.endsWith("identical chatter") }
        assertEquals("dedupe window should collapse these into one line", 1, occurrences)
    }

    @Test
    fun `clear empties the file as well as the buffer`() {
        val file = File(dir, "bta.log")
        LogRepository.attach(file)
        LogRepository.info("main", "before clear")
        settle()

        LogRepository.clear()
        settle()

        assertEquals("buffer not cleared", 0, LogRepository.snapshot().size)
        // The file must be empty too. Leaving it populated would make the
        // cleared session reappear in the next export after a restart, which
        // looks exactly like a log that ignores "Clear logs".
        assertTrue("file not truncated: " + file.readText(), file.readText().isBlank())
    }

    @Test
    fun `a missing log file is not an error`() {
        // The scanner must start even if the log directory cannot be created;
        // a diagnostic aid is not allowed to be a startup dependency.
        val file = File(dir, "does/not/exist/yet/bta.log")
        LogRepository.attach(file)
        LogRepository.info("main", "still logging")
        settle()
        assertTrue(file.exists())
        assertTrue(file.readText().contains("still logging"))
    }

    @Test
    fun `newlines in a message cannot forge a second log record`() {
        val file = File(dir, "bta.log")
        LogRepository.attach(file)
        LogRepository.info("main", "line one\n1700000000000\tINFO\tmain\tforged line")
        settle()
        val matching = file.readLines().count { it.endsWith("forged line") }
        assertEquals("an embedded newline must not create a second record", 1, matching)
    }
}
