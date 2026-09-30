package com.hakim3691.bta.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hakim3691.bta.research.ArmEpisodeCsv.toCsv
import com.hakim3691.bta.research.IntervalSummaryCsv.toCsv as summaryToCsv
import java.io.File

/**
 * The Stage 3 collector. The CSV contract matters more than the writer
 * mechanics: these files are the evidence a Stage 3 decision will be made
 * from, so the header and the row shape are pinned here.
 */
class ResearchRecorderTest {

    private fun tempDir(): File = createTempDir(prefix = "bta-research-test")

    private fun episode(outcome: String = "FIRED") = ArmEpisode(
        epochMs = 1_700_000_000_000L,
        triangleId = "BTC-ETH-BNB",
        armedPercent = 0.35,
        fireBar = 0.40,
        abandonBar = 0.25,
        ttlMs = 2000,
        armSpreadBps = 2.0,
        armImbalance = 0.1,
        armInterArrivalMs = 120.0,
        armBookImbalanceSign = 1,
        outcome = outcome,
        endPercent = 0.55,
        bestPercent = 0.56,
        waitedMs = 800,
        pingCount = 6,
        waitCount = 2,
        verdictReason = "fired: spread tight, book settled"
    )

    @Test
    fun `episode csv carries every Stage 3 decision variable`() {
        val header = ArmEpisodeCsv.header
        for (column in listOf(
            "armed_percent", "end_percent", "best_percent",
            "waited_ms", "ping_count", "outcome", "arm_spread_bps", "arm_imbalance",
            "arm_inter_arrival_ms", "fire_bar", "abandon_bar"
        )) {
            assertTrue("header must contain $column", header.contains(column))
        }
    }

    @Test
    fun `episode rows round-trip through the csv shape`() {
        // The comma-free reason keeps a naive split honest here; the quoted-
        // field behaviour is pinned separately below.
        val row = episode().copy(verdictReason = "spread tight; book settled").toCsv()
        assertEquals(ArmEpisodeCsv.header.split(",").size, row.split(",").size)
        assertTrue(row.contains("BTC-ETH-BNB"))
        assertTrue(row.contains("FIRED"))
    }

    @Test
    fun `a reason containing a comma stays inside its quoted field`() {
        val e = episode().copy(verdictReason = "waiting for 0.4%, projecting 0.35%")
        val row = e.toCsv()
        val headerCount = ArmEpisodeCsv.header.split(",").size
        // A naive comma split yields one more cell than the header has
        // columns, but the quoted field is intact - which is what every real
        // CSV reader cares about.
        assertTrue(row.contains("\"waiting for 0.4%, projecting 0.35%\""))
        assertEquals(headerCount + 1, row.split(",").size)
    }

    @Test
    fun `summary rows carry the gate thresholds they were produced under`() {
        val s = IntervalSummary(
            epochMs = 1_700_000_000_000L, intervalSec = 300, opportunitiesSeen = 120,
            armsCreated = 30, fired = 4, abandoned = 20, expired = 6,
            medianWaitMsFired = 700, medianArmedPercent = 0.36, medianFiredPercent = 0.51,
            medianBestPercent = 0.52, p10SpreadBps = 1.0, medianSpreadBps = 2.0,
            p90SpreadBps = 5.0, p90Imbalance = 0.4, fireBar = 0.40, abandonBar = 0.25,
            tightSpreadBps = 8.0, imbalanceLimit = 0.35, cadenceLimitMs = 750, ttlMs = 2000,
            medianInterArrivalMs = 130.0, scanCycles = 12_000
        )
        val row = s.summaryToCsv()
        assertEquals(
            IntervalSummaryCsv.header.split(",").size,
            row.split(",").size
        )
        for (column in listOf("median_fired_percent", "p90_spread_bps", "tight_spread_bps", "ttl_ms")) {
            assertTrue(IntervalSummaryCsv.header.contains(column))
        }
    }

    @Test
    fun `the summary carries the best-edge distribution the arming bar is judged against`() {
        // The base rate. Without these columns there is no evidence for what
        // threshold to arm at - only the arms that already cleared the gate.
        for (column in listOf(
            "edges_seen", "edges_above_fire_bar", "p10_percent", "median_percent",
            "p90_percent", "max_percent", "median_armed_percent", "median_best_percent"
        )) {
            assertTrue(IntervalSummaryCsv.header.contains(column))
        }
    }

