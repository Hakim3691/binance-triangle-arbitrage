package com.hakim3691.bta.research

import com.hakim3691.bta.log.LogRepository
import com.hakim3691.bta.research.ArmEpisodeCsv.toCsv
import com.hakim3691.bta.research.IntervalSummaryCsv.toCsv
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * One row per arm episode: everything Stage 3 needs to decide whether a
 * statistical model earns its place.
 *
 * The decision variables, spelled out:
 *
 *  - `waitedMs`, `pingCount`, `bestPercentWhileArmed`, `firedPercent`,
 *    `armedPercent`: **does waiting actually pay?** If the median fired
 *    percent is not better than the median armed percent, patience buys
 *    nothing and the gates should be loosened, not modelled.
 *  - `armSpreadBps`, `armImbalance`, `armInterArrivalMs`, `armBookImbalanceSign`:
 *    **do book features at detection predict episode outcome?** A model is
 *    only worth building if these features separate fired from abandoned
 *    episodes; if they do not, Stage 3 has no signal to model.
 *  - `fireBar`, `abandonBar`, `ttlMs`: **the bar context**, so rows stay
 *    comparable across settings changes.
 *  - `outcome`: FIRED / ABANDONED / EXPIRED - the label.
 */
data class ArmEpisode(
    val epochMs: Long,
    val triangleId: String,
    val armedPercent: Double,
    val fireBar: Double,
    val abandonBar: Double,
    val ttlMs: Int,
    val armSpreadBps: Double,
    val armImbalance: Double,
    val armInterArrivalMs: Double,
    val armBookImbalanceSign: Int,
    val outcome: String,
    val endPercent: Double,
    val bestPercent: Double,
    val waitedMs: Long,
    val pingCount: Int,
    val waitCount: Int,
    val verdictReason: String
)

/**
 * Rolls the arming book up into one row per interval, with the engine
 * context that decides whether arming is working at all: how much edge the
 * universe is producing, whether the fire bar is reachable, and where the
 * microstructure thresholds sit relative to the market's observed
 * distributions.
 */
data class IntervalSummary(
    val epochMs: Long,
    val intervalSec: Int,
    val opportunitiesSeen: Int,
    val armsCreated: Int,
    val fired: Int,
    val abandoned: Int,
    val expired: Int,
    val medianWaitMsFired: Long,
    val medianArmedPercent: Double,
    val medianFiredPercent: Double,
    val medianBestPercent: Double,
    val p10SpreadBps: Double,
    val medianSpreadBps: Double,
    val p90SpreadBps: Double,
    val p90Imbalance: Double,
    val fireBar: Double,
    val abandonBar: Double,
    val tightSpreadBps: Double,
    val imbalanceLimit: Double,
    val cadenceLimitMs: Int,
    val ttlMs: Int,
    val medianInterArrivalMs: Double,
    val scanCycles: Long,
    /** Cycles that produced a priced opportunity. */
    val edgesSeen: Int = 0,
    /** Of those, how many had a best edge at or above the fire bar. */
    val edgesAboveFireBar: Int = 0,
    val p10Percent: Double = 0.0,
    val medianPercent: Double = 0.0,
    val p90Percent: Double = 0.0,
    val maxPercent: Double = 0.0
)

/**
 * Appends every arm episode and every interval summary to CSV files in the
 * app's external files directory, one pair per scanner session.
 *
 * Rows are queued from the scan thread and appended by a single background
 * writer that flushes per row: a process death mid-session must not lose
 * the data collected so far. Collection failure is logged, never thrown -
 * the recorder must not be able to take the trading path down with it.
 */
