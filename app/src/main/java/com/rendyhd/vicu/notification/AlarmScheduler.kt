package com.rendyhd.vicu.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import com.rendyhd.vicu.util.DateUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskDao: TaskDao,
    private val taskMapper: TaskMapper,
) {
    companion object {
        private const val TAG = "AlarmScheduler"
    }

    private val alarmManager: AlarmManager
        get() = context.getSystemService(AlarmManager::class.java)

    fun scheduleForTask(task: Task) {
        cancelForTask(task.id)

        if (task.done || task.reminders.isEmpty()) return

        task.reminders.forEachIndexed { index, reminder ->
            val triggerAtMillis = resolveReminderTime(reminder, task.dueDate)
            if (triggerAtMillis != null && triggerAtMillis > System.currentTimeMillis()) {
                scheduleAlarm(task.id, task.title, index, triggerAtMillis)
            }
        }
    }

    fun cancelForTask(taskId: Long) {
        // Cancel up to 100 potential reminders per task
        for (i in 0 until 100) {
            val requestCode = taskId.toInt() * 100 + i
            val intent = Intent(context, AlarmReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (pending != null) {
                alarmManager.cancel(pending)
                pending.cancel()
            }
        }
    }

    suspend fun rescheduleAll() {
        Log.d(TAG, "rescheduleAll() start")
        try {
            val entities = taskDao.getAllWithReminders()
            entities.forEach { entity ->
                val task = with(taskMapper) { entity.toDomain() }
                scheduleForTask(task)
            }
            Log.d(TAG, "rescheduleAll() scheduled for ${entities.size} tasks")
        } catch (e: Exception) {
            Log.e(TAG, "rescheduleAll() failed", e)
        }
    }

    /**
     * Schedule a single alarm for snooze (absolute time).
     */
    fun scheduleSnooze(taskId: Long, taskTitle: String, triggerAtMillis: Long) {
        // Use index 99 for snooze to avoid colliding with regular reminders
        scheduleAlarm(taskId, taskTitle, 99, triggerAtMillis)
    }

    private fun scheduleAlarm(taskId: Long, taskTitle: String, reminderIndex: Int, triggerAtMillis: Long) {
        val requestCode = taskId.toInt() * 100 + reminderIndex
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId)
            putExtra(AlarmReceiver.EXTRA_TASK_TITLE, taskTitle)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.w(TAG, "Cannot schedule exact alarms — permission not granted")
            return
        }

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            pending,
        )
        Log.d(TAG, "Scheduled alarm taskId=$taskId index=$reminderIndex at $triggerAtMillis")
    }

    private fun resolveReminderTime(reminder: TaskReminder, dueDate: String): Long? {
        // Absolute reminder: reminder field has a valid ISO timestamp
        if (reminder.reminder.isNotBlank()) {
            val instant = DateUtils.parseIsoDate(reminder.reminder)
            if (instant != null) return instant.toEpochMilli()
        }

        // Relative reminder: offset from due_date (or start_date/end_date via relativeTo)
        if (reminder.relativePeriod != 0L) {
            val baseDate = DateUtils.parseIsoDate(dueDate)
            if (baseDate != null) {
                // relativePeriod is in seconds, negative = before due date
                val triggerInstant = baseDate.plus(Duration.ofSeconds(reminder.relativePeriod))
                return triggerInstant.toEpochMilli()
            }
        }

        return null
    }
}
