package com.amneziaguard.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.amneziaguard.core.data.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Reconnects the last active server on boot, if the user opted in. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var controller: TunnelController

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val settings = settingsRepository.settings.first()
                val serverId = settings.activeServerId
                if (settings.connectOnBoot && serverId != null) {
                    controller.connect(serverId)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
