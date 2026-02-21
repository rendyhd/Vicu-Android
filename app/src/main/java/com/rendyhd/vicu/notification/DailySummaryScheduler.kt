package com.rendyhd.vicu.notification

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.rendyhd.vicu.worker.DailySummaryWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DailySummaryScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "DailySummaryScheduler"
        private const val WORK_NAME = "daily_summary"
    }

    fun scheduleIfEnabled(enabled: Boolean, hour: Int, minute: Int) {
        if (!enabled) {
            cancel()
            return
        }
        schedule(hour, minute)
    }

    fun schedule(hour: Int, minute: Int) {
        val now = LocalDateTime.now()
        var target = now.with(LocalTime.of(hour, minute))
        if (target.isBefore(now) || target.isEqual(now)) {
            target = target.plusDays(1)
        }
        val initialDelay = Duration.between(now, target)

        val request = PeriodicWorkRequestBuilder<DailySummaryWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelay.toMillis(), TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
        Log.d(TAG, "Scheduled daily summary at $hour:$minute (delay=${initialDelay.toMinutes()}min)")
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        Log.d(TAG, "Cancelled daily summary")
    }
}
