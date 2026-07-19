package com.amneziaguard.core.firewall

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Starts/stops the [GuardVpnService] blackhole. */
@Singleton
class GuardController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** BLOCK mode: capture (blackhole) exactly these apps. */
    fun startBlocking(packages: Set<String>) {
        val intent = Intent(context, GuardVpnService::class.java)
            .setAction(GuardVpnService.ACTION_START)
            .putStringArrayListExtra(GuardVpnService.EXTRA_ALLOWED, ArrayList(packages))
        context.startService(intent)
    }

    /** Kill-switch mode: capture everyone except [exemptPackages]. */
    fun startKillSwitch(exemptPackages: Set<String>) {
        val intent = Intent(context, GuardVpnService::class.java)
            .setAction(GuardVpnService.ACTION_START)
            .putStringArrayListExtra(GuardVpnService.EXTRA_EXEMPT, ArrayList(exemptPackages))
        context.startService(intent)
    }

    fun stop() {
        val intent = Intent(context, GuardVpnService::class.java)
            .setAction(GuardVpnService.ACTION_STOP)
        context.startService(intent)
    }
}
