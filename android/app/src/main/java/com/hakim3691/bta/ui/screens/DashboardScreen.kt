package com.hakim3691.bta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.hakim3691.bta.scanner.ScannerState
import com.hakim3691.bta.scanner.TradingMode
import com.hakim3691.bta.ui.ArbViewModel
import com.hakim3691.bta.ui.theme.BtaYellow
import com.hakim3691.bta.ui.theme.CardBorder
import com.hakim3691.bta.ui.theme.LossRed
import com.hakim3691.bta.ui.theme.ProfitGreen
import com.hakim3691.bta.ui.theme.TextSecondary

@Composable
fun DashboardScreen(viewModel: ArbViewModel, navController: NavController) {
    val mode by viewModel.mode.collectAsState()
    val state by viewModel.scannerState.collectAsState()
    val connection by viewModel.connection.collectAsState()
    val opportunities by viewModel.opportunities.collectAsState()
    val current by viewModel.currentOpportunity.collectAsState()
    val performance by viewModel.performance.collectAsState()
    val kellyStats by viewModel.kellyStats.collectAsState()
    val lastExecuted by viewModel.lastExecutedOpportunity.collectAsState()
    val gateStatus by viewModel.gateStatus.collectAsState()
    val ageThresholdInfo by viewModel.ageThresholdInfo.collectAsState()

    val profitable = opportunities.count { it.percent > 0 }
    val isRunning = state == ScannerState.RUNNING || state == ScannerState.WAITING_FOR_DEPTH
    val feedHealth = viewModel.feedHealth.collectAsState().value
    val scannedCount = performance.scannedCount

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
    ) {
        ModeBanner(mode)
        val manualClose = viewModel.manualCloseRequired.collectAsState().value
        if (manualClose.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            AlertBanner(
                text = "MANUAL ACTION REQUIRED - close on Binance: " + manualClose.take(3).joinToString("; ") +
                    (if (manualClose.size > 3) " (+${manualClose.size - 3} more)" else ""),
                tint = LossRed,
                actionLabel = "DISMISS",
                onAction = { viewModel.clearManualCloseRequired() }
            )
        }
        if (!feedHealth.healthy && feedHealth.total > 0) {
            Spacer(Modifier.height(8.dp))
            AlertBanner(text = feedHealth.message(), tint = LossRed)
        }
        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Triangle Arbitrage", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = BtaYellow)
            Text(
                text = state.name,
                color = when (state) {
                    ScannerState.RUNNING, ScannerState.WAITING_FOR_DEPTH -> ProfitGreen
                    ScannerState.ERROR -> LossRed
                    ScannerState.PAUSED_CAP_REACHED -> BtaYellow
                    else -> TextSecondary
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.startScanner() },
                enabled = !isRunning,
                colors = ButtonDefaults.buttonColors(containerColor = ProfitGreen, contentColor = Color.Black),
                modifier = Modifier.weight(1f)
            ) { Text(if (isRunning) "RUNNING" else "START") }
            OutlinedButton(
                onClick = { viewModel.stopScanner() },
                enabled = isRunning,
                modifier = Modifier.weight(1f)
            ) { Text("STOP") }
        }
        if (state == ScannerState.PAUSED_CAP_REACHED) {
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = { viewModel.resetCap() },
                colors = ButtonDefaults.buttonColors(containerColor = BtaYellow, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth()
            ) { Text("RESET EXECUTION CAP") }
        }

        // Navigation lives in the bottom bar and its Menu tab; these chips used to
        // sit here too, duplicating it and pushing the dashboard panels off-screen.

        Spacer(Modifier.height(12.dp))
        SectionTitle("Connection")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("WEBSOCKET", connection.wsStatus, modifier = Modifier.weight(1f),
                valueColor = if (connection.wsStatus == "CONNECTED") ProfitGreen else TextSecondary)
            StatCard("REST", if (connection.restOk) "OK" else "DOWN", modifier = Modifier.weight(1f),
                valueColor = if (connection.restOk) ProfitGreen else LossRed)
            StatCard("LATENCY", "${connection.latencyMs} ms", modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard(
                "FRESH",
                "${feedHealth.fresh}/${feedHealth.total}",
                modifier = Modifier.weight(1f),
                valueColor = if (feedHealth.healthy) ProfitGreen else LossRed
            )
            StatCard("CYCLES/S", "%.1f".format(performance.cyclesPerSecond), modifier = Modifier.weight(1f))
            StatCard("CYCLE", "${performance.lastCycleMs} ms", modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("LOOPS", "${performance.loopCount}", modifier = Modifier.weight(1f),
                valueColor = if (performance.loopCount > 0) ProfitGreen else TextSecondary)
            StatCard("LOOP PROG", "${(performance.loopProgress * 100).toInt()}%", modifier = Modifier.weight(1f))
            StatCard("LAST LOOP", "${performance.lastLoopMs} ms", modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("COMBINATIONS", "${performance.trianglesTotal}", modifier = Modifier.weight(1f))
            StatCard("EVALUATED", "${performance.trianglesEvaluated}", modifier = Modifier.weight(1f))
            StatCard("LOOPS/MIN", "%.1f".format(performance.loopsPerMinute), modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))
        SectionTitle("Market Scan (last cycle)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("SCANNED", "$scannedCount", modifier = Modifier.weight(1f))
            StatCard("PROFITABLE", if (isRunning) "$profitable" else "0", modifier = Modifier.weight(1f),
                valueColor = if (profitable > 0 && isRunning) ProfitGreen else TextSecondary)
            StatCard("EXECUTIONS", "${viewModel.executions.collectAsState().value.size}", modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))
        SectionTitle("Staged execution (arm - wait - fire)")
        ArmingPanel(viewModel)

        Spacer(Modifier.height(10.dp))
        MeasuredEdgeDisclaimer()

        if (kellyStats != null) {
            Spacer(Modifier.height(12.dp))
            SectionTitle("Paper Auto-Trading (Kelly) - investing ${(viewModel.config.collectAsState().value.kellyInvestmentFraction * 100).toInt()}% of Kelly")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(
                    "EQUITY",
                    "%.2f".format(kellyStats!!.equityUsdt) + " USDT",
                    modifier = Modifier.weight(1f),
                    valueColor = if (kellyStats!!.totalPnlUsdt >= 0) ProfitGreen else LossRed
                )
                StatCard("TRADES", "${kellyStats!!.trades}", modifier = Modifier.weight(1f))
                StatCard(
                    "WIN RATE",
                    "${(kellyStats!!.winRate * 100).toInt()}%",
                    modifier = Modifier.weight(1f),
                    valueColor = if (kellyStats!!.winRate >= 0.5) ProfitGreen else TextSecondary
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(
                    "P&L",
                    "%+.4f".format(kellyStats!!.totalPnlUsdt) + " USDT",
                    modifier = Modifier.weight(1f),
                    valueColor = if (kellyStats!!.totalPnlUsdt >= 0) ProfitGreen else LossRed
                )
                StatCard("SIGMA", "%.4f%%".format(kellyStats!!.sigmaPercent), modifier = Modifier.weight(1f))
                StatCard(
                    "LAST P(WIN)",
                    kellyStats!!.lastPWin?.let { "${(it * 100).toInt()}%" } ?: "-",
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Age gate " + ageThresholdInfo + " | " + gateStatus,
                color = TextSecondary,
                fontSize = 12.sp
            )
        }

        Spacer(Modifier.height(12.dp))
        SectionTitle("Current Best Opportunity")
        if (current != null) {
            OpportunityCard(current!!, onClick = { navController.navigate("opportunity/${current!!.id}") })
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (isRunning) "Scanning for opportunities ..." else "Scanner not running",
                        color = TextSecondary
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionTitle("Top Opportunities")
        if (opportunities.isEmpty()) {
            Text("No opportunities yet", color = TextSecondary, fontSize = 13.sp)
        } else {
            opportunities.take(5).forEach { opp ->
                OpportunityCard(opp, onClick = { navController.navigate("opportunity/${opp.id}") })
                Spacer(Modifier.height(6.dp))
            }
        }

        if (lastExecuted != null) {
            Spacer(Modifier.height(12.dp))
            SectionTitle("Last Auto-Executed (Kelly)")
            OpportunityCard(lastExecuted!!, onClick = { navController.navigate("opportunity/${lastExecuted!!.id}") })
        }

        if (connection.lastError != null) {
            Spacer(Modifier.height(10.dp))
            Text("Last error: ${connection.lastError}", color = LossRed, fontSize = 12.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun OpportunityCard(opp: com.hakim3691.bta.scanner.OpportunityUi, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${opp.symbolA} → ${opp.symbolB} → ${opp.symbolC} → ${opp.symbolA}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    formatPercent(opp.percent),
                    color = profitColor(opp.percent),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${opp.abTicker}(${opp.abMethod}) · ${opp.bcTicker}(${opp.bcMethod}) · ${opp.caTicker}(${opp.caMethod})",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
                // Past the execution deadline a triangle cannot be fired at
                // all, however permissive the (auto-tuned) age gate is, so the
                // deadline is the honest line for "will not be traded". Using
                // the age gate alone let a 9.7s-old row read as fresh.
                val cfg = com.hakim3691.bta.core.ExecutionConfig
                val staleAfter = if (cfg.executionDeadlineMs > 0) {
                    minOf(cfg.ageThresholdMs, cfg.executionDeadlineMs)
                } else {
                    cfg.ageThresholdMs
                }
                val stale = opp.ageMs > staleAfter
                Text(
                    "age ${opp.ageMs} ms" + if (stale) "  - STALE, will not be traded" else "",
                    color = if (stale) LossRed else TextSecondary,
                    fontSize = 11.sp
                )
            }
        }
    }
}


/**
 * Telemetry for the staged executor.
 *
 * The two numbers that matter are how long an opportunity survives after it is
 * recognised, and why the ones that never fired were dropped - together they
 * say whether waiting for a better moment is worth anything at all on this
 * market. Every drop is listed with its reason, because an unexplained silence
 * is indistinguishable from a bug.
 */
@Composable
private fun ArmingPanel(viewModel: ArbViewModel) {
    val arming by viewModel.armingStats.collectAsState()
    val armLog by viewModel.armLog.collectAsState()

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (arming.enabled) BtaYellow else CardBorder)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(arming.message(), color = TextSecondary, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("ARMED", "${arming.armed}/${arming.slots}", modifier = Modifier.weight(1f),
                    valueColor = if (arming.armed > 0) BtaYellow else TextSecondary)
                StatCard("FIRED", "${arming.fired}", modifier = Modifier.weight(1f),
                    valueColor = if (arming.fired > 0) ProfitGreen else TextSecondary)
                StatCard("DROPPED", "${arming.abandoned + arming.expired}", modifier = Modifier.weight(1f),
                    valueColor = if (arming.abandoned + arming.expired > 0) LossRed else TextSecondary)
            }
            Spacer(Modifier.height(6.dp))
            KeyValueRow("Fire at", ">= %.4f%%".format(arming.fireBarPercent), valueColor = ProfitGreen)
            KeyValueRow("Drop below", "%.4f%%".format(arming.abandonBarPercent), valueColor = LossRed)
            KeyValueRow("Window", arming.ttlMs.toString() + " ms from detection")
            KeyValueRow("Median wait to fire", if (arming.fired > 0)
                arming.medianTimeToFireMs.toString() + " ms over " + arming.medianWaitsBeforeFire + " waits" else "-")
            KeyValueRow("Median time to drop", if (arming.abandoned + arming.expired > 0)
                arming.medianTimeToAbandonMs.toString() + " ms" else "-")
            KeyValueRow("Median fired percent", if (arming.fired > 0)
                "%.4f%%".format(arming.medianFiredPercent) else "-")
            arming.lastEvent?.let {
                Spacer(Modifier.height(4.dp))
                Text("last: " + it, color = BtaYellow, fontSize = 11.sp)
            }
            if (armLog.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("Recent outcomes", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                armLog.take(5).forEach { record ->
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = record.outcome.name + " " + record.id + " (" + record.durationMs + "ms, " +
                            record.pings + " pings)",
                        color = when (record.outcome) {
                            com.hakim3691.bta.core.ArmOutcome.FIRED -> ProfitGreen
                            com.hakim3691.bta.core.ArmOutcome.WAITING -> BtaYellow
                            else -> LossRed
                        },
                        fontSize = 10.sp
                    )
                    Text("  " + record.reason, color = TextSecondary, fontSize = 10.sp)
                }
            }
        }
    }
}

/**
 * The honest context, permanently on the dashboard.
 *
 * The engine measures dislocations on a 100ms public depth feed and acts on
 * them through REST with a round trip measured in tens to hundreds of
 * milliseconds. The participants that consume most dislocations before the
 * books move on sit at the exchange with websocket order entry. Paper profits
 * under simulated friction are a property of the simulation's assumptions,
 * not a forecast of live P&L - this app's defensible purpose is measurement
 * and research, and the UI should say so where the numbers are shown.
 */
@Composable
private fun MeasuredEdgeDisclaimer() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Measured edge vs. retail latency", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Dislocations on a 100ms public feed are usually consumed by faster " +
                    "participants within tens of milliseconds. This app measures whether " +
                    "an edge survives YOUR round trip on YOUR connection - paper profits " +
                    "reflect the simulation's assumptions, not a live forecast. Treat it " +
                    "as a research instrument; live mode can lose money to fees alone.",
                color = TextSecondary, fontSize = 11.sp
            )
        }
    }
}
