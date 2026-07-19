package com.amneziaguard.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

/**
 * Minimal import surface: paste a WireGuard/AmneziaWG .conf and save it. File
 * and QR import are layered on in a later milestone.
 */
@Composable
fun ImportScreen(
    onImported: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServersViewModel = hiltViewModel(),
) {
    var name by remember { mutableStateOf("") }
    var confText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Import config", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = confText,
            onValueChange = { confText = it; error = null },
            label = { Text("Paste .conf here") },
            modifier = Modifier.fillMaxWidth().height(280.dp),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Default),
        )

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                scope.launch {
                    viewModel.import(name, confText)
                        .onSuccess { onImported() }
                        .onFailure { error = it.message ?: "Invalid config" }
                }
            },
            enabled = confText.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save server")
        }
    }
}
