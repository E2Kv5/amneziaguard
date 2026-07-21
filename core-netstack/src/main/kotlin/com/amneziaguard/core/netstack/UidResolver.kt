package com.amneziaguard.core.netstack

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.core.content.getSystemService
import com.amneziaguard.core.netstack.packet.FlowKey
import com.amneziaguard.core.netstack.packet.IpProtocol
import java.net.InetAddress
import java.net.InetSocketAddress

/** Maps a captured connection to the owning app's UID. */
interface UidResolver {
    /** The owning UID, or [android.os.Process.INVALID_UID] if unknown. */
    fun uidFor(flow: FlowKey): Int
}

/**
 * Android implementation backed by [ConnectivityManager.getConnectionOwnerUid]
 * (API 29+). This is the no-root mechanism for per-app filtering inside a
 * VpnService — the VpnService owns the captured connections, so it may query
 * their owning UID. On API 26-28 there is no equivalent, so it returns unknown
 * and the caller falls back to the default policy.
 */
class ConnectivityUidResolver(context: Context) : UidResolver {

    private val connectivityManager = context.getSystemService<ConnectivityManager>()

    override fun uidFor(flow: FlowKey): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return INVALID_UID
        val cm = connectivityManager ?: return INVALID_UID
        val protocol = when (flow.protocol) {
            IpProtocol.TCP -> IPPROTO_TCP
            IpProtocol.UDP -> IPPROTO_UDP
            else -> return INVALID_UID
        }
        return runCatching {
            cm.getConnectionOwnerUid(
                protocol,
                InetSocketAddress(InetAddress.getByAddress(flow.sourceIp), flow.sourcePort),
                InetSocketAddress(InetAddress.getByAddress(flow.destIp), flow.destPort),
            )
        }.getOrDefault(INVALID_UID)
    }

    private companion object {
        const val IPPROTO_TCP = 6
        const val IPPROTO_UDP = 17
        const val INVALID_UID = -1
    }
}

const val INVALID_UID = -1
