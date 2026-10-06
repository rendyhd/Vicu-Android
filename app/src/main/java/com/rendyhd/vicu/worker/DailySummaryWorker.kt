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
import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.notification.NotificationChannelManager
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.DueDates
import kotlinx.coroutines.flow.first

class DailySummaryWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val taskDao: TaskDao,
    private val taskMapper: TaskMapper,
    private val notificationPrefsStore: NotificationPrefsStore,
    private val dayClock: DayClock,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "DailySummaryWorker"
        private const val NOTIFICATION_ID = 999_999
        private const val NOTIFICATION_ID_AFTERNOON = 999_998
        const val KEY_SLOT = "slot"
        const val SLOT_AFTERNOON = "afternoon"
    }

    override suspend fun doWork(): Result {
        val isAfternoon = inputData.getString(KEY_SLOT) == SLOT_AFTERNOON
        val notificationId = if (isAfternoon) NOTIFICATION_ID_AFTERNOON else NOTIFICATION_ID
        val heading = if (isAfternoon) "Afternoon Summary" else "Daily Summary"
        Log.d(TAG, "Running daily summary (afternoon=$isAfternoon)")
        return try {
            // Overdue is before the start of today, due today is the local day, upcoming is from
            // the start of tomorrow: the same boundaries as the Today and Upcoming screens.
            dayClock.refresh()
            val day = dayClock.day.value
            val startOfToday = DueDates.startOfDay(day.date, day.zone).toString()
            val startOfTomorrow = DueDates.startOfTomorrow(day.date, day.zone).toString()

            val prefs = notificationPrefsStore.getPrefs().first()
            val overdueCount = if (prefs.notifyOverdueEnabled) taskDao.countOverdue(startOfToday) else 0
            val todayCount = if (prefs.notifyDueTodayEnabled) taskDao.countDueToday(startOfToday, startOfTomorrow) else 0
            val upcomingCount = if (prefs.notifyUpcomingEnabled) taskDao.countUpcoming(startOfTomorrow) else 0

            val total = overdueCount + todayCount + upcomingCount
            if (total == 0) {
                Log.d(TAG, "No tasks to report")
                return Result.success()
            }

            // Today-only (excludes overdue, which is summarized separately below).
            val todayTasks = taskDao.getDueTodaySync(startOfToday, startOfTomorrow, 3)
                .map { with(taskMapper) { it.toDomain() } }

            val tapIntent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val tapPending = PendingIntent.getActivity(
                applicationContext,
                notificationId,
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val title = buildString {
                val parts = mutableListOf<String>()
                if (overdueCount > 0) parts.add("$overdueCount overdue")
                if (todayCount > 0) parts.add("$todayCount today")
                if (upcomingCount > 0) parts.add("$upcomingCount upcoming")
                append(parts.joinToString(", "))
            }

            val style = NotificationCompat.InboxStyle()
                .setBigContentTitle(heading)

            todayTasks.forEach { task ->
                style.addLine(task.title)
            }
            val remaining = todayCount - todayTasks.size
            if (remaining > 0) {
                style.addLine("+$remaining more today")
            }
            if (overdueCount > 0) {
                style.addLine("$overdueCount overdue tasks need attention")
            }

            val notification = NotificationCompat.Builder(
                applicationContext,
                NotificationChannelManager.CHANNEL_DAILY_SUMMARY,
            )
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(heading)
                .setContentText(title)
                .setStyle(style)
                .setAutoCancel(true)
                .setContentIntent(tapPending)
                .build()

            try {
                NotificationManagerCompat.from(applicationContext).notify(notificationId, notification)
            } catch (e: SecurityException) {
                Log.w(TAG, "Missing POST_NOTIFICATIONS permission", e)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Daily summary failed", e)
            Result.retry()
        }
    }
}
