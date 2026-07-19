package com.crsk.openclaw.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import com.composables.icons.lucide.Activity
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.crsk.openclaw.ui.chat.ChatScreen
import com.crsk.openclaw.ui.components.NavTab
import com.crsk.openclaw.ui.components.SegmentedBottomNav
import com.crsk.openclaw.ui.settings.SettingsScreen
import com.crsk.openclaw.ui.setup.SetupScreen
import com.crsk.openclaw.ui.status.StatusScreen

sealed class Screen(val route: String) {
    data object Setup : Screen("setup")
    data object Chat : Screen("chat")
    data object Status : Screen("status")
    data object Settings : Screen("settings")
    data object Heartbeat : Screen("heartbeat")
    data object Connections : Screen("connections")
}

private val MainTabs = listOf(
    NavTab(route = Screen.Chat.route, label = "Chat", icon = Lucide.MessageCircle),
    NavTab(route = Screen.Status.route, label = "Status", icon = Lucide.Activity),
    NavTab(route = Screen.Settings.route, label = "Settings", icon = Lucide.Settings),
)

@Composable
fun AppNavigation(isSetupComplete: Boolean) {
    val navController = rememberNavController()

    // Stable startDestination — computed ONCE from the initial state.
    // MainActivity gates rendering until DataStore resolves, so this value is
    // correct on first composition and never flip-flops.
    val startDestination = remember {
        if (!isSetupComplete) Screen.Setup.route else Screen.Chat.route
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: startDestination
    val showBottomBar = currentRoute in MainTabs.map { it.route }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            if (showBottomBar) {
                SegmentedBottomNav(
                    tabs = MainTabs,
                    activeRoute = currentRoute,
                    onSelect = { route -> navController.navigateTab(route) },
                )
            }
        },
    ) { padding ->
        val contentPadding = if (showBottomBar) {
            PaddingValues(bottom = padding.calculateBottomPadding())
        } else {
            PaddingValues(0.dp)
        }
        NavHost(
            navController = navController,
            startDestination = startDestination,
            // Edge-to-edge: consume the bottom-bar padding, then add IME padding so the
            // keyboard lifts content smoothly without double-counting the nav bar. Without
            // this the window has no IME inset handling at all → jumpy keyboard + input lag.
            modifier = Modifier
                .padding(contentPadding)
                .consumeWindowInsets(contentPadding)
                .imePadding(),
        ) {
            composable(Screen.Setup.route) {
                SetupScreen(
                    onSetupComplete = {
                        navController.navigate(Screen.Chat.route) {
                            popUpTo(Screen.Setup.route) { inclusive = true }
                        }
                    },
                )
            }
            composable(Screen.Chat.route) { ChatScreen() }
            composable(Screen.Status.route) {
                StatusScreen(
                    onOpenIntegrations = { navController.navigate(Screen.Connections.route) },
                    onOpenSettings = { navController.navigateTab(Screen.Settings.route) },
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onOpenHeartbeat = { navController.navigate(Screen.Heartbeat.route) },
                )
            }
            composable(Screen.Heartbeat.route) {
                com.crsk.openclaw.ui.heartbeat.HeartbeatSettingsScreen()
            }
            composable(Screen.Connections.route) {
                com.crsk.openclaw.ui.connections.ConnectionsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
