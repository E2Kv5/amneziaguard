package com.amneziaguard.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ServerEditScreen(
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServerEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Edit server", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::updateName,
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(16.dp))
        Text("AWG 2.0 obfuscation", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Junk packets (Jc/Jmin/Jmax), header junk sizes (S1/S2), magic headers (H1–H4) and signature packets (I1–I5) that disguise the handshake.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        val f = state.obfuscation
        NumberRow(
            "Jc" to f.jc, "Jmin" to f.jmin, "Jmax" to f.jmax,
            onChange = { key, v ->
                viewModel.updateObfuscation {
                    when (key) {
                        "Jc" -> it.copy(jc = v); "Jmin" -> it.copy(jmin = v); else -> it.copy(jmax = v)
                    }
                }
            },
        )
        NumberRow(
            "S1" to f.s1, "S2" to f.s2,
            onChange = { key, v ->
                viewModel.updateObfuscation { if (key == "S1") it.copy(s1 = v) else it.copy(s2 = v) }
            },
        )
        TextRow("H1", f.h1) { v -> viewModel.updateObfuscation { it.copy(h1 = v) } }
        TextRow("H2", f.h2) { v -> viewModel.updateObfuscation { it.copy(h2 = v) } }
        TextRow("H3", f.h3) { v -> viewModel.updateObfuscation { it.copy(h3 = v) } }
        TextRow("H4", f.h4) { v -> viewModel.updateObfuscation { it.copy(h4 = v) } }
        TextRow("I1", f.i1) { v -> viewModel.updateObfuscation { it.copy(i1 = v) } }
        TextRow("I2", f.i2) { v -> viewModel.updateObfuscation { it.copy(i2 = v) } }
        TextRow("I3", f.i3) { v -> viewModel.updateObfuscation { it.copy(i3 = v) } }
        TextRow("I4", f.i4) { v -> viewModel.updateObfuscation { it.copy(i4 = v) } }
        TextRow("I5", f.i5) { v -> viewModel.updateObfuscation { it.copy(i5 = v) } }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = viewModel::save,
            enabled = state.loaded,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save changes")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun NumberRow(
    vararg fields: Pair<String, String>,
    onChange: (String, String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        fields.forEach { (label, value) ->
            OutlinedTextField(
                value = value,
                onValueChange = { onChange(label, it.filter(Char::isDigit)) },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TextRow(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
