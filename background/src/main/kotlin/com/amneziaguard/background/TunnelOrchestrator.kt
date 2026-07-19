package com.amneziaguard.background

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import com.amneziaguard.core.data.repo.RuleRepository
import com.amneziaguard.core.data.settings.SettingsRepository
import com.amneziaguard.core.firewall.FirewallEnforcer
import com.amneziaguard.core.firewall.FirewallPolicyCompiler
import com.amneziaguard.core.tunnel.TunnelManager
import com.amneziaguard.core.tunnel.TunnelState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single arbiter of tunnel + firewall state. Forwards connect/disconnect to
 * [TunnelManager], drives BLOCK enforcement through [FirewallEnforcer], rebuilds
 * the tunnel when per-app rules change while connected, and keeps the Quick
 * Settings tile in sync.
 */
@Singleton
class TunnelOrchestrator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tunnelManager: TunnelManager,
    private val settingsRepository: SettingsRepository,
    private val ruleRepository: RuleRepository,
    private val policyCompiler: FirewallPolicyCompiler,
    private val firewallEnforcer: FirewallEnforcer,
) {
    private val scope = CoroutineScope(SupervisorJob())

    val state: StateFlow<TunnelState> = tunnelManager.state

    private var lastPolicySignature: String? = null

    init {
        state.onEach { refreshTile() }.launchIn(scope)
        observeFirewall()
    }

    fun connect(serverId: Long) {
        scope.launch {
            settingsRepository.setActiveServerId(serverId)
            lastPolicySignature = currentPolicySignature()
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

    /**
     * Combines rules, settings and tunnel state; applies BLOCK enforcement and,
     * when the app-routing policy changes while connected, rebuilds the tunnel.
     */
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
                firewallEnforcer.apply(
                    FirewallEnforcer.Inputs(
                        rules = rules,
                        tunnelActive = tunnel.isActive,
                        rootModeEnabled = settings.rootModeEnabled,
                        blockWhenDisconnected = settings.blockWhenDisconnected,
                    ),
                )

                if (tunnel is TunnelState.Up) {
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
            }
            .launchIn(scope)
    }

    private suspend fun rebuildTunnel(serverId: Long) {
        tunnelManager.disconnect()
        tunnelManager.connect(serverId)
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
