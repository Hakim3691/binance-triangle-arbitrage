package com.hakim3691.bta

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hakim3691.bta.ui.ArbViewModel
import com.hakim3691.bta.ui.ArbViewModelFactory
import com.hakim3691.bta.ui.screens.BottomNavBar
import com.hakim3691.bta.ui.screens.ConnectionScreen
import com.hakim3691.bta.ui.screens.DashboardScreen
import com.hakim3691.bta.ui.screens.ExecutionScreen
import com.hakim3691.bta.ui.screens.LogsScreen
import com.hakim3691.bta.ui.screens.MarketScreen
import com.hakim3691.bta.ui.screens.OpportunitiesScreen
import com.hakim3691.bta.ui.screens.OpportunityDetailScreen
import com.hakim3691.bta.ui.screens.PaperLiveScreen
import com.hakim3691.bta.ui.screens.SettingsScreen
import com.hakim3691.bta.ui.screens.TradeHistoryScreen
import com.hakim3691.bta.ui.theme.BtaTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ArbViewModel by viewModels { ArbViewModelFactory(application as ArbApplication) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BtaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ArbApp(viewModel)
                }
            }
        }
    }
}

object Routes {
    const val DASHBOARD = "dashboard"
    const val OPPORTUNITIES = "opportunities"
    const val OPPORTUNITY_DETAIL = "opportunity/{opportunityId}"
    const val MARKET = "market"
    const val EXECUTION = "execution"
    const val TRADE_HISTORY = "trade_history"
    const val LOGS = "logs"
    const val SETTINGS = "settings"
    const val CONNECTION = "connection"
    const val PAPER_LIVE = "paper_live"

    fun opportunityDetail(id: String) = "opportunity/$id"
}

@Composable
fun ArbApp(viewModel: ArbViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Column(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier.weight(1f)
        ) {
        composable(Routes.DASHBOARD) {
            DashboardScreen(viewModel, navController)
        }
        composable(Routes.OPPORTUNITIES) {
            OpportunitiesScreen(viewModel, navController)
        }
        composable(Routes.OPPORTUNITY_DETAIL) { entry ->
            val id = entry.arguments?.getString("opportunityId") ?: ""
            OpportunityDetailScreen(viewModel, navController, id)
        }
        composable(Routes.MARKET) {
            MarketScreen(viewModel, navController)
        }
        composable(Routes.EXECUTION) {
            ExecutionScreen(viewModel, navController)
        }
        composable(Routes.TRADE_HISTORY) {
            TradeHistoryScreen(viewModel, navController)
        }
        composable(Routes.LOGS) {
            LogsScreen(viewModel, navController)
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(viewModel, navController)
        }
        composable(Routes.CONNECTION) {
            ConnectionScreen(viewModel, navController)
        }
        composable(Routes.PAPER_LIVE) {
            PaperLiveScreen(viewModel, navController)
        }
        }
        BottomNavBar(navController, currentRoute)
    }
}
