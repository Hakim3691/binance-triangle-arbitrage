package com.hakim3691.bta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.hakim3691.bta.ui.theme.BtaYellow
import com.hakim3691.bta.ui.theme.CardBorder
import com.hakim3691.bta.ui.theme.TextSecondary

/**
 * Fixed bottom navigation shown on every screen - tabs are always reachable,
 * no dead ends.
 */
@Composable
fun BottomNavBar(navController: NavController, currentRoute: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        TabItem(navController, currentRoute, "dashboard", "Home")
        TabItem(navController, currentRoute, "opportunities", "Opps")
        TabItem(navController, currentRoute, "paper_live", "Mode")
        TabItem(navController, currentRoute, "execution", "Trades")
        TabItem(navController, currentRoute, "logs", "Logs")
        TabItem(navController, currentRoute, "settings", "Settings")
    }
}

@Composable
private fun TabItem(
    navController: NavController,
    currentRoute: String?,
    route: String,
    label: String
) {
    val selected = currentRoute == route ||
        (route == "opportunities" && currentRoute?.startsWith("opportunity/") == true)
    Box(
        modifier = Modifier
            .clickable {
                navController.navigate(route) {
                    popUpTo("dashboard") { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (selected) "[$label]" else label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            color = if (selected) BtaYellow else TextSecondary
        )
    }
}
