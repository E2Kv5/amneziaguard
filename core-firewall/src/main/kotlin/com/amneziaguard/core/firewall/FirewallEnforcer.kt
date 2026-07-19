package com.amneziaguard.core.firewall

import com.amneziaguard.core.data.model.AppMode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies the "no internet" (BLOCK) enforcement and the kill-switch blackhole
 * across the available tiers:
 *
 * - Kill-switch engaged (tunnel expected up but down) → [GuardVpnService] in
 *   kill-switch mode: everyone except BYPASS apps is blackholed until the
 *   tunnel is restored. This also covers BLOCK apps.
 * - Root available + enabled → iptables owner-match DROP for BLOCK apps
 *   (works in any tunnel state).
 * - No root, tunnel down, block-when-disconnected → blackhole the BLOCK apps.
 * - No root, tunnel up → BLOCK apps are already forced into the tunnel by the
 *   policy compiler; nothing extra to do (documented limitation).
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
        val killSwitchActive: Boolean,
        val rootModeEnabled: Boolean,
        val blockWhenDisconnected: Boolean,
    )

    suspend fun apply(inputs: Inputs) = mutex.withLock {
        val blocked = inputs.rules.filterValues { it == AppMode.BLOCK }.keys
        val bypass = inputs.rules.filterValues { it == AppMode.BYPASS }.keys

        // Highest priority: kill-switch fail-closed blackhole.
        if (inputs.killSwitchActive) {
            guardController.startKillSwitch(bypass)
            blackholeActive = true
            // Root block can stay applied underneath; it is harmless.
            if (inputs.rootModeEnabled && rootController.isRootAvailable()) {
                applyRootBlock(blocked)
            }
            return@withLock
        }

        // Tier 1: root iptables — authoritative when available and enabled.
        if (inputs.rootModeEnabled && rootController.isRootAvailable()) {
            stopBlackhole()
            applyRootBlock(blocked)
            return@withLock
        }
        clearRootBlock()

        // Tier 2: blackhole while the tunnel is down.
        if (!inputs.tunnelActive && inputs.blockWhenDisconnected && blocked.isNotEmpty()) {
            guardController.startBlocking(blocked)
            blackholeActive = true
        } else {
            stopBlackhole()
        }
        // Tier 3 (tunnel up, no root): handled by the policy compiler.
    }

    suspend fun clearAll() = mutex.withLock {
        clearRootBlock()
        stopBlackhole()
    }

    private suspend fun applyRootBlock(blocked: Set<String>) {
        val uids = installedApps.installedApps()
            .filter { it.packageName in blocked }
            .map { it.uid }
            .toSet()
        rootController.applyBlockedUids(uids)
        rootActive = true
    }

    private suspend fun clearRootBlock() {
        if (rootActive) {
            rootController.clear()
            rootActive = false
        }
    }

    private fun stopBlackhole() {
        if (blackholeActive) {
            guardController.stop()
            blackholeActive = false
        }
    }
}
