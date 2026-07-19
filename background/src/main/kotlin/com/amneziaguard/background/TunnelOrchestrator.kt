package com.amneziaguard.background

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import com.amneziaguard.core.data.repo.RuleRepository
import com.amneziaguard.core.data.settings.SettingsRepository
import com.amneziaguard.core.firewall.FirewallEnforcer
import com.amneziaguard.core.firewall.FirewallPolicyCompiler
import com.amneziaguard.core.firewall.leak.LeakCheckScheduler
import com.amneziaguard.core.tunnel.TunnelManager
import com.amneziaguard.core.tunnel.TunnelState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single arbiter of tunnel + firewall state. Forwards connect/disconnect to
 * [TunnelManager], drives BLOCK + kill-switch enforcement through
 * [FirewallEnforcer], rebuilds the tunnel when per-app rules change while
 * connected, reconnects after an unexpected drop, and keeps the tile in sync.
 */
@Singleton
class TunnelOrchestrator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tunnelManager: TunnelManager,
    private val settingsRepository: SettingsRepository,
    private val ruleRepository: RuleRepository,
    private val policyCompiler: FirewallPolicyCompiler,
    private val firewallEnforcer: FirewallEnforcer,
    private val leakCheckScheduler: LeakCheckScheduler,
) {
    private val scope = CoroutineScope(SupervisorJob())

    val state: StateFlow<TunnelState> = tunnelManager.state

    /** The server the user wants connected; null means "stay disconnected". */
    @Volatile private var desiredServerId: Long? = null
    private var lastPolicySignature: String? = null
    private var reconnectJob: Job? = null

    init {
        state.onEach { refreshTile() }.launchIn(scope)
        observeFirewall()
    }

    fun connect(serverId: Long) {
        scope.launch {
            desiredServerId = serverId
            settingsRepository.setActiveServerId(serverId)
            lastPolicySignature = currentPolicySignature()
            tunnelManager.connect(serverId)
            scheduleLeakChecks()
        }
    }

    fun disconnect() {
        scope.launch {
            desiredServerId = null
            reconnectJob?.cancel()
            tunnelManager.disconnect()
        }
    }

    fun toggle(serverId: Long) {
        if (desiredServerId != null || state.value.isActive) disconnect() else connect(serverId)
    }

    suspend fun onNetworkChanged() = tunnelManager.onNetworkChanged()

    private fun observeFirewall() {
        combine(
            ruleRepository.observeRules(),
            settingsRepository.settings,
            state,
        ) { rules, settings, tunnel ->
            Triple(rules, settings, tunnel)
        }
            .distinctUntilChanged()
            .onEach { (rules, settings, tunnel) ->
                val wantConnected = desiredServerId != null
                val killSwitchActive =
                    wantConnected && !tunnel.isActive && settings.killSwitchEnabled

                firewallEnforcer.apply(
                    FirewallEnforcer.Inputs(
                        rules = rules,
                        tunnelActive = tunnel.isActive,
                        killSwitchActive = killSwitchActive,
                        rootModeEnabled = settings.rootModeEnabled,
                        blockWhenDisconnected = settings.blockWhenDisconnected,
                    ),
                )

                when {
                    tunnel is TunnelState.Up -> {
                        reconnectJob?.cancel()
                        val signature = policyCompiler
                            .compile(rules, settings.defaultAppMode)
                            .let { "${it.included.sorted()}|${it.excluded.sorted()}" }
                        if (lastPolicySignature != null && signature != lastPolicySignature) {
                            lastPolicySignature = signature
                            rebuildTunnel(tunnel.serverId)
                        } else {
                            lastPolicySignature = signature
                        }
                    }
                    // Unexpected drop while the user still wants a connection.
                    wantConnected && (tunnel is TunnelState.Down || tunnel is TunnelState.Error) -> {
                        startReconnectLoop()
                    }
                }
            }
            .launchIn(scope)
    }

    private fun startReconnectLoop() {
        if (reconnectJob?.isActive == true) return
        val serverId = desiredServerId ?: return
        reconnectJob = scope.launch {
            var delayMs = 2_000L
            while (isActive && desiredServerId == serverId && !state.value.isActive) {
                delay(delayMs)
                if (desiredServerId != serverId) break
                tunnelManager.connect(serverId)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun rebuildTunnel(serverId: Long) {
        tunnelManager.disconnect()
        tunnelManager.connect(serverId)
    }

    private suspend fun scheduleLeakChecks() {
        val settings = settingsRepository.settings.first()
        if (settings.dnsLeakProtection) {
            leakCheckScheduler.schedulePeriodic(settings.leakCheckIntervalHours.toLong())
        }
    }

    private suspend fun currentPolicySignature(): String {
        val settings = settingsRepository.settings.first()
        val rules = ruleRepository.rules()
        return policyCompiler.compile(rules, settings.defaultAppMode)
            .let { "${it.included.sorted()}|${it.excluded.sorted()}" }
    }

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