class ResearchRecorder(
    private val directory: File,
    val intervalSec: Int = 300
) {
    private val episodes = ConcurrentLinkedQueue<ArmEpisode>()
    private val summaries = ConcurrentLinkedQueue<IntervalSummary>()
    private val episodeCounter = AtomicLong(0)
    private val summaryCounter = AtomicLong(0)

    @Volatile private var sessionEpisodeFile: File? = null
    @Volatile private var sessionSummaryFile: File? = null
    @Volatile private var stopped = false

    private val writeThread = Thread {
        Thread.currentThread().priority = Thread.MIN_PRIORITY
        while (!stopped) {
            val e = episodes.poll()
            if (e != null) {
                appendRow(episodeFile(), e.toCsv())
                continue
            }
            val s = summaries.poll()
            if (s != null) {
                appendRow(summaryFile(), s.toCsv())
                continue
            }
            try { Thread.sleep(500) } catch (_: InterruptedException) { break }
        }
    }

    init {
        writeThread.name = "bta-research"
        writeThread.isDaemon = true
        writeThread.start()
    }

    private fun episodeFile(): File {
        sessionEpisodeFile?.let { return it }
        synchronized(this) {
            sessionEpisodeFile?.let { return it }
            val f = File(directory, "arm_episodes_" + System.currentTimeMillis() + ".csv")
            if (!f.exists()) f.writeText(ArmEpisodeCsv.header + "\n")
            sessionEpisodeFile = f
            return f
        }
    }

    private fun summaryFile(): File {
        sessionSummaryFile?.let { return it }
        synchronized(this) {
            sessionSummaryFile?.let { return it }
            val f = File(directory, "arm_summaries_" + System.currentTimeMillis() + ".csv")
            if (!f.exists()) f.writeText(IntervalSummaryCsv.header + "\n")
            sessionSummaryFile = f
            return f
        }
    }

    private fun appendRow(file: File, row: String) {
        runCatching {
            file.appendText(row + "\n")
        }.onFailure {
            val n = episodeCounter.get() + summaryCounter.get()
            if (n % 50 == 0L) LogRepository.warn("research", "CSV append failed: ${it.message}")
        }
    }

    fun recordEpisode(e: ArmEpisode) {
        if (!enabled) return
        episodes.add(e)
        episodeCounter.incrementAndGet()
    }

    fun recordSummary(s: IntervalSummary) {
        if (!enabled) return
        summaries.add(s)
        summaryCounter.incrementAndGet()
    }

    /** Collection switch - rows are dropped while off. */
    @Volatile var enabled: Boolean = true
        private set

    /** Rows written so far, for the UI. */
    val episodeCount: Long get() = episodeCounter.get()
    val summaryCount: Long get() = summaryCounter.get()

    /** The episode file written this session, when it has rows. */
    fun currentEpisodeFile(): File? = sessionEpisodeFile?.takeIf { it.length() > 0 }

    /** The summary file written this session, when it has rows. */
    fun currentSummaryFile(): File? = sessionSummaryFile?.takeIf { it.length() > 0 }

    /** Every research CSV in the directory, newest first. */
    fun allFiles(): List<File> =
        directory.listFiles { f -> f.name.startsWith("arm_") && f.name.endsWith(".csv") }
            ?.sortedByDescending { it.name } ?: emptyList()

    /** Starts a fresh file pair; previous sessions' files are kept. */
    fun newSession() {
        synchronized(this) {
            sessionEpisodeFile = null
            sessionSummaryFile = null
        }
    }

    fun stop() {
        stopped = true
        writeThread.interrupt()
    }
}

object ArmEpisodeCsv {
    val header: String = listOf(
        "epoch_ms", "triangle_id", "armed_percent", "fire_bar", "abandon_bar", "ttl_ms",
        "arm_spread_bps", "arm_imbalance", "arm_inter_arrival_ms", "arm_book_imbalance_sign",
        "outcome", "end_percent", "best_percent", "waited_ms", "ping_count", "wait_count",
        "verdict_reason"
    ).joinToString(",")

    fun ArmEpisode.toCsv(): String = listOf(
        epochMs.toString(), triangleId, pct(armedPercent), pct(fireBar), pct(abandonBar),
        ttlMs.toString(), num(armSpreadBps), num(armImbalance), num(armInterArrivalMs),
        armBookImbalanceSign.toString(), outcome, pct(endPercent), pct(bestPercent),
        waitedMs.toString(), pingCount.toString(), waitCount.toString(),
        "\"" + verdictReason.replace("\"", "'") + "\""
    ).joinToString(",")
}

object IntervalSummaryCsv {
    val header: String = listOf(
        "epoch_ms", "interval_sec", "opportunities_seen", "arms_created", "fired",
        "abandoned", "expired", "median_wait_ms_fired", "median_armed_percent",
        "median_fired_percent", "median_best_percent", "p10_spread_bps", "median_spread_bps",
        "p90_spread_bps", "p90_imbalance", "fire_bar", "abandon_bar", "tight_spread_bps",
        "imbalance_limit", "cadence_limit_ms", "ttl_ms", "median_inter_arrival_ms",
        "scan_cycles", "edges_seen", "edges_above_fire_bar", "p10_percent",
        "median_percent", "p90_percent", "max_percent"
    ).joinToString(",")

    fun IntervalSummary.toCsv(): String = listOf(
        epochMs.toString(), intervalSec.toString(), opportunitiesSeen.toString(),
        armsCreated.toString(), fired.toString(), abandoned.toString(), expired.toString(),
        medianWaitMsFired.toString(), pct(medianArmedPercent), pct(medianFiredPercent),
        pct(medianBestPercent), num(p10SpreadBps), num(medianSpreadBps), num(p90SpreadBps),
        num(p90Imbalance), pct(fireBar), pct(abandonBar), num(tightSpreadBps),
        num(imbalanceLimit), cadenceLimitMs.toString(), ttlMs.toString(),
        num(medianInterArrivalMs), scanCycles.toString(),
        edgesSeen.toString(), edgesAboveFireBar.toString(), pct(p10Percent),
        pct(medianPercent), pct(p90Percent), pct(maxPercent)
    ).joinToString(",")
}

private fun pct(v: Double): String = "%.6f".format(v)
private fun num(v: Double): String = if (v.isFinite()) "%.6f".format(v) else ""
