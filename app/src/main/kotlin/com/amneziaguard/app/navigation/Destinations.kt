package com.amneziaguard.app.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.ui.graphics.vector.ImageVector

enum class TopDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Connect("connect", "Connect", Icons.Default.VpnKey),
    Firewall("firewall", "Firewall", Icons.Default.Apps),
    Servers("servers", "Servers", Icons.Default.Dns),
    Security("security", "Security", Icons.Default.Security),
}

object Routes {
    const val IMPORT = "import"
    const val SERVER_EDIT = "server_edit/{id}"
    fun serverEdit(id: Long) = "server_edit/$id"
}
