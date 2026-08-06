package com.clearpass.app.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.clearpass.app.util.LogCollector
import java.util.concurrent.TimeUnit

object ConfigUpdateScheduler {

    private const val PERIODIC = "clearpass_config_update"
    private const val ONCE = "clearpass_config_update_once"

    fun schedule(context: Context, hours: Long = 3) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val periodic = PeriodicWorkRequestBuilder<ConfigUpdateWorker>(
                hours.coerceIn(1, 24), TimeUnit.HOURS
            ).setConstraints(constraints).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodic
            )

            val once = OneTimeWorkRequestBuilder<ConfigUpdateWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONCE,
                androidx.work.ExistingWorkPolicy.KEEP,
                once
            )

            LogCollector.i("Scheduler", "Periodic ${hours}h + one-shot enqueue")
        } catch (e: Exception) {
            LogCollector.e("Scheduler", e.message ?: "fail")
        }
    }

    fun refreshNow(context: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val once = OneTimeWorkRequestBuilder<ConfigUpdateWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueue(once)
            LogCollector.i("Scheduler", "Manual refresh enqueued")
        } catch (e: Exception) {
            LogCollector.e("Scheduler", e.message ?: "fail")
        }
    }
}
