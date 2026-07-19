package com.amneziaguard.core.firewall

import com.amneziaguard.core.data.model.AppMode
import javax.inject.Inject

/**
 * Compiled per-app policy for the AmneziaWG VpnService.Builder.
 *
 * Android throws if both allow-list and disallow-list are used on the same
 * builder, and GoBackend applies both sets blindly. The [require] here is the
 * load-bearing invariant that keeps the tunnel from crashing at establish time.
 */
data class TunnelAppPolicy(
    val included: Set<String>,
    val excluded: Set<String>,
) {
    init {
        require(included.isEmpty() || excluded.isEmpty()) {
            "VpnService.Builder cannot mix included and excluded applications"
        }
    }

    companion object {
        val EMPTY = TunnelAppPolicy(emptySet(), emptySet())
    }
}

/**
 * Translates the 3-mode per-app rules into the include/exclude sets the tunnel
 * understands. BLOCK apps are always routed *into* the tunnel so their real
 * network is hidden; the actual "no internet" enforcement (blackhole VpnService
 * or root iptables) lives elsewhere — see [com.amneziaguard.core.firewall].
 *
 * - defaultMode = VPN: everything tunnels; only BYPASS apps are excluded.
 * - defaultMode = BYPASS: only VPN and BLOCK apps are included.
 */
class FirewallPolicyCompiler @Inject constructor() {

    fun compile(rules: Map<String, AppMode>, defaultMode: AppMode): TunnelAppPolicy {
        val vpn = rules.filterValues { it == AppMode.VPN }.keys
        val bypass = rules.filterValues { it == AppMode.BYPASS }.keys
        val block = rules.filterValues { it == AppMode.BLOCK }.keys

        return when (defaultMode) {
            AppMode.VPN, AppMode.BLOCK -> TunnelAppPolicy(
                included = emptySet(),
                excluded = bypass,
            )
            AppMode.BYPASS -> TunnelAppPolicy(
                included = vpn + block,
                excluded = emptySet(),
            )
        }
    }

    /** Package names that must be fully cut off from the network (BLOCK mode). */
    fun blockedPackages(rules: Map<String, AppMode>): Set<String> =
        rules.filterValues { it == AppMode.BLOCK }.keys
}
