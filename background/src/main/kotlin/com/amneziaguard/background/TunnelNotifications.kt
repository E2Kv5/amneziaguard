package com.amneziaguard.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import java.util.Locale

object TunnelNotifications {
    const val CHANNEL_ID = "tunnel"
    const val NOTIFICATION_ID = 1001
    const val ENGINE_NOTIFICATION_ID = 1002

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN status",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Builds the ongoing tunnel notification. [disconnect] is supplied by the
     * caller because each datapath is stopped by a different service.
     */
    fun build(
        context: Context,
        title: String,
        text: String,
        disconnect: PendingIntent? = null,
    ): Notification {
        ensureChannel(context)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        disconnect?.let { builder.addAction(0, "Disconnect", it) }
        return builder.build()
    }

    /** Stops the fast path (the library-backed tunnel). */
    fun disconnectFastPath(context: Context): PendingIntent = PendingIntent.getService(
        context, 1,
        Intent(context, TunnelForegroundService::class.java)
            .setAction(TunnelForegroundService.ACTION_DISCONNECT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Stops the userspace filtering engine. */
    fun disconnectEngine(context: Context): PendingIntent = PendingIntent.getService(
        context, 2,
        Intent(context, FilteringVpnService::class.java)
            .setAction(FilteringVpnService.ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Updates an already-posted notification in place. */
    fun update(context: Context, id: Int, notification: Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    fun formatRate(bytesPerSecond: Long): String {
        if (bytesPerSecond < 1024) return "$bytesPerSecond B/s"
        val units = listOf("KB/s", "MB/s", "GB/s")
        var value = bytesPerSecond.toDouble() / 1024
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}
