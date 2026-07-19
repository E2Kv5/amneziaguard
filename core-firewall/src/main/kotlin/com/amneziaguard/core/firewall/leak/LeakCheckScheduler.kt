package com.amneziaguard.core.firewall.leak

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LeakCheckScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun schedulePeriodic(intervalHours: Long) {
        val request = PeriodicWorkRequestBuilder<LeakCheckWorker>(
            intervalHours.coerceAtLeast(1), TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            LeakCheckWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** Runs an immediate, expedited leak check (the Security "Check now" button). */
    fun runOnce() {
        val request = OneTimeWorkRequestBuilder<LeakCheckWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
