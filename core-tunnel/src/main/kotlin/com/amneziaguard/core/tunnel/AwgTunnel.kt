package com.amneziaguard.core.tunnel

import org.amnezia.awg.backend.Tunnel

/**
 * Adapts an AmneziaWG backend tunnel to a callback. The name must satisfy the
 * library's constraint `[a-zA-Z0-9_=+.-]{1,15}`, so we derive it from the
 * server id rather than the user-visible server name.
 */
class AwgTunnel(
    private val serverId: Long,
    private val onState: (Tunnel.State) -> Unit,
) : Tunnel {

    override fun getName(): String = "awg$serverId".take(15)

    override fun onStateChange(newState: Tunnel.State) {
        onState(newState)
    }

    override fun isIpv4ResolutionPreferred(): Boolean = true

    override fun isMetered(): Boolean = false
}
