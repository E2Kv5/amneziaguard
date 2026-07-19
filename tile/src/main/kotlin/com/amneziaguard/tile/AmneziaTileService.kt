package com.amneziaguard.tile

import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.amneziaguard.background.TunnelController
import com.amneziaguard.background.TunnelOrchestrator
import com.amneziaguard.core.data.settings.SettingsRepository
import com.amneziaguard.core.tunnel.TunnelState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: tap toggles the tunnel, long-press opens the app on the
 * server picker. Hilt cannot inject a TileService constructor, so dependencies
 * are pulled from the application component via an [EntryPoint].
 */
class AmneziaTileService : TileService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TileEntryPoint {
        fun orchestrator(): TunnelOrchestrator
        fun controller(): TunnelController
        fun settingsRepository(): SettingsRepository
    }

    private val deps by lazy {
        EntryPointAccessors.fromApplication(applicationContext, TileEntryPoint::class.java)
    }

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = newScope
        deps.orchestrator().state
            .onEach { render(it) }
            .launchIn(newScope)
    }

    override fun onStopListening() {
        scope?.coroutineContext?.get(Job)?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch {
            val settings = deps.settingsRepository().settings.first()
            val serverId = settings.activeServerId
            val needsConsent = VpnService.prepare(applicationContext) != null
            if (serverId == null || needsConsent) {
                openApp()
            } else {
                deps.controller().toggle(serverId)
            }
        }
    }

    private fun render(state: TunnelState) {
        val tile = qsTile ?: return
        when (state) {
            is TunnelState.Up -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Connected"
            }
            TunnelState.Connecting -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Connecting…"
            }
            TunnelState.Down -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Disconnected"
            }
            is TunnelState.Error -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.subtitle = "Error"
            }
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.updateTile()
    }

    private fun openApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return
        startActivityAndCollapseCompat(launch)
    }

    private fun startActivityAndCollapseCompat(intent: Intent) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = android.app.PendingIntent.getActivity(
                this, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
