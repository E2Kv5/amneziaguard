package com.amneziaguard.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// Kill-switch bridge, root mode and DNS leak self-test are wired in milestone M6.
@Composable
fun SecurityScreen(
    modifier: Modifier = Modifier,
    viewModel: SecurityViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Security", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        ToggleRow(
            title = "Kill-switch",
            subtitle = "Block traffic when the tunnel drops",
            checked = state.killSwitchEnabled,
            onCheckedChange = viewModel::setKillSwitch,
        )
        ToggleRow(
            title = "Block when disconnected",
            subtitle = "Cut off \"blocked\" apps while the VPN is off",
            checked = state.blockWhenDisconnected,
            onCheckedChange = viewModel::setBlockWhenDisconnected,
        )
        ToggleRow(
            title = "DNS-leak protection",
            subtitle = "Force DNS through the tunnel",
            checked = state.dnsLeakProtection,
            onCheckedChange = viewModel::setDnsLeakProtection,
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
