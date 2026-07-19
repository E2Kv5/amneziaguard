package com.amneziaguard.core.firewall

import com.amneziaguard.core.data.model.AppMode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies the "no internet" (BLOCK) enforcement across the three tiers:
 *
 * 1. Root available + enabled → iptables owner-match DROP (works in any state).
 * 2. No root, tunnel down, block-when-disconnected → [GuardVpnService] blackhole.
 * 3. No root, tunnel up → BLOCK apps are already forced into the tunnel by the
 *    policy compiler; nothing extra to do here (documented limitation).
 */
@Singleton
class FirewallEnforcer @Inject constructor(
    private val rootController: RootFirewallController,
    private val guardController: GuardController,
    private val installedApps: InstalledAppsRepository,
) {
    private val mutex = Mutex()
    private var rootActive = false
    private var blackholeActive = false

    data class Inputs(
        val rules: Map<String, AppMode>,
        val tunnelActive: Boolean,
        val rootModeEnabled: Boolean,
        val blockWhenDisconnected: Boolean,
    )

    suspend fun apply(inputs: Inputs) = mutex.withLock {
        val blocked = inputs.rules.filterValues { it == AppMode.BLOCK }.keys

        // Tier 1: root iptables — authoritative when available and enabled.
        if (inputs.rootModeEnabled && rootController.isRootAvailable()) {
            stopBlackhole()
            val uids = installedApps.installedApps()
                .filter { it.packageName in blocked }
                .map { it.uid }
                .toSet()
            rootController.applyBlockedUids(uids)
            rootActive = true
            return@withLock
        }
        if (rootActive) {
            rootController.clear()
            rootActive = false
        }

        // Tier 2: blackhole while the tunnel is down.
        if (!inputs.tunnelActive && inputs.blockWhenDisconnected && blocked.isNotEmpty()) {
            guardController.start(blocked)
            blackholeActive = true
        } else {
            stopBlackhole()
        }
        // Tier 3 (tunnel up, no root): handled by the policy compiler.
    }

    suspend fun clearAll() = mutex.withLock {
        if (rootActive) {
            rootController.clear()
            rootActive = false
        }
        stopBlackhole()
    }

    private fun stopBlackhole() {
        if (blackholeActive) {
            guardController.stop()
            blackholeActive = false
        }
    }
}
