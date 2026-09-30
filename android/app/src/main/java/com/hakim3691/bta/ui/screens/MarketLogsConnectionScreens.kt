package com.hakim3691.bta.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.hakim3691.bta.log.LogEntry
import com.hakim3691.bta.log.LogLevel
import com.hakim3691.bta.log.LogRepository
import com.hakim3691.bta.ui.theme.BtaYellow
import java.io.File
import com.hakim3691.bta.market.WsStatus
import com.hakim3691.bta.scanner.ScannerState
import com.hakim3691.bta.ui.ArbViewModel
import com.hakim3691.bta.ui.theme.LossRed
import com.hakim3691.bta.ui.theme.ProfitGreen
import com.hakim3691.bta.ui.theme.TextSecondary

@Composable
fun MarketScreen(viewModel: ArbViewModel, navController: NavController) {
    val mode by viewModel.mode.collectAsState()
    val connection by viewModel.connection.collectAsState()
    val opportunities by viewModel.opportunities.collectAsState()

    Column(Modifier.fillMaxSize()) {
        ModeBanner(mode)
        Text(
            "Market / Order Book",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
        ) {
            SectionTitle("Stream Health")
            CardPanel {
                KeyValueRow("WebSocket", connection.wsStatus,
                    valueColor = if (connection.wsStatus == "CONNECTED") ProfitGreen else TextSecondary)
                KeyValueRow("Depth updates received", "${viewModel.wsMessages.collectAsState().value}")
                KeyValueRow("Tickers synced", "${connection.syncedTickers}/${connection.totalTickers}")
                KeyValueRow("REST", if (connection.restOk) "operational" else "unavailable")
                KeyValueRow("Latency", "${connection.latencyMs} ms")
                KeyValueRow("Age gate", viewModel.ageThresholdInfo.collectAsState().value)
            }
            Spacer(Modifier.height(12.dp))
            SectionTitle("Paper Portfolio")
            val balances = viewModel.paperBalances.collectAsState().value
            if (balances.isEmpty()) {
                Text("Start the scanner to seed the paper portfolio.", color = TextSecondary)
            } else {
                CardPanel {
                    balances.entries.sortedByDescending { it.value }.take(8).forEach { (asset, amt) ->
                        KeyValueRow(asset, formatQty(amt))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle("Depth Consumed by Latest Opportunities")
            if (opportunities.isEmpty()) {
                Text("Waiting for scan results ...", color = TextSecondary)
            } else {
                opportunities.take(10).forEach { opp ->
                    CardPanel {
                        Text(opp.id, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        KeyValueRow("AB ${opp.abTicker} ${opp.abMethod}", "depth ${opp.abDepth}, age ${opp.abAgeMs} ms")
                        KeyValueRow("BC ${opp.bcTicker} ${opp.bcMethod}", "depth ${opp.bcDepth}, age ${opp.bcAgeMs} ms")
                        KeyValueRow("CA ${opp.caTicker} ${opp.caMethod}", "depth ${opp.caDepth}, age ${opp.caAgeMs} ms")
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun CardPanel(content: @Composable () -> Unit) {
    androidx.compose.material3.Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, com.hakim3691.bta.ui.theme.CardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) { content() }
    }
}

@Composable
fun LogsScreen(viewModel: ArbViewModel, navController: NavController) {
    val mode by viewModel.mode.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Column(Modifier.fillMaxSize()) {
        ModeBanner(mode)
        Text(
            "Logs",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )

        // The controls sit above the log list and are always rendered, so the
        // export is reachable even before a single row has been collected.
        Row(
            Modifier.padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { LogRepository.clear() }) { Text("Clear logs") }
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(buildLogExport(logs))) }) {
                Text("COPY LOG")
            }
            OutlinedButton(onClick = { saveLogText(context, logs) }) { Text("SAVE LOG") }
        }

        ResearchPanel(viewModel)
        Spacer(Modifier.height(8.dp))

        LazyColumnLogs(logs.reversed(), Modifier.weight(1f))
    }
}

/**
 * Stage 3 collector. Every arm episode and every five-minute summary the
 * scanner produced is already on disk; this panel is only the way out of the
 * app, so it is deliberately always visible rather than hidden behind a
 * "you have data" condition.
 */
@Composable
private fun ResearchPanel(viewModel: ArbViewModel) {
    val context = LocalContext.current
    val files = viewModel.researchFiles()
    val episodeRows = viewModel.researchRowCount().first
    val summaryRows = viewModel.researchRowCount().second
    val researchInfo by viewModel.researchInfo.collectAsState()
    val isRunning = viewModel.scannerState.value == ScannerState.RUNNING

    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(
            "STAGE 3 RESEARCH",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = BtaYellow
        )
        Spacer(Modifier.height(6.dp))
        CardPanel {
            KeyValueRow("Arm episodes", "$episodeRows rows")
            KeyValueRow("Interval summaries", "$summaryRows rows")
            KeyValueRow("Recorder", researchInfo)
            if (files.isEmpty()) {
                Text(
                    // Previously this read as a fixed reassurance that recording
                    // "starts when the scanner starts", which is exactly what was
                    // untrue: the recorder was never attached, and a hardcoded
                    // reassurance hid that from three separate device runs. Now
                    // the live recorder state is shown beside the row counts.
                    if (isRunning) {
                        "Recording. Arm episodes appear when an opportunity is armed; " +
                            "interval summaries are written every 5 minutes."
                    } else {
                        "No rows yet - start the scanner to begin collecting."
                    },
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            } else {
                files.forEach { f -> KeyValueRow(f.name, formatBytes(f.length())) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = files.isNotEmpty(),
                    onClick = { shareResearchFiles(context, files) }
                ) { Text("SHARE CSV") }
                OutlinedButton(onClick = { viewModel.startNewResearchSession() }) {
                    Text("NEW SESSION")
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "arm_episodes_* answers whether waiting pays. arm_summaries_* shows the " +
                    "spread and imbalance the market actually produced, next to the gates " +
                    "in force at the time.",
                color = TextSecondary,
                fontSize = 10.sp
            )
        }
    }
}

/** Hands the CSVs to the system share sheet through the FileProvider. */
private fun shareResearchFiles(context: Context, files: List<File>) {
    // Also copy into public Downloads. The share sheet needs the user to pick a
    // destination, but the CSVs are the whole point of the Stage 3 study and
    // they live where no file manager can reach them, so a silent copy makes
    // them collectable without depending on which app the share lands in.
    var published = 0
    for (f in files) {
        try {
            if (publishToDownloads(context, f.name, f.readBytes()) != null) published++
        } catch (e: Exception) {
            LogRepository.warn("research", "Could not copy " + f.name + " to Downloads: " + e.message)
        }
    }
    if (published > 0) {
        LogRepository.info(
            "research",
            "Copied $published research CSV(s) to the Downloads folder as well"
        )
    }

    val uris = ArrayList<android.net.Uri>()
    for (f in files) {
        try {
            uris.add(FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f))
        } catch (e: Exception) {
            LogRepository.warn("research", "Could not share " + f.name + ": " + e.message)
        }
    }
    if (uris.isEmpty()) return
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uris[0])
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/csv"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(
        Intent.createChooser(intent, "Share Stage 3 research CSVs")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

/**
 * Writes the visible log buffer to app storage so it can be pulled off the device.
 *
 * `getExternalFilesDir(null)` returns null when no external volume is mounted, and
 * `File(null, "exports")` silently collapses to the bare relative name "exports",
 * so the write would land in the process working directory and the reported path
 * would look plausible while the file was nowhere near it. That is exactly the
 * failure mode seen on a device where the folder appeared empty. The directory is
 * therefore resolved to an absolute path and its existence verified before the
 * write, and the internal cache is used as a fallback so a log is never lost.
 */
private fun saveLogText(context: Context, entries: List<LogEntry>): File? {
    val name = "bta_log_" + System.currentTimeMillis() + ".txt"
    val body = buildLogExport(entries).toByteArray()

    // Public Downloads first: it is the only location a file manager can actually
    // open, since Android 11 blocks browsing Android/data.
    val public = publishToDownloads(context, name, body)
    if (public != null) {
        LogRepository.info(
            "logs",
            "Saved " + entries.size + " lines to " + public + " (full path: " +
                "Downloads/" + name + ")"
        )
        // Still keep an app-private copy so the file survives a reboot and remains
        // reachable through the FileProvider.
        return writePrivateCopy(context, name, body)
    }

    val privateCopy = writePrivateCopy(context, name, body)
    reportExportOutcome("log (" + entries.size + " lines)", privateCopy, null)
    return privateCopy
}

/** Best-effort app-private copy, used when Downloads is unavailable. */
private fun writePrivateCopy(context: Context, name: String, body: ByteArray): File? {
    val external = context.getExternalFilesDir(null)
    val candidates = listOfNotNull(
        external?.let { File(it, "exports") },
        File(context.filesDir, "exports")
    )
    for (dir in candidates) {
        try {
            if (!dir.isDirectory && !dir.mkdirs()) continue
            val file = File(dir, name)
            file.writeBytes(body)
            return file
        } catch (e: Exception) {
            LogRepository.error("logs", "Could not write to " + dir.absolutePath + ": " + e.message)
        }
    }
    return null
}

/** Plain-text rendering of the log buffer, oldest first. */
private fun buildLogExport(entries: List<LogEntry>): String {
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
    val sb = StringBuilder()
    sb.append("# bta log export - ").append(entries.size).append(" lines\n")
    for (e in entries) {
        sb.append(fmt.format(java.util.Date(e.timestamp)))
            .append(" [").append(e.level.name).append('/').append(e.channel).append("] ")
            .append(e.message).append('\n')
    }
    return sb.toString()
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1f kB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun LazyColumnLogs(
    entries: List<LogEntry>,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.lazy.LazyColumn(
        modifier.padding(horizontal = 14.dp)
    ) {
        items(entries) { entry ->
            val color = when (entry.level) {
                LogLevel.ERROR -> LossRed
                LogLevel.WARN -> BtaYellow
                else -> TextSecondary
            }
            Text(
                text = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(entry.timestamp))} " +
                    "[${entry.channel}] ${entry.message}",
                color = color,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.padding(vertical = 1.dp)
            )
        }
    }
}

@Composable
fun ConnectionScreen(viewModel: ArbViewModel, navController: NavController) {
    val connection by viewModel.connection.collectAsState()
    val state by viewModel.scannerState.collectAsState()
    val mode by viewModel.mode.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
    ) {
        ModeBanner(mode)
        Spacer(Modifier.height(10.dp))
        Text("Binance Connection", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        SectionTitle("REST API")
        CardPanel {
            KeyValueRow("Endpoint", "https://api.binance.com")
            KeyValueRow("Status", if (connection.restOk) "reachable" else "unreachable",
                valueColor = if (connection.restOk) ProfitGreen else LossRed)
            KeyValueRow("Measured latency", "${connection.latencyMs} ms")
        }
        Spacer(Modifier.height(10.dp))
        SectionTitle("WebSocket Market Streams")
        CardPanel {
            KeyValueRow("Endpoint", "wss://stream.binance.com:9443/stream")
            KeyValueRow("Status", connection.wsStatus,
                valueColor = if (connection.wsStatus == "CONNECTED") ProfitGreen else TextSecondary)
            KeyValueRow("Synced tickers", "${connection.syncedTickers}/${connection.totalTickers}")
            KeyValueRow("Reconnect policy", "exponential backoff, 1s..32s cap 30s")
            KeyValueRow("Stale watchdog", "forced reconnect after 15s of silence")
        }
        Spacer(Modifier.height(10.dp))
        SectionTitle("Build")
        CardPanel {
            // Seventeen installs all read "1.0.0" in the launcher; this is the
            // unambiguous answer to which one is actually running.
            KeyValueRow(
                "Version",
                com.hakim3691.bta.BuildConfig.VERSION_NAME + " (code " +
                    com.hakim3691.bta.BuildConfig.VERSION_CODE + ")"
            )
            KeyValueRow("Package", com.hakim3691.bta.BuildConfig.APPLICATION_ID)
            KeyValueRow("Flavor", com.hakim3691.bta.BuildConfig.BUILD_TYPE)
            // Kept out of versionName on purpose: the version is a committed
            // value so every checkout builds identically, and the commit is
            // reported here as provenance without destabilising that.
            KeyValueRow("Commit", com.hakim3691.bta.BuildConfig.BUILD_COMMIT)
        }
        Spacer(Modifier.height(10.dp))
        SectionTitle("Scanner")
        CardPanel {
            KeyValueRow("State", state.name)
            if (connection.lastError != null) {
                KeyValueRow("Last error", connection.lastError ?: "", valueColor = LossRed)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
