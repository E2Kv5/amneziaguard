package com.amneziaguard.core.firewall

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.amneziaguard.core.data.model.AppMode
import com.amneziaguard.core.netstack.ConnectivityUidResolver
import com.amneziaguard.core.netstack.INVALID_UID
import com.amneziaguard.core.netstack.packet.FlowKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers "which app owns this flow, and what may it do?" for the userspace
 * datapath — the piece that finally makes the BLOCK mode work without root
 * *while the tunnel is up*.
 *
 * The owning UID comes from `ConnectivityManager.getConnectionOwnerUid`. A flow
 * whose owner can't be determined (a kernel-originated probe, say) falls back to
 * the default mode rather than being dropped.
 */
@Singleton
class UidPolicyResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val uidResolver = ConnectivityUidResolver(context)
    private val packageManager: PackageManager get() = context.packageManager

    @Volatile private var rules: Map<String, AppMode> = emptyMap()
    @Volatile private var defaultMode: AppMode = AppMode.VPN

    // A UID's mode is stable until the rules change, and the flow lookup happens
    // on the read loop, so caching keeps per-connection work to one binder call.
    private val modeByUid = ConcurrentHashMap<Int, AppMode>()

    fun update(rules: Map<String, AppMode>, defaultMode: AppMode) {
        this.rules = rules
        this.defaultMode = defaultMode
        modeByUid.clear()
    }

    fun modeFor(flow: FlowKey): AppMode {
        val uid = uidResolver.uidFor(flow)
        if (uid == INVALID_UID) return defaultMode
        modeByUid[uid]?.let { return it }

        val packages = runCatching { packageManager.getPackagesForUid(uid) }.getOrNull().orEmpty()
        // Shared UIDs can host several packages; the strictest rule wins so a
        // blocked app can't reach the network through a sibling's identity.
        val mode = packages.mapNotNull { rules[it] }.minByOrNull { it.strictness } ?: defaultMode
        modeByUid[uid] = mode
        if (mode == AppMode.BLOCK) {
            Log.d(TAG, "uid $uid (${packages.joinToString()}) is BLOCKed")
        }
        return mode
    }

    private val AppMode.strictness: Int
        get() = when (this) {
            AppMode.BLOCK -> 0
            AppMode.VPN -> 1
            AppMode.BYPASS -> 2
        }

    private companion object {
        const val TAG = "AGEngine"
    }
}
