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

    // TCP asks once per connection, but UDP asks once per *datagram* — and
    // getConnectionOwnerUid is a binder round trip into system_server, orders of
    // magnitude more expensive than everything else the datapath does per packet.
    // A flow's owner cannot change while the flow is alive, so the answer is
    // memoised; the TTL exists only because we can't see the flow close, and it
    // bounds how long a reused 5-tuple could inherit the previous owner.
    private val uidByFlow = ConcurrentHashMap<FlowKey, CachedUid>()

    private class CachedUid(val uid: Int, val resolvedAtMs: Long)

    fun update(rules: Map<String, AppMode>, defaultMode: AppMode) {
        this.rules = rules
        this.defaultMode = defaultMode
        modeByUid.clear()
        // uidByFlow survives: which app owns a flow does not depend on the rules,
        // and re-resolving every live flow would cost a binder storm per edit.
    }

    fun modeFor(flow: FlowKey): AppMode {
        val uid = uidFor(flow)
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

    /**
     * The owning UID, from cache when it was resolved recently. Only successful
     * lookups are cached: an unknown owner falls back to the default mode, and
     * remembering that would keep applying the default to a flow whose socket
     * simply hadn't landed in the kernel's table yet.
     */
    private fun uidFor(flow: FlowKey): Int {
        val now = System.currentTimeMillis()
        uidByFlow[flow]?.let { if (now - it.resolvedAtMs < UID_TTL_MS) return it.uid }

        val uid = uidResolver.uidFor(flow)
        if (uid != INVALID_UID) {
            if (uidByFlow.size >= MAX_CACHED_FLOWS) prune(now)
            uidByFlow[flow] = CachedUid(uid, now)
        }
        return uid
    }

    /** Drops expired flows; if they were all fresh, starts over rather than grow. */
    private fun prune(now: Long) {
        uidByFlow.entries.removeAll { now - it.value.resolvedAtMs >= UID_TTL_MS }
        if (uidByFlow.size >= MAX_CACHED_FLOWS) uidByFlow.clear()
    }

    private val AppMode.strictness: Int
        get() = when (this) {
            AppMode.BLOCK -> 0
            AppMode.VPN -> 1
            AppMode.BYPASS -> 2
        }

    private companion object {
        const val TAG = "AGEngine"

        // One second still collapses a QUIC flow's thousands of datagrams per
        // second into a single lookup, while keeping the window in which a
        // recycled source port could be attributed to its previous owner short.
        const val UID_TTL_MS = 1_000L
        const val MAX_CACHED_FLOWS = 4_096
    }
}
