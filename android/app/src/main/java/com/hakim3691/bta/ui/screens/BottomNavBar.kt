package com.hakim3691.bta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.hakim3691.bta.ui.theme.BtaYellow
import com.hakim3691.bta.ui.theme.TextSecondary

/**
 * The screens that do not earn a permanent slot in the bottom bar, gathered under
 * one "Menu" tab instead. The dashboard used to spray these across two rows of
 * chips, which pushed the actual dashboard content off-screen and duplicated
 * navigation that the bar already provided.
 */
private data class MenuEntry(val route: String, val label: String)

private val MENU_ENTRIES = listOf(
    MenuEntry("market", "Market"),
    MenuEntry("trade_history", "History"),
    MenuEntry("paper_live", "Paper / Live"),
    MenuEntry("connection", "Connection"),
    MenuEntry("settings", "Settings")
)

/**
 * Fixed bottom navigation shown on every screen - the primary destinations are
 * always one tap away and everything else is one tap further, behind Menu. No dead
 * ends, and no duplicated chip rows on the dashboard.
 */
@Composable
fun BottomNavBar(navController: NavController, currentRoute: String?) {
    var menuExpanded by remember { mutableStateOf(false) }

    // A screen reached through the menu keeps Menu highlighted, so the bar never
    // looks like nothing is selected.
    val menuRouteActive = MENU_ENTRIES.any { it.route == currentRoute }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TabItem(navController, currentRoute, "dashboard", "Home")
        TabItem(navController, currentRoute, "opportunities", "Opps")
        TabItem(navController, currentRoute, "execution", "Trades")
        TabItem(navController, currentRoute, "logs", "Logs")

        Box {
            NavLabel(
                label = "Menu",
                selected = menuRouteActive,
                onClick = { menuExpanded = true }
            )
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                MENU_ENTRIES.forEach { entry ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (entry.route == currentRoute) "[${entry.label}]" else entry.label,
                                fontSize = 13.sp,
                                fontWeight = if (entry.route == currentRoute) FontWeight.Bold else FontWeight.Normal,
                                color = if (entry.route == currentRoute) BtaYellow else MaterialTheme.colorScheme.onBackground
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            navigateTo(navController, entry.route)
                        }
                    )
                    // Separates the read-only status screens from the two
                    // configuration-heavy destinations below.
                    if (entry.route == "trade_history") HorizontalDivider()
                }
            }
        }
    }
}

/** Shared navigation so bar and menu behave identically. */
private fun navigateTo(navController: NavController, route: String) {
    navController.navigate(route) {
        popUpTo("dashboard") { saveState = true }
        launchSingleTop = true
        restoreState = true
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
    NavLabel(
        label = label,
        selected = selected,
        onClick = { navigateTo(navController, route) }
    )
}

@Composable
private fun NavLabel(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
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
