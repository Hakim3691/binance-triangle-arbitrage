package com.hakim3691.bta.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.hakim3691.bta.core.ArbitrageExecution
import com.hakim3691.bta.core.ExecutionState
import com.hakim3691.bta.scanner.TradingMode
import com.hakim3691.bta.ui.ArbViewModel
import com.hakim3691.bta.ui.theme.CardBorder
import com.hakim3691.bta.ui.theme.LossRed
import com.hakim3691.bta.ui.theme.ProfitGreen
import com.hakim3691.bta.ui.theme.TextSecondary

@Composable
fun ExecutionScreen(viewModel: ArbViewModel, navController: NavController) {
    val mode by viewModel.mode.collectAsState()
    val executions by viewModel.executions.collectAsState()

    Column(Modifier.fillMaxSize()) {
        ModeBanner(mode)
        Text(
            "Execution",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
        if (executions.isEmpty()) {
            Text(
                "No executions yet. Executions appear here with their three-leg state.",
                color = TextSecondary,
                modifier = Modifier.padding(14.dp)
            )
        } else {
            LazyColumn(Modifier.padding(horizontal = 14.dp)) {
                items(executions) { ex ->
                    ExecutionCard(ex)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
fun ExecutionCard(ex: ExecutionState) {
    val statusColor = when (ex.status) {
        ExecutionState.Status.COMPLETED -> ProfitGreen
        ExecutionState.Status.FAILED -> LossRed
        ExecutionState.Status.PARTIAL -> com.hakim3691.bta.ui.theme.BtaYellow
        ExecutionState.Status.ABORTED -> LossRed
        else -> TextSecondary
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
            ) {
                Text(ex.id, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(ex.status.name, color = statusColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
            KeyValueRow("Strategy", ex.strategy)
            KeyValueRow("Leg AB", if (ex.abComplete) "filled" else "not filled")
            KeyValueRow("Leg BC", if (ex.bcComplete) "filled" else "not filled")
            KeyValueRow("Leg CA", if (ex.caComplete) "filled" else "not filled")
            if (ex.requiresManualClose) {
                Spacer(Modifier.height(8.dp))
                AlertBanner(
                    text = "CLOSE MANUALLY ON BINANCE - this position could not be unwound" +
                        (if (ex.strandedAssets.isNotEmpty())
                            " (still holding " + ex.strandedAssets.joinToString(", ") + ")" else ""),
                    tint = LossRed
                )
            }
            if (ex.aborted) {
                KeyValueRow("Aborted", ex.abortReason ?: "", valueColor = LossRed)
                ex.projectedPercent?.let {
                    KeyValueRow("Projected at abort", "%.4f%%".format(it), valueColor = LossRed)
                }
                KeyValueRow(
                    "Unwound", if (ex.unwound) "yes, filled legs reversed" else "no, position may be stranded",
                    valueColor = if (ex.unwound) ProfitGreen else LossRed
                )
            }
            val actual = ex.actual
            if (actual != null) {
                KeyValueRow(
                    "Delta A", formatQty(actual.a.delta),
                    valueColor = profitColor(actual.a.delta)
                )
                KeyValueRow(
                    "Delta B", formatQty(actual.b.delta),
                    valueColor = profitColor(actual.b.delta)
                )
                KeyValueRow(
                    "Delta C", formatQty(actual.c.delta),
                    valueColor = profitColor(actual.c.delta)
                )
                KeyValueRow("BNB fees", formatQty(actual.fees))
            }
            if (ex.error != null) {
                Text("Error: ${ex.error}", color = LossRed, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun TradeHistoryScreen(viewModel: ArbViewModel, navController: NavController) {
    val mode by viewModel.mode.collectAsState()
    val history by viewModel.tradeHistory.collectAsState()

    Column(Modifier.fillMaxSize()) {
        ModeBanner(mode)
        Text(
            "Trade History",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
        if (history.isEmpty()) {
            Text(
                "No completed trades yet.",
                color = TextSecondary,
                modifier = Modifier.padding(14.dp)
            )
        } else {
            LazyColumn(Modifier.padding(horizontal = 14.dp)) {
                items(history) { trade ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            androidx.compose.foundation.layout.Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                            ) {
                                Text(trade.id, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(
                                    formatPercent(trade.percent),
                                    color = profitColor(trade.percent),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            KeyValueRow("Executed at", java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                                .format(java.util.Date(trade.timestamp)))
                            KeyValueRow("Spend ${trade.symbolA}", formatQty(trade.spentA))
                            KeyValueRow("Final ${trade.symbolA}", formatQty(trade.earnedA))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}
