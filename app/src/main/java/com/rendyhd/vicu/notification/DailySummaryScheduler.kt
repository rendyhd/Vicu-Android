package com.rendyhd.vicu.notification

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.rendyhd.vicu.data.local.NotificationPrefs
import com.rendyhd.vicu.worker.DailySummaryWorker

import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.util.concurrent.TimeUnit

class DailySummaryScheduler(
    private val context: Context,
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
        val timeZone = TimeZone.currentSystemDefault()
        val now = Clock.System.now()
        val localNow = now.toLocalDateTime(timeZone)

        var targetLocal = LocalDateTime(
            localNow.year, localNow.monthNumber, localNow.dayOfMonth,
            hour, minute, 0, 0
        )
        var targetInstant = targetLocal.toInstant(timeZone)
        if (targetInstant <= now) {
            val tomorrow = localNow.date.plus(1, DateTimeUnit.DAY)
            targetInstant = LocalDateTime(
                tomorrow.year, tomorrow.monthNumber, tomorrow.dayOfMonth,
                hour, minute, 0, 0
            ).toInstant(timeZone)
        }
        val initialDelayMillis = (targetInstant - now).inWholeMilliseconds

        val request = PeriodicWorkRequestBuilder<DailySummaryWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("slot" to slot))
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            workName(slot),
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
        Log.d(TAG, "Scheduled $slot daily summary at $hour:$minute (delay=${initialDelayMillis / 60000}min)")
    }

    /** Schedules, or cancels, both summaries as [prefs] says (used after signing in again). */
    fun scheduleFromPrefs(prefs: NotificationPrefs) {
        scheduleIfEnabled(SLOT_MORNING, prefs.dailySummaryEnabled, prefs.dailySummaryHour, prefs.dailySummaryMinute)
        scheduleIfEnabled(
            SLOT_AFTERNOON,
            prefs.afternoonSummaryEnabled,
            prefs.afternoonSummaryHour,
            prefs.afternoonSummaryMinute,
        )
    }

    fun cancel(slot: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(slot))
        Log.d(TAG, "Cancelled $slot daily summary")
    }
}
