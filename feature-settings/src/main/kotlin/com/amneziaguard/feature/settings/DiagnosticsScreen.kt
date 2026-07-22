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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Runs the no-root datapath and shows what it is doing.
 *
 * The engine reports a line every couple of seconds while it carries traffic, so
 * this screen doubles as the readout for diagnosing throughput: rate, packet
 * rate, the cost of a tun write and the live connection count.
 */
@Composable
fun DiagnosticsScreen(
    modifier: Modifier = Modifier,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val log by viewModel.log.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.startEngine()
    }

    fun start() {
        val consent = VpnService.prepare(context)
        if (consent != null) consentLauncher.launch(consent) else viewModel.startEngine()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
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
            Button(onClick = { start() }, enabled = !running) { Text("Start engine") }
            OutlinedButton(onClick = viewModel::stopEngine) { Text("Stop") }
        }

        LogBlock(log)
        Spacer(Modifier.height(24.dp))
    }
}

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
