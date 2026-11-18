package com.helper.voiceassistant.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.helper.voiceassistant.presentation.screen.HistoryScreen
import com.helper.voiceassistant.presentation.screen.LogsScreen
import com.helper.voiceassistant.presentation.screen.MainScreen
import com.helper.voiceassistant.presentation.screen.SettingsScreen

object Routes {
    const val MAIN = "main"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
    const val LOGS = "logs"
}

@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) {
            MainScreen(
                viewModel = hiltViewModel(),
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenHistory = { nav.navigate(Routes.HISTORY) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(hiltViewModel(), onBack = { nav.popBackStack() }, onOpenLogs = { nav.navigate(Routes.LOGS) })
        }
        composable(Routes.HISTORY) {
            HistoryScreen(hiltViewModel(), onBack = { nav.popBackStack() })
        }
        composable(Routes.LOGS) {
            LogsScreen(onBack = { nav.popBackStack() })
        }
    }
}
