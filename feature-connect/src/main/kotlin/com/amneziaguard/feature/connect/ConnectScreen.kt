package com.amneziaguard.feature.connect

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amneziaguard.core.tunnel.TunnelState
import com.amneziaguard.core.ui.formatRate

@Composable
fun ConnectScreen(
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConnectViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val throughput by viewModel.throughput.collectAsStateWithLifecycle()

    var pendingServerId by remember { mutableStateOf<Long?>(null) }
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val serverId = pendingServerId
        pendingServerId = null
        if (result.resultCode == Activity.RESULT_OK && serverId != null) {
            viewModel.connect(serverId)
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current

    fun startConnect(serverId: Long) {
        val consent = VpnService.prepare(context)
        if (consent != null) {
            pendingServerId = serverId
            consentLauncher.launch(consent)
        } else {
            viewModel.connect(serverId)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val tunnel = state.tunnel
        val active = tunnel is TunnelState.Up

        ConnectButton(
            state = tunnel,
            enabled = state.hasServers,
            onClick = {
                if (active) {
                    viewModel.disconnect()
                } else {
                    val serverId = state.activeServerId ?: state.servers.firstOrNull()?.id
                    if (serverId != null) startConnect(serverId)
                }
            },
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = statusLabel(tunnel),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = state.activeServer?.name ?: "No server selected",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (active) {
            Spacer(Modifier.height(24.dp))
            ThroughputCard(rxPerSec = throughput.rxPerSec, txPerSec = throughput.txPerSec)
        }

        if (!state.hasServers) {
            Spacer(Modifier.height(32.dp))
            Text(
                text = "Import a server config to get started.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onImport) { Text("Import config") }
        } else {
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onImport) { Text("Import / manage servers") }
        }
    }
}

@Composable
private fun ConnectButton(
    state: TunnelState,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val color = when (state) {
        is TunnelState.Up -> MaterialTheme.colorScheme.primary
        TunnelState.Connecting -> MaterialTheme.colorScheme.primaryContainer
        is TunnelState.Error -> MaterialTheme.colorScheme.error
        TunnelState.Down -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = color,
        modifier = Modifier.size(180.dp).clip(CircleShape),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = if (state is TunnelState.Up) "Disconnect" else "Connect",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun ThroughputCard(rxPerSec: Long, txPerSec: Long) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Download", style = MaterialTheme.typography.labelMedium)
                Text(formatRate(rxPerSec), style = MaterialTheme.typography.titleMedium)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Upload", style = MaterialTheme.typography.labelMedium)
                Text(formatRate(txPerSec), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private fun statusLabel(state: TunnelState): String = when (state) {
    is TunnelState.Up -> "Connected"
    TunnelState.Connecting -> "Connecting…"
    TunnelState.Down -> "Disconnected"
    is TunnelState.Error -> "Error: ${state.message}"
}
