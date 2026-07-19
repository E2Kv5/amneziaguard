package com.amneziaguard.app.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.amneziaguard.feature.connect.ConnectScreen
import com.amneziaguard.feature.firewall.FirewallScreen
import com.amneziaguard.feature.settings.ImportScreen
import com.amneziaguard.feature.settings.SecurityScreen
import com.amneziaguard.feature.settings.ServerEditScreen
import com.amneziaguard.feature.settings.ServersScreen

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute != Routes.IMPORT && currentRoute != Routes.SERVER_EDIT

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    val current = backStackEntry?.destination
                    TopDestination.entries.forEach { dest ->
                        NavigationBarItem(
                            selected = current?.hierarchy?.any { it.route == dest.route } == true,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopDestination.Connect.route,
            modifier = Modifier.padding(padding),
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
}
