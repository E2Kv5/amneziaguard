package com.amneziaguard.core.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The three per-app firewall modes, in display order. */
enum class TriMode { VPN, BYPASS, BLOCK }

@Composable
fun TriStateAppToggle(
    selected: TriMode,
    onSelect: (TriMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = TriMode.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
            ) {
                Text(mode.label)
            }
        }
    }
}

private val TriMode.label: String
    get() = when (this) {
        TriMode.VPN -> "VPN"
        TriMode.BYPASS -> "Direct"
        TriMode.BLOCK -> "Block"
    }