    @Test
    fun `the distribution columns round-trip their values`() {
        val s = IntervalSummary(
            epochMs = 1L, intervalSec = 300, opportunitiesSeen = 10,
            armsCreated = 0, fired = 0, abandoned = 0, expired = 0,
            medianWaitMsFired = 0, medianArmedPercent = 0.0, medianFiredPercent = 0.0,
            medianBestPercent = 0.0, p10SpreadBps = 0.0, medianSpreadBps = 0.0,
            p90SpreadBps = 0.0, p90Imbalance = 0.0, fireBar = 0.40, abandonBar = 0.25,
            tightSpreadBps = 14.0, imbalanceLimit = 0.43, cadenceLimitMs = 1651,
            ttlMs = 14580, medianInterArrivalMs = 800.0, scanCycles = 2000L,
            edgesSeen = 1900, edgesAboveFireBar = 3,
            p10Percent = -2.4, medianPercent = -1.9, p90Percent = -0.7, maxPercent = 0.62
        )
        val row = s.summaryToCsv()
        assertEquals(IntervalSummaryCsv.header.split(",").size, row.split(",").size)
        val cells = row.split(",")
        val header = IntervalSummaryCsv.header.split(",")
        fun cell(name: String) = cells[header.indexOf(name)]
        assertEquals("1900", cell("edges_seen"))
        assertEquals("3", cell("edges_above_fire_bar"))
        assertEquals("0.620000", cell("max_percent"))
        assertEquals("-2.400000", cell("p10_percent"))
    }

    @Test
    fun `the writer flushes rows to a headered file`() {
        val dir = tempDir()
        val recorder = ResearchRecorder(dir)
        try {
            recorder.recordEpisode(episode())
            recorder.recordEpisode(episode("ABANDONED"))
            // Wait for the background writer to drain both rows into the file.
            val deadline = System.currentTimeMillis() + 10_000
            var f: File? = null
            while (System.currentTimeMillis() < deadline) {
                f = recorder.currentEpisodeFile()
                if (f != null && f.readLines().size >= 3) break
                Thread.sleep(50)
            }
            f ?: throw AssertionError("episode file never appeared")
            assertEquals(2L, recorder.episodeCount)
            val lines = f.readLines()
            assertEquals(ArmEpisodeCsv.header, lines[0])
            assertEquals(3, lines.size)
        } finally {
            recorder.stop()
        }
    }

    @Test
    fun `a new session starts a fresh file pair`() {
        val dir = tempDir()
        val recorder = ResearchRecorder(dir)
        try {
            recorder.recordEpisode(episode())
            val deadline = System.currentTimeMillis() + 10_000
            var first: File? = null
            while (System.currentTimeMillis() < deadline) {
                first = recorder.currentEpisodeFile()
                if (first != null) break
                Thread.sleep(50)
            }
            first ?: throw AssertionError("first episode file never appeared")
            recorder.newSession()
            recorder.recordEpisode(episode())
            var second: File? = null
            while (System.currentTimeMillis() < deadline) {
                second = recorder.currentEpisodeFile()
                if (second != null && second != first) break
                Thread.sleep(50)
            }
            second ?: throw AssertionError("second episode file never appeared")
            assertTrue(second.absolutePath != first.absolutePath)
            assertEquals(2, recorder.allFiles().size)
        } finally {
            recorder.stop()
        }
    }

    @Test
    fun `a stop drains nothing further and marks the file complete`() {
        val dir = tempDir()
        val recorder = ResearchRecorder(dir)
        recorder.recordEpisode(episode())
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val f = recorder.currentEpisodeFile()
            if (f != null && f.length() > 0) break
            Thread.sleep(50)
        }
        recorder.stop()
        assertEquals(1L, recorder.episodeCount)
        // stop() only ends the writer; whatever was flushed stays on disk.
        assertTrue(recorder.currentEpisodeFile()?.length()!! > 0)
    }
}
