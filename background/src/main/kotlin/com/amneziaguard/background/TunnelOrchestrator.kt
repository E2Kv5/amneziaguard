package com.amneziaguard.background

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import com.amneziaguard.core.data.settings.SettingsRepository
import com.amneziaguard.core.tunnel.TunnelManager
import com.amneziaguard.core.tunnel.TunnelState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single arbiter of tunnel state. For the MVP it forwards connect/disconnect to
 * [TunnelManager], persists the active server, and pushes state changes to the
 * Quick Settings tile. Kill-switch / blackhole coordination is layered on here
 * in later milestones.
 */
@Singleton
class TunnelOrchestrator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tunnelManager: TunnelManager,
    private val settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob())

    val state: StateFlow<TunnelState> = tunnelManager.state

    init {
        // Any state transition refreshes the tile so the shortcut stays in sync.
        state.onEach { refreshTile() }.launchIn(scope)
    }

    fun connect(serverId: Long) {
        scope.launch {
            settingsRepository.setActiveServerId(serverId)
            tunnelManager.connect(serverId)
        }
    }

    fun disconnect() {
        scope.launch { tunnelManager.disconnect() }
    }

    fun toggle(serverId: Long) {
        if (state.value.isActive) disconnect() else connect(serverId)
    }

    suspend fun onNetworkChanged() = tunnelManager.onNetworkChanged()

    private fun refreshTile() {
        runCatching {
            TileService.requestListeningState(
                context,
                ComponentName(context, TILE_SERVICE_CLASS),
            )
        }
    }

    private companion object {
        const val TILE_SERVICE_CLASS = "com.amneziaguard.tile.AmneziaTileService"
    }
}
