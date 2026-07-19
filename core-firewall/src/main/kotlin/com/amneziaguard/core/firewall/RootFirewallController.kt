package com.amneziaguard.core.firewall

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional root-backed enforcement of the BLOCK mode using iptables owner-match
 * on the app UIDs. This is the only tier that fully cuts an app off from the
 * network regardless of tunnel state. Absent root, callers fall back to the
 * [GuardVpnService] blackhole (tunnel down) or in-tunnel routing (tunnel up).
 */
@Singleton
class RootFirewallController @Inject constructor() {

    private val chain = "amneziaguard"

    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        Shell.getShell().isRoot
    }

    /** Installs DROP rules for the given UIDs; clears any previous rules first. */
    suspend fun applyBlockedUids(uids: Set<Int>): Boolean = withContext(Dispatchers.IO) {
        if (!Shell.getShell().isRoot) return@withContext false
        val commands = buildList {
            // Recreate the chain idempotently for both IPv4 and IPv6.
            for (bin in listOf("iptables", "ip6tables")) {
                add("$bin -w -D OUTPUT -j $chain 2>/dev/null || true")
                add("$bin -w -F $chain 2>/dev/null || true")
                add("$bin -w -X $chain 2>/dev/null || true")
                add("$bin -w -N $chain 2>/dev/null || true")
                add("$bin -w -I OUTPUT -j $chain")
                for (uid in uids) {
                    add("$bin -w -A $chain -m owner --uid-owner $uid -j DROP")
                }
            }
        }
        Shell.cmd(*commands.toTypedArray()).exec().isSuccess
    }

    /** Removes all AmneziaGuard rules. */
    suspend fun clear(): Boolean = withContext(Dispatchers.IO) {
        if (!Shell.getShell().isRoot) return@withContext false
        val commands = buildList {
            for (bin in listOf("iptables", "ip6tables")) {
                add("$bin -w -D OUTPUT -j $chain 2>/dev/null || true")
                add("$bin -w -F $chain 2>/dev/null || true")
                add("$bin -w -X $chain 2>/dev/null || true")
            }
        }
        Shell.cmd(*commands.toTypedArray()).exec().isSuccess
    }
}
