package com.amneziaguard.feature.settings

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

/**
 * Import a WireGuard/AmneziaWG config three ways: paste text, open a `.conf`
 * file, or scan/pick a QR code.
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
    val context = LocalContext.current

    fun save() {
        scope.launch {
            viewModel.import(name, confText)
                .onSuccess { onImported() }
                .onFailure { error = it.message ?: "Invalid config" }
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val text = context.contentResolver.openInputStream(uri)?.use {
                it.bufferedReader().readText()
            }
            if (text != null) { confText = text; error = null }
        }
    }

    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { confText = it; error = null }
    }

    val qrImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            val decoded = QrDecoder.decodeFromUri(context, uri)
            if (decoded != null) { confText = decoded; error = null } else { error = "No QR code found in image" }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Import config", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f),
            ) { Text("File") }
            OutlinedButton(
                onClick = {
                    qrScanner.launch(
                        ScanOptions().setBeepEnabled(false).setOrientationLocked(false),
                    )
                },
                modifier = Modifier.weight(1f),
            ) { Text("Scan QR") }
            OutlinedButton(
                onClick = {
                    qrImagePicker.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                },
                modifier = Modifier.weight(1f),
            ) { Text("QR image") }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = confText,
            onValueChange = { confText = it; error = null },
            label = { Text("Paste .conf here") },
            modifier = Modifier.fillMaxWidth().height(260.dp),
        )

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { save() },
            enabled = confText.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save server")
        }
    }
}
