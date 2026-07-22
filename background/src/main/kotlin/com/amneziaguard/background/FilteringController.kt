package com.amneziaguard.background

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Starts/stops the experimental [FilteringVpnService]. VPN consent is obtained in the UI. */
@Singleton
class FilteringController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun start() {
        context.startService(
            Intent(context, FilteringVpnService::class.java).setAction(FilteringVpnService.ACTION_START),
        )
    }

    fun startRelayTest() {
        context.startService(
            Intent(context, FilteringVpnService::class.java).setAction(FilteringVpnService.ACTION_RELAY_TEST),
        )
    }

    /** Brings up the tunnel through the userspace datapath with per-app rules applied. */
    fun startFirewall() {
        context.startService(
            Intent(context, FilteringVpnService::class.java)
                .setAction(FilteringVpnService.ACTION_START_FIREWALL),
        )
    }

    fun stop() {
        context.startService(
            Intent(context, FilteringVpnService::class.java).setAction(FilteringVpnService.ACTION_STOP),
        )
    }
}
