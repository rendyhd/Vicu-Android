package com.rendyhd.vicu.notification

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.rendyhd.vicu.data.local.NotificationPrefs
import com.rendyhd.vicu.util.DailySummary
import com.rendyhd.vicu.util.TimeSource
import com.rendyhd.vicu.worker.DailySummaryWorker
import kotlinx.datetime.Instant
import java.util.concurrent.TimeUnit

/**
 * Schedules the daily summaries. Each summary is a one-time job for the next local occurrence of
 * its configured time, and the job schedules the following day's when it has run. A repeating
 * 24 hour job would drift by an hour across a daylight-saving change.
 */
class DailySummaryScheduler(
    private val context: Context,
    private val time: TimeSource,
) {
    companion object {
        private const val TAG = "DailySummaryScheduler"
        private const val WORK_NAME_MORNING = "daily_summary"
        private const val WORK_NAME_AFTERNOON = "daily_summary_afternoon"
        const val SLOT_MORNING = "morning"
        const val SLOT_AFTERNOON = "afternoon"
        const val KEY_SLOT = "slot"

        /** The instant the job was scheduled to run at; absent on a run of the old repeating job. */
        const val KEY_TARGET_MILLIS = "target_millis"

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

    /** Replaces whatever is queued for [slot] with the next occurrence of [hour]:[minute] from now. */
    fun schedule(slot: String, hour: Int, minute: Int) {
        enqueue(slot, hour, minute, after = time.now(), policy = ExistingWorkPolicy.REPLACE)
    }

    /**
     * Called by the job itself once it has run: queues the next occurrence after [after] (the later
     * of now and the time this run was due, so an early start cannot pick the same time again).
     * [replaceRunning] is for the last run of the old repeating job, which has to be replaced
     * rather than appended to.
     */
    fun scheduleNext(slot: String, hour: Int, minute: Int, after: Instant, replaceRunning: Boolean) {
        enqueue(
            slot, hour, minute, after,
            policy = if (replaceRunning) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
        )
    }

    private fun enqueue(slot: String, hour: Int, minute: Int, after: Instant, policy: ExistingWorkPolicy) {
        val now = time.now()
        // The local wall-clock time of the next day is computed from the calendar date in the
        // current zone, so it lands on the configured time across daylight-saving changes.
        val target = DailySummary.nextOccurrence(after, time.zone(), hour, minute)
        val initialDelayMillis = (target - now).inWholeMilliseconds.coerceAtLeast(0L)

        val request = OneTimeWorkRequestBuilder<DailySummaryWorker>()
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_SLOT to slot, KEY_TARGET_MILLIS to target.toEpochMilliseconds()))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(workName(slot), policy, request)
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
