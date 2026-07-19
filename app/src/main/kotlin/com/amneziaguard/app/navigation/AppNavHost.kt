package com.amneziaguard.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.amneziaguard.feature.connect.ConnectScreen
import com.amneziaguard.feature.firewall.FirewallScreen
import com.amneziaguard.feature.settings.ImportScreen
import com.amneziaguard.feature.settings.SecurityScreen
import com.amneziaguard.feature.settings.ServerEditScreen
import com.amneziaguard.feature.settings.ServersScreen

/**
 * The navigation graph only. Scaffolding and window-inset handling live in
 * [com.amneziaguard.app.AmneziaApp] so the inset padding comes from a single
 * stable container and can't desync per destination.
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = TopDestination.Connect.route,
        modifier = modifier,
    ) {
        composable(TopDestination.Connect.route) {
            ConnectScreen(onImport = { navController.navigate(Routes.IMPORT) })
        }
        composable(TopDestination.Firewall.route) {
            FirewallScreen()
        }
        composable(TopDestination.Servers.route) {
            ServersScreen(
                onImport = { navController.navigate(Routes.IMPORT) },
                onEdit = { id -> navController.navigate(Routes.serverEdit(id)) },
            )
        }
        composable(TopDestination.Security.route) {
            SecurityScreen()
        }
        composable(Routes.IMPORT) {
            ImportScreen(onImported = { navController.popBackStack() })
        }
        composable(
            Routes.SERVER_EDIT,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) {
            ServerEditScreen(onSaved = { navController.popBackStack() })
        }
    }
}
