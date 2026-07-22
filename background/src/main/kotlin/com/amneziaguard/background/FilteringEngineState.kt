package com.amneziaguard.background

import com.amneziaguard.core.tunnel.TunnelState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State of the userspace datapath, published by [FilteringVpnService] so the
 * rest of the app can treat it exactly like the fast path's state.
 */
@Singleton
class FilteringEngineState @Inject constructor() {
    private val _state = MutableStateFlow<TunnelState>(TunnelState.Down)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    val isActive: Boolean get() = _state.value !is TunnelState.Down

    fun set(state: TunnelState) { _state.value = state }

    /** Cumulative counters, so the UI can show throughput on this path too. */
    data class Traffic(val downloadedBytes: Long, val uploadedBytes: Long)

    @Volatile private var trafficSource: (() -> Traffic)? = null

    fun setTrafficSource(source: (() -> Traffic)?) { trafficSource = source }

    fun traffic(): Traffic? = trafficSource?.invoke()
}
