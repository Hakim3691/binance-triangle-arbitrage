package com.hakim3691.bta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hakim3691.bta.scanner.TradingMode
import com.hakim3691.bta.ui.theme.CardBorder
import com.hakim3691.bta.ui.theme.LossRed
import com.hakim3691.bta.ui.theme.ProfitGreen
import com.hakim3691.bta.ui.theme.TextSecondary

/** Top bar with mode indicator shown on every screen. */
@Composable
fun ModeBanner(mode: TradingMode, modifier: Modifier = Modifier) {
    val isLive = mode == TradingMode.LIVE
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isLive) LossRed else ProfitGreen.copy(alpha = 0.15f))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = if (isLive) "● LIVE TRADING — REAL ORDERS" else "● PAPER TRADING — SIMULATION",
            color = if (isLive) Color.White else ProfitGreen,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 0.8.sp
        )
    }
}

/**
 * Full-width alert for states the operator must act on: a dead feed, or a
 * position the app could not put back by itself.
 */
@Composable
fun AlertBanner(
    text: String,
    tint: Color,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.12f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, tint),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) {
                OutlinedButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                ) {
                    Text(actionLabel, fontSize = 11.sp, color = tint)
                }
            }
        }
    }
}

@Composable
fun StatCard(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onBackground,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = valueColor
            )
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = TextSecondary,
        letterSpacing = 1.2.sp,
        modifier = modifier.padding(vertical = 6.dp)
    )
}

@Composable
fun KeyValueRow(key: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(key, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            color = valueColor,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace
        )
    }
}

/** Formats a signed percent with a fixed color convention. */
fun profitColor(percent: Double): Color = if (percent >= 0) ProfitGreen else LossRed

fun formatPercent(p: Double): String = "%+.4f".format(p) + "%"

fun formatQty(q: Double): String {
    return when {
        q == 0.0 -> "0"
        kotlin.math.abs(q) >= 1000 -> "%.2f".format(q)
        kotlin.math.abs(q) >= 1 -> "%.6f".format(q)
        else -> "%.8f".format(q)
    }
}
