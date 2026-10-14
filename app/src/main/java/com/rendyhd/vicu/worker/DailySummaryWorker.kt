package com.rendyhd.vicu.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rendyhd.vicu.MainActivity
import com.rendyhd.vicu.R
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.DailySummaryReader
import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.data.remote.BaseUrlHolder
import com.rendyhd.vicu.notification.DailySummaryScheduler
import com.rendyhd.vicu.notification.NotificationChannelManager
import com.rendyhd.vicu.util.DailySummary
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.TimeSource
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * Posts one daily summary and queues the next one. The summary is preceded by a sync attempt, so it
 * reports what the server has when the device can reach it, and what the device has when it cannot.
 */
class DailySummaryWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val reader: DailySummaryReader,
    private val notificationPrefsStore: NotificationPrefsStore,
    private val dayClock: DayClock,
    private val time: TimeSource,
    private val syncEngine: SyncEngine,
    private val scheduler: DailySummaryScheduler,
    private val authManager: AuthManager,
    private val baseUrlHolder: BaseUrlHolder,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "DailySummaryWorker"
        private const val NOTIFICATION_ID = 999_999
        private const val NOTIFICATION_ID_AFTERNOON = 999_998
        const val KEY_SLOT = DailySummaryScheduler.KEY_SLOT
        const val SLOT_AFTERNOON = DailySummaryScheduler.SLOT_AFTERNOON

        /** The most a sync attempt may delay the summary. */
        private val SYNC_TIMEOUT = 45.seconds
    }

    override suspend fun doWork(): Result {
        val slot = inputData.getString(KEY_SLOT) ?: DailySummaryScheduler.SLOT_MORNING
        val targetMillis = inputData.getLong(DailySummaryScheduler.KEY_TARGET_MILLIS, 0L)
        Log.d(TAG, "Running daily summary (slot=$slot)")

        DailySummaryRun(
            isSignedIn = {
                baseUrlHolder.ensureInitialized()
                !authManager.getVikunjaUrl().isNullOrBlank()
            },
            syncAttempt = { syncEngine.performSync() },
            syncTimeout = SYNC_TIMEOUT,
            summarize = { postSummary(slot) },
            scheduleNext = { scheduleNext(slot, targetMillis) },
        ).run()
        // Never retry: a late retry would post a duplicate, and the next day's job is already queued.
        return Result.success()
    }

    private suspend fun postSummary(slot: String) {
        val isAfternoon = slot == SLOT_AFTERNOON
        val notificationId = if (isAfternoon) NOTIFICATION_ID_AFTERNOON else NOTIFICATION_ID
        val heading = if (isAfternoon) "Afternoon Summary" else "Daily Summary"

        // The day is re-read first: this may run long after the clock last ticked.
        dayClock.refresh()
        val day = dayClock.day.value
        val prefs = notificationPrefsStore.getPrefs().first()
        val content = reader.read(
            day,
            includeOverdue = prefs.notifyOverdueEnabled,
            includeDueToday = prefs.notifyDueTodayEnabled,
            includeTomorrow = prefs.notifyUpcomingEnabled,
        )
        val counts = content.counts
        if (counts.total == 0) {
            Log.d(TAG, "No tasks to report")
            return
        }

        val tapIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(
            applicationContext,
            notificationId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val style = NotificationCompat.InboxStyle().setBigContentTitle(heading)
        content.dueTodayTitles.forEach { style.addLine(it) }
        val remaining = counts.dueToday - content.dueTodayTitles.size
        if (remaining > 0) {
            style.addLine("+$remaining more today")
        }
        if (counts.overdue > 0) {
            style.addLine("${counts.overdue} overdue tasks need attention")
        }
        if (counts.dueTomorrow > 0) {
            style.addLine("${counts.dueTomorrow} due tomorrow")
        }

        val notification = NotificationCompat.Builder(
            applicationContext,
            NotificationChannelManager.CHANNEL_DAILY_SUMMARY,
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(heading)
            .setContentText(DailySummary.headline(counts))
            .setStyle(style)
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .build()

        try {
            NotificationManagerCompat.from(applicationContext).notify(notificationId, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing POST_NOTIFICATIONS permission", e)
        }
    }

    /** Queues the next occurrence from the current settings; nothing when the summary was switched off. */
    private suspend fun scheduleNext(slot: String, targetMillis: Long) {
        val prefs = notificationPrefsStore.getPrefs().first()
        val afternoon = slot == SLOT_AFTERNOON
        val enabled = if (afternoon) prefs.afternoonSummaryEnabled else prefs.dailySummaryEnabled
        if (!enabled) return
        val hour = if (afternoon) prefs.afternoonSummaryHour else prefs.dailySummaryHour
        val minute = if (afternoon) prefs.afternoonSummaryMinute else prefs.dailySummaryMinute

        // After this run's own due time as well as now: starting a little early must not pick the
        // same time again.
        val after = maxOf(time.now(), Instant.fromEpochMilliseconds(targetMillis))
        scheduler.scheduleNext(slot, hour, minute, after, replaceRunning = targetMillis == 0L)
    }
}
