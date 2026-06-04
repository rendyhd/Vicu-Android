package com.rendyhd.vicu.notification

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.rendyhd.vicu.worker.DailySummaryWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DailySummaryScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "DailySummaryScheduler"
        private const val WORK_NAME_MORNING = "daily_summary"
        private const val WORK_NAME_AFTERNOON = "daily_summary_afternoon"
        const val SLOT_MORNING = "morning"
        const val SLOT_AFTERNOON = "afternoon"
        private fun workName(slot: String) =
            if (slot == SLOT_AFTERNOON) WORK_NAME_AFTERNOON else WORK_NAME_MORNING
    }

    // Backward-compatible morning overloads (keep existing callers compiling)
    fun scheduleIfEnabled(enabled: Boolean, hour: Int, minute: Int) =
        scheduleIfEnabled(SLOT_MORNING, enabled, hour, minute)

    fun schedule(hour: Int, minute: Int) = schedule(SLOT_MORNING, hour, minute)

    fun cancel() = cancel(SLOT_MORNING)

    fun scheduleIfEnabled(slot: String, enabled: Boolean, hour: Int, minute: Int) {
        if (!enabled) {
            cancel(slot)
            return
        }
        schedule(slot, hour, minute)
    }

    fun schedule(slot: String, hour: Int, minute: Int) {
        // Compute the delay in the system zone so the FIRST fire lands on the correct wall-clock
        // time across DST transitions. (The 24h periodic interval re-anchors on each schedule()
        // call — boot, settings change — keeping drift bounded.)
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        var target = now.with(LocalTime.of(hour, minute))
        if (!target.isAfter(now)) target = target.plusDays(1)
        val initialDelay = Duration.between(now, target)

        val request = PeriodicWorkRequestBuilder<DailySummaryWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelay.toMillis(), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("slot" to slot))
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            workName(slot),
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
        Log.d(TAG, "Scheduled $slot daily summary at $hour:$minute (delay=${initialDelay.toMinutes()}min)")
    }

    fun cancel(slot: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(slot))
        Log.d(TAG, "Cancelled $slot daily summary")
    }
}
