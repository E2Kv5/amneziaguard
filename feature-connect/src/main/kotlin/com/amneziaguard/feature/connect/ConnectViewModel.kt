package com.amneziaguard.feature.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amneziaguard.background.FilteringEngineState
import com.amneziaguard.background.TunnelController
import com.amneziaguard.background.TunnelOrchestrator
import com.amneziaguard.core.data.model.Server
import com.amneziaguard.core.data.repo.ServerRepository
import com.amneziaguard.core.data.settings.SettingsRepository
import com.amneziaguard.core.tunnel.TunnelManager
import com.amneziaguard.core.tunnel.TunnelState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class Throughput(val rxBytes: Long, val txBytes: Long, val rxPerSec: Long, val txPerSec: Long)

data class ConnectUiState(
    val tunnel: TunnelState,
    val servers: List<Server>,
    val activeServerId: Long?,
    /** True when the userspace engine carries the traffic (per-app blocking active). */
    val filtering: Boolean = false,
) {
    val activeServer: Server? get() = servers.firstOrNull { it.id == activeServerId }
    val hasServers: Boolean get() = servers.isNotEmpty()
}

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val controller: TunnelController,
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository,
    private val tunnelManager: TunnelManager,
    private val engineState: FilteringEngineState,
    orchestrator: TunnelOrchestrator,
) : ViewModel() {

    val uiState: StateFlow<ConnectUiState> = combine(
        orchestrator.state,
        serverRepository.observeServers(),
        settingsRepository.settings,
        engineState.state,
    ) { tunnel, servers, settings, engine ->
        ConnectUiState(
            tunnel = tunnel,
            servers = servers,
            activeServerId = settings.activeServerId,
            filtering = engine !is TunnelState.Down,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ConnectUiState(TunnelState.Down, emptyList(), null),
    )

    private val _throughput = MutableStateFlow(Throughput(0, 0, 0, 0))
    val throughput: StateFlow<Throughput> = _throughput.asStateFlow()

    init {
        viewModelScope.launch { sampleThroughput() }
    }

    fun connect(serverId: Long) = controller.connect(serverId)

    fun disconnect() = controller.disconnect()

    fun selectServer(serverId: Long) {
        viewModelScope.launch { settingsRepository.setActiveServerId(serverId) }
    }

    private suspend fun sampleThroughput() {
        var lastRx = 0L
        var lastTx = 0L
        while (viewModelScope.isActive) {
            // Counters come from whichever datapath is carrying the traffic.
            val totals = engineState.traffic()?.let { it.downloadedBytes to it.uploadedBytes }
                ?: tunnelManager.statistics()?.let { it.totalRx() to it.totalTx() }

            if (totals == null) {
                _throughput.value = Throughput(0, 0, 0, 0)
                lastRx = 0
                lastTx = 0
            } else {
                val (rx, tx) = totals
                // A path switch resets the counters; treat a drop as a fresh start
                // rather than reporting a negative rate.
                val rxPerSec = (rx - lastRx).coerceAtLeast(0)
                val txPerSec = (tx - lastTx).coerceAtLeast(0)
                _throughput.value = Throughput(rx, tx, rxPerSec, txPerSec)
                lastRx = rx
                lastTx = tx
            }
            delay(1_000)
        }
    }
}
