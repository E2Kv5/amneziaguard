package com.amneziaguard.core.firewall.leak

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.amneziaguard.core.data.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Periodic DNS/IP leak self-test. Runs only while the tunnel is expected to be
 * up; on a detected leak it posts a high-priority notification and records the
 * latest result (no history is kept — no-logs policy).
 */
@HiltWorker
class LeakCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val leakCheckApi: LeakCheckApi,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = settingsRepository.settings.first()
        if (!settings.dnsLeakProtection || settings.activeServerId == null) {
            return Result.success()
        }

        val result = leakCheckApi.check().getOrElse {
            return Result.retry()
        }

        val leaking = result.dnsLeak == true
        settingsRepository.setLastLeakCheck(ok = !leaking, atEpochMs = System.currentTimeMillis())
        if (leaking) notifyLeak(result.exitIp)
        return Result.success()
    }

    private fun notifyLeak(exitIp: String?) {
        val manager = applicationContext.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Leak alerts", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val text = buildString {
            append("DNS/IP leak detected")
            if (exitIp != null) append(" (exit IP $exitIp)")
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("AmneziaGuard")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val UNIQUE_NAME = "leak-check"
        private const val CHANNEL_ID = "leak"
        private const val NOTIFICATION_ID = 2001
    }
}
