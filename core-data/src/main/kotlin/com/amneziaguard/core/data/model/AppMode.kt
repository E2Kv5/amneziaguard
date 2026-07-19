package com.amneziaguard.core.data.model

/**
 * Per-app firewall mode.
 *
 * [VPN] — traffic goes through the tunnel.
 * [BYPASS] — traffic goes directly, outside the tunnel.
 * [BLOCK] — the app must not reach the network at all (see :core-firewall for
 * the tiered enforcement strategy and its non-root limitations).
 */
enum class AppMode(val id: Int) {
    VPN(0),
    BYPASS(1),
    BLOCK(2);

    companion object {
        fun fromId(id: Int): AppMode = entries.first { it.id == id }
    }
}
