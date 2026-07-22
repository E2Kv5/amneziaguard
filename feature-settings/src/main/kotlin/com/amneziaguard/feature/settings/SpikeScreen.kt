package com.amneziaguard.feature.settings

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Diagnostics for the no-root datapath. Two probes:
 *  - Plain spike: amneziawg-go SOCKS5 with bypass=0 (no VpnService).
 *  - Protected spike: the same under our FilteringVpnService with bypass=1 +
 *    protect(), which is what the real datapath will use.
 */
@Composable
fun SpikeScreen(
    modifier: Modifier = Modifier,
    viewModel: SpikeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filteringLog by viewModel.filteringLog.collectAsStateWithLifecycle()
    val filteringRunning by viewModel.filteringRunning.collectAsStateWithLifecycle()
    val filteringExitIp by viewModel.filteringExitIp.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var pending by remember { mutableStateOf(Pending.SPIKE) }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            when (pending) {
                Pending.SPIKE -> viewModel.startFilteringSpike()
                Pending.RELAY -> viewModel.startRelayTest()
                Pending.FIREWALL -> viewModel.startFirewallEngine()
            }
        }
    }

    fun launch(what: Pending) {
        pending = what
        val consent = VpnService.prepare(context)
        if (consent != null) {
            consentLauncher.launch(consent)
        } else {
            when (what) {
                Pending.SPIKE -> viewModel.startFilteringSpike()
                Pending.RELAY -> viewModel.startRelayTest()
                Pending.FIREWALL -> viewModel.startFirewallEngine()
            }
        }
    }


    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("SOCKS5 datapath spike", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Plain: amneziawg-go SOCKS5 without a VpnService. Keep the app's VPN disconnected.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = viewModel::run, enabled = !state.running) { Text("Run plain spike") }
        state.exitIp?.let {
            Spacer(Modifier.height(8.dp))
            Text("Exit IP: $it", style = MaterialTheme.typography.titleMedium)
        }
        LogBlock(state.log)

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("Protected spike (VpnService)", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Runs amneziawg-go with bypass=1 + protect() under our own VpnService — the real " +
                "datapath's setup. Grants a VPN permission prompt; only this app is captured.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { launch(Pending.SPIKE) }, enabled = !filteringRunning) {
            Text("Run protected spike")
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("TCP relay test", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Runs the tun2socks TCP relay: captures this app, then opens a real connection to " +
                "1.1.1.1 that flows tun → engine → SOCKS5 → tunnel. A VPN-server exit IP proves " +
                "the relay carries a real TCP+TLS connection.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { launch(Pending.RELAY) }, enabled = !filteringRunning) {
            Text("Run TCP relay test")
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("No-root firewall engine", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Brings the tunnel up through the userspace datapath and enforces the per-app rules " +
                "on it: apps set to \"Block\" get no network even while connected, without root. " +
                "TCP and UDP (QUIC, DNS) are both carried through the tunnel. Experimental — it " +
                "replaces the normal Connect flow.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { launch(Pending.FIREWALL) }) { Text("Start engine") }
            OutlinedButton(onClick = viewModel::stopFirewallEngine) { Text("Stop") }
        }

        filteringExitIp?.let {
            Spacer(Modifier.height(8.dp))
            Text("Exit IP (VpnService): $it", style = MaterialTheme.typography.titleMedium)
        }
        LogBlock(filteringLog)
        Spacer(Modifier.height(24.dp))
    }
}

/** Which probe the pending VPN-consent result should launch. */
private enum class Pending { SPIKE, RELAY, FIREWALL }

@Composable
private fun LogBlock(lines: List<String>) {
    if (lines.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    Column(modifier = Modifier.fillMaxWidth()) {
        lines.forEach { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}
