package com.amneziaguard.background

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin entry point the UI and tile use to drive the tunnel. It starts the
 * foreground service with the right action so foreground-service start
 * restrictions are respected.
 */
@Singleton
class TunnelController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun connect(serverId: Long) = startService(
        TunnelForegroundService.ACTION_CONNECT,
        serverId,
    )

    fun disconnect() = startService(TunnelForegroundService.ACTION_DISCONNECT, null)

    fun toggle(serverId: Long?) = startService(
        TunnelForegroundService.ACTION_TOGGLE,
        serverId,
    )

    private fun startService(action: String, serverId: Long?) {
        val intent = Intent(context, TunnelForegroundService::class.java).setAction(action)
        serverId?.let { intent.putExtra(TunnelForegroundService.EXTRA_SERVER_ID, it) }
        ContextCompat.startForegroundService(context, intent)
    }
}
