package com.amneziaguard.feature.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date

@Composable
fun SecurityScreen(
    modifier: Modifier = Modifier,
    viewModel: SecurityViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
            subtitle = "Block traffic when the tunnel drops until it reconnects",
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
            subtitle = "Force DNS through the tunnel and self-test periodically",
            checked = state.dnsLeakProtection,
            onCheckedChange = viewModel::setDnsLeakProtection,
        )
        ToggleRow(
            title = "Advanced firewall (root)",
            subtitle = "Use iptables for a true per-app block when root is available",
            checked = state.rootModeEnabled,
            onCheckedChange = viewModel::setRootMode,
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("DNS-leak self-test", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = lastLeakText(state.lastLeakCheckOk, state.lastLeakCheckAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = viewModel::checkForLeaks) { Text("Check for leaks now") }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("System kill-switch", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "For the strongest kill-switch, enable Always-on VPN and \"Block connections without VPN\" for AmneziaGuard in system settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }) {
            Text("Open system VPN settings")
        }
        Spacer(Modifier.height(24.dp))
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

private fun lastLeakText(ok: Boolean?, at: Long?): String {
    if (ok == null || at == null) return "No check has run yet."
    val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))
    return if (ok) "Last check: no leak detected ($time)" else "Last check: LEAK DETECTED ($time)"
}
