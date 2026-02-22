package com.rendyhd.vicu.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rendyhd.vicu.MainActivity
import com.rendyhd.vicu.R
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.notification.NotificationChannelManager
import com.rendyhd.vicu.util.DateUtils
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class DailySummaryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val taskDao: TaskDao,
    private val taskMapper: TaskMapper,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "DailySummaryWorker"
        private const val NOTIFICATION_ID = 999_999
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Running daily summary")
        return try {
            val startOfToday = DateUtils.todayStartIso()
            val endOfToday = DateUtils.getEndOfToday()

            val overdueCount = taskDao.countOverdue(startOfToday)
            val todayCount = taskDao.countDueToday(startOfToday, endOfToday)
            val upcomingCount = taskDao.countUpcoming(endOfToday)

            val total = overdueCount + todayCount + upcomingCount
            if (total == 0) {
                Log.d(TAG, "No tasks to report")
                return Result.success()
            }

            val todayTasks = taskDao.getTodayTasksSync(endOfToday, 3)
                .map { with(taskMapper) { it.toDomain() } }

            val tapIntent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val tapPending = PendingIntent.getActivity(
                applicationContext,
                NOTIFICATION_ID,
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
                .setBigContentTitle("Daily Summary")

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
                .setContentTitle("Daily Summary")
                .setContentText(title)
                .setStyle(style)
                .setAutoCancel(true)
                .setContentIntent(tapPending)
                .build()

            try {
                NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
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
