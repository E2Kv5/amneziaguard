package com.amneziaguard.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amneziaguard.core.tunnel.ProxySpike
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SpikeUiState(
    val running: Boolean = false,
    val log: List<String> = emptyList(),
    val exitIp: String? = null,
)

@HiltViewModel
class SpikeViewModel @Inject constructor(
    private val proxySpike: ProxySpike,
) : ViewModel() {

    private val _state = MutableStateFlow(SpikeUiState())
    val state: StateFlow<SpikeUiState> = _state.asStateFlow()

    fun run() {
        if (_state.value.running) return
        _state.value = SpikeUiState(running = true, log = listOf("— SOCKS5 spike started —"))
        viewModelScope.launch {
            val outcome = proxySpike.run { line ->
                _state.update { it.copy(log = it.log + line) }
            }
            _state.update { it.copy(running = false, exitIp = outcome.exitIp) }
        }
    }
}
