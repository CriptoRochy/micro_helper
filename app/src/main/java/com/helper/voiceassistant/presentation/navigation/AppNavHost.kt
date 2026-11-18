package com.helper.voiceassistant.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.helper.voiceassistant.presentation.screen.HistoryScreen
import com.helper.voiceassistant.presentation.screen.LibraryScreen
import com.helper.voiceassistant.presentation.screen.LogsScreen
import com.helper.voiceassistant.presentation.screen.SearchScreen
import com.helper.voiceassistant.presentation.screen.MainScreen
import com.helper.voiceassistant.presentation.screen.SettingsScreen

object Routes {
    const val MAIN = "main"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
    const val LOGS = "logs"
    const val SEARCH = "search"
    const val LIBRARY = "library"
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
                onOpenSearch = { nav.navigate(Routes.SEARCH) },
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(hiltViewModel(), onBack = { nav.popBackStack() }, onOpenLibrary = { nav.navigate(Routes.LIBRARY) })
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(hiltViewModel(), onBack = { nav.popBackStack() })
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
