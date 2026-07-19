package com.amneziaguard.background

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Re-resolves peer endpoints (DDNS) when the default network changes so the
 * tunnel keeps roaming across Wi-Fi <-> mobile without a restart.
 */
@Singleton
class NetworkChangeMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val orchestrator: TunnelOrchestrator,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (orchestrator.state.value.isActive) {
                scope.launch { orchestrator.onNetworkChanged() }
            }
        }
    }

    fun start() {
        if (registered) return
        val cm = context.getSystemService<ConnectivityManager>() ?: return
        cm.registerDefaultNetworkCallback(callback)
        registered = true
    }

    fun stop() {
        if (!registered) return
        context.getSystemService<ConnectivityManager>()?.unregisterNetworkCallback(callback)
        registered = false
    }
}
