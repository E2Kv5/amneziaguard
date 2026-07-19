package com.amneziaguard.core.tunnel

/** High-level tunnel state exposed to the UI, tile and orchestrator. */
sealed interface TunnelState {
    data object Down : TunnelState
    data object Connecting : TunnelState
    data class Up(val serverId: Long, val sinceEpochMs: Long) : TunnelState
    data class Error(val message: String) : TunnelState

    val isActive: Boolean get() = this is Up
}
