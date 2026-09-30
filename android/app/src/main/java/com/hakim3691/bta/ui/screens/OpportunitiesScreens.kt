package com.hakim3691.bta.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.hakim3691.bta.scanner.OpportunityUi
import com.hakim3691.bta.ui.ArbViewModel
import com.hakim3691.bta.ui.theme.CardBorder
import com.hakim3691.bta.ui.theme.LossRed
import com.hakim3691.bta.ui.theme.ProfitGreen
import com.hakim3691.bta.ui.theme.TextSecondary

@Composable
fun OpportunitiesScreen(viewModel: ArbViewModel, navController: NavController) {
    val mode by viewModel.mode.collectAsState()
    val all by viewModel.opportunities.collectAsState()
    val performance by viewModel.performance.collectAsState()
    val profitable = all.filter { it.percent > 0 }

    Column(Modifier.fillMaxSize()) {
        ModeBanner(mode)
        Text(
            "Arbitrage Opportunities",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
        Text(
            // `all` is the result of the most recent depth event, which only
            // re-prices the triangles containing the ticker that moved - so it is
            // a handful, not the universe. The scanned-universe count lives on
            // the dashboard; repeating the event's size here read as "only 2
            // triangles are being scanned", which is not what it means.
            "Showing profitable opportunities only. The full universe of " +
                "${performance.trianglesTotal} triangles is scanned " +
                "continuously; ${all.size} were re-priced on the latest market event " +
                "(the rest were priced on earlier ones).",
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 14.dp)
        )
        if (profitable.isEmpty()) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "No profitable opportunities right now.",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "This is normal: triangle arbitrage edges are usually smaller than the ~0.3% " +
                        "(3 x taker fee) cost. The scanner evaluates every triangle continuously and will " +
                        "auto-execute here the moment one clears the Kelly gate.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(Modifier.padding(horizontal = 14.dp)) {
                items(profitable, key = { it.id }) { opp ->
                    OpportunityCard(opp, onClick = {
                        navController.navigate("opportunity/${opp.id}")
                    })
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
fun OpportunityDetailScreen(viewModel: ArbViewModel, navController: NavController, opportunityId: String) {
    val mode by viewModel.mode.collectAsState()
    val opportunities by viewModel.opportunities.collectAsState()
    val opp = opportunities.firstOrNull { it.id == opportunityId }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
    ) {
        ModeBanner(mode)
        Spacer(Modifier.height(10.dp))
        if (opp == null) {
            Text("Opportunity $opportunityId is no longer active", color = TextSecondary)
            return@Column
        }

        Text(opp.id, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = com.hakim3691.bta.ui.theme.BtaYellow)
        Text(
            text = formatPercent(opp.percent),
            color = profitColor(opp.percent),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))

        SectionTitle("Cycle")
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
        ) {
            Column(Modifier.padding(12.dp)) {
                KeyValueRow("Path", "${opp.symbolA} → ${opp.symbolB} → ${opp.symbolC} → ${opp.symbolA}")
                KeyValueRow("Leg AB", "${opp.abTicker} ${opp.abMethod}")
                KeyValueRow("Leg BC", "${opp.bcTicker} ${opp.bcMethod}")
                KeyValueRow("Leg CA", "${opp.caTicker} ${opp.caMethod}")
            }
        }

        Spacer(Modifier.height(10.dp))
        SectionTitle("Order Sizes")
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
        ) {
            Column(Modifier.padding(12.dp)) {
                KeyValueRow("Investment (${opp.symbolA})", formatQty(opp.investmentA))
                KeyValueRow("Leg AB quantity", formatQty(opp.orderSizeAb))
                KeyValueRow("Leg BC quantity", formatQty(opp.orderSizeBc))
                KeyValueRow("Leg CA quantity", formatQty(opp.orderSizeCa))
            }
        }

        Spacer(Modifier.height(10.dp))
        SectionTitle("Expected Flow")
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
        ) {
            Column(Modifier.padding(12.dp)) {
                KeyValueRow("Spend ${opp.symbolA}", formatQty(opp.spentA))
                KeyValueRow("Earn ${opp.symbolB}", formatQty(opp.earnedB))
                KeyValueRow("Earn ${opp.symbolC}", formatQty(opp.earnedC))
                KeyValueRow("Final ${opp.symbolA}", formatQty(opp.earnedA))
            }
        }

        Spacer(Modifier.height(10.dp))
        SectionTitle("Depth & Freshness")
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
        ) {
            Column(Modifier.padding(12.dp)) {
                KeyValueRow("AB depth used", "${opp.abDepth} levels")
                KeyValueRow("BC depth used", "${opp.bcDepth} levels")
                KeyValueRow("CA depth used", "${opp.caDepth} levels")
                KeyValueRow("AB age", "${opp.abAgeMs} ms")
                KeyValueRow("BC age", "${opp.bcAgeMs} ms")
                KeyValueRow("CA age", "${opp.caAgeMs} ms")
                KeyValueRow("Stalest age", "${opp.ageMs} ms")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
