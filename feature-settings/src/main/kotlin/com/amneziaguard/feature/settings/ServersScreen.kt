package com.amneziaguard.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amneziaguard.core.data.model.Server

@Composable
fun ServersScreen(
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServersViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Servers",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.TextButton(onClick = onImport) { Text("Import") }
        }
        Spacer(Modifier.height(12.dp))

        if (state.servers.isEmpty()) {
            Text(
                "No servers yet. Import a .conf to begin.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn {
                items(state.servers, key = { it.id }) { server ->
                    ServerRow(
                        server = server,
                        active = server.id == state.activeServerId,
                        onSelect = { viewModel.selectServer(server.id) },
                        onDelete = { viewModel.delete(server.id) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun ServerRow(
    server: Server,
    active: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(onClick = onSelect, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(server.name, style = MaterialTheme.typography.titleMedium)
                if (server.endpoint.isNotBlank()) {
                    Text(
                        server.endpoint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (active) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Active",
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}
