package com.amneziaguard.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService

object TunnelNotifications {
    const val CHANNEL_ID = "tunnel"
    const val NOTIFICATION_ID = 1001

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

    fun build(
        context: Context,
        title: String,
        text: String,
        showDisconnect: Boolean,
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
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (showDisconnect) {
            val disconnectIntent = Intent(context, TunnelForegroundService::class.java)
                .setAction(TunnelForegroundService.ACTION_DISCONNECT)
            val disconnectPending = PendingIntent.getService(
                context, 1, disconnectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Disconnect", disconnectPending)
        }
        return builder.build()
    }
}
