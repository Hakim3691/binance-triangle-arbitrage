package com.hakim3691.bta.scanner

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression cover for the Stage 3 evidence pipeline never running.
 *
 * `ensureResearchRecorder()` existed but had **no call sites**. With
 * `researchRecorder` permanently null, both recorders are guarded by
 * `researchRecorder ?: return`, so every arm episode and every interval summary
 * was dropped on the floor. Nothing errored: the panel simply read "0 rows",
 * and `arm_summaries_*.csv` - the document that decides whether a statistical
 * model is warranted - was never created. Three device runs over an hour could
 * not distinguish that from a market result, because the symptom looks exactly
 * like "no opportunities found".
 *
 * The fix calls the wiring from start(). This test pins the property that made
 * the bug survivable: the recorder must be attached before anything can record,
 * and a missing context must be reported rather than swallowed.
 */
class ResearchWiringTest {

    @Test
    fun `start attaches csv collection before anything can record`() {
        val source = File("src/main/java/com/hakim3691/bta/scanner/ScannerController.kt")
        assertTrue("ScannerController.kt not found at ${source.absolutePath}", source.isFile)
        val text = source.readText()

        // The call must live inside start(), not merely exist in the file.
        val startBody = text.substringAfter("suspend fun start()")
            .substringBefore("\n    /**", missingDelimiterValue = "")
        assertTrue(
            "start() must call ensureResearchRecorder(), otherwise researchRecorder " +
                "stays null and every recorded row is silently dropped",
            startBody.contains("ensureResearchRecorder()")
        )
    }

    @Test
    fun `a missing context is reported rather than swallowed`() {
        val source = File("src/main/java/com/hakim3691/bta/scanner/ScannerController.kt")
        val text = source.readText()
        val body = text.substringAfter("private fun ensureResearchRecorder()")

        assertTrue(
            "ensureResearchRecorder must warn when it cannot attach, otherwise an " +
                "unwired recorder is indistinguishable from a market result",
            body.contains("CSV collection unavailable")
        )
    }

    @Test
    fun `stop flushes a final summary so the last interval is not lost`() {
        val source = File("src/main/java/com/hakim3691/bta/scanner/ScannerController.kt")
        val text = source.readText()
        assertTrue(
            "stop() must force a final summary row",
            text.contains("maybeRecordSummary(System.currentTimeMillis(), 0, force = true)")
        )
    }
}
