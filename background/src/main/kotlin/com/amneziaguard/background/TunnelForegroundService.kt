package com.amneziaguard.background

import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import com.amneziaguard.core.data.repo.ServerRepository
import com.amneziaguard.core.tunnel.TunnelState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps the tunnel alive and shows an ongoing
 * notification. Declared with foregroundServiceType `specialUse` (subtype
 * "vpn"); the actual VPN interface is owned by the library's own VpnService.
 */
@AndroidEntryPoint
class TunnelForegroundService : LifecycleService() {

    @Inject lateinit var orchestrator: TunnelOrchestrator
    @Inject lateinit var serverRepository: ServerRepository

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat(
            TunnelNotifications.build(
                this, "AmneziaGuard", "Starting…",
                disconnect = TunnelNotifications.disconnectFastPath(this),
            ),
        )
        orchestrator.state
            .onEach { updateNotification(it) }
            .launchIn(lifecycleScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_CONNECT -> {
                val serverId = intent.getLongExtra(EXTRA_SERVER_ID, -1L)
                if (serverId >= 0) orchestrator.connect(serverId)
            }
            ACTION_DISCONNECT -> {
                orchestrator.disconnect()
                stopSelf()
            }
            ACTION_TOGGLE -> {
                val serverId = intent.getLongExtra(EXTRA_SERVER_ID, -1L)
                if (serverId >= 0) {
                    orchestrator.toggle(serverId)
                } else {
                    lifecycleScope.launch { toggleActiveServer() }
                }
            }
        }
        return START_STICKY
    }

    private suspend fun toggleActiveServer() {
        if (orchestrator.state.value.isActive) {
            orchestrator.disconnect()
            stopSelf()
        }
    }

    private fun updateNotification(state: TunnelState) {
        val (title, text) = when (state) {
            TunnelState.Connecting -> "AmneziaGuard" to "Connecting…"
            is TunnelState.Up -> "AmneziaGuard" to "Connected"
            is TunnelState.Error -> "AmneziaGuard" to "Error: ${state.message}"
            TunnelState.Down -> {
                stopSelf()
                return
            }
        }
        startForegroundCompat(
            TunnelNotifications.build(
                this, title, text,
                disconnect = TunnelNotifications.disconnectFastPath(this),
            ),
        )
    }

    /**
     * The specialUse foreground-service type only exists since API 34, so the
     * versioned call is guarded directly (rather than via a computed type int),
     * which also satisfies the NewApi lint check.
     */
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                TunnelNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(TunnelNotifications.NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_CONNECT = "com.amneziaguard.action.CONNECT"
        const val ACTION_DISCONNECT = "com.amneziaguard.action.DISCONNECT"
        const val ACTION_TOGGLE = "com.amneziaguard.action.TOGGLE"
        const val EXTRA_SERVER_ID = "server_id"
    }
}
