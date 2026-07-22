package com.amneziaguard.background

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.amneziaguard.core.data.model.AppMode
import com.amneziaguard.core.data.repo.RuleRepository
import com.amneziaguard.core.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single entry point the UI, the tile, boot and always-on use to drive the
 * tunnel — and the place that decides *which datapath* carries it.
 *
 * Two paths exist because they are good at different things:
 *  - the fast path hands the tun straight to amneziawg-go, so the whole
 *    datapath stays in Go; per-app VPN/bypass is expressed with the interface's
 *    include/exclude lists, but packets can't be filtered per app;
 *  - the userspace engine ([FilteringVpnService]) relays every flow through the
 *    tunnel's SOCKS5 in our own code, which is what makes "no internet" work
 *    without root — at the cost of moving the datapath into the JVM.
 *
 * So the engine is used exactly when a rule needs packet-level filtering, and
 * the fast path otherwise. The choice is made here, before any service starts,
 * so only one of them ever runs.
 */
@Singleton
class TunnelController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ruleRepository: RuleRepository,
    private val settingsRepository: SettingsRepository,
    private val engineState: FilteringEngineState,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun connect(serverId: Long) {
        scope.launch {
            settingsRepository.setActiveServerId(serverId)
            if (needsFilteringEngine()) startEngine() else startFastPath(serverId)
        }
    }

    fun disconnect() {
        // Stop both: only one is running, and stopping the idle one is a no-op.
        context.startService(stopEngineIntent())
        startService(TunnelForegroundService.ACTION_DISCONNECT, null)
    }

    fun toggle(serverId: Long?) {
        scope.launch {
            if (engineState.isActive) {
                disconnect()
            } else if (serverId != null) {
                settingsRepository.setActiveServerId(serverId)
                if (needsFilteringEngine()) startEngine() else startService(
                    TunnelForegroundService.ACTION_TOGGLE, serverId,
                )
            } else {
                startService(TunnelForegroundService.ACTION_TOGGLE, null)
            }
        }
    }

    /** Starts the userspace engine directly (diagnostics / explicit choice). */
    fun startFilteringEngine() = startEngine()

    private suspend fun needsFilteringEngine(): Boolean =
        ruleRepository.rules().values.any { it == AppMode.BLOCK }

    private fun startEngine() {
        context.startService(
            Intent(context, FilteringVpnService::class.java)
                .setAction(FilteringVpnService.ACTION_START_FIREWALL),
        )
    }

    private fun startFastPath(serverId: Long) =
        startService(TunnelForegroundService.ACTION_CONNECT, serverId)

    private fun stopEngineIntent(): Intent =
        Intent(context, FilteringVpnService::class.java).setAction(FilteringVpnService.ACTION_STOP)

    private fun startService(action: String, serverId: Long?) {
        val intent = Intent(context, TunnelForegroundService::class.java).setAction(action)
        serverId?.let { intent.putExtra(TunnelForegroundService.EXTRA_SERVER_ID, it) }
        ContextCompat.startForegroundService(context, intent)
    }
}
