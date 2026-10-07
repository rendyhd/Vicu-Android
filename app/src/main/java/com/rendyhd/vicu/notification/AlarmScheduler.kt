package com.rendyhd.vicu.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.rendyhd.vicu.data.local.ReminderAlarmRegistry
import com.rendyhd.vicu.data.local.SnoozeEntry
import com.rendyhd.vicu.data.local.SnoozeStore
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.ReminderAlarmBackend
import com.rendyhd.vicu.util.ReminderAlarmCoordinator
import com.rendyhd.vicu.util.ReminderAlarmSpec
import com.rendyhd.vicu.util.SnoozeRules
import com.rendyhd.vicu.util.TimeSource
import kotlinx.coroutines.CancellationException

/**
 * Registers task reminder alarms with AlarmManager. Which alarms exist is tracked in
 * [ReminderAlarmRegistry], so an alarm can be cancelled exactly (completed or deleted task,
 * removed reminder, sign-out) instead of probing request codes. The pure rules live in
 * [com.rendyhd.vicu.util.ReminderAlarms]; [AlarmReceiver] re-checks the task when an alarm fires.
 */
class AlarmScheduler(
    private val context: Context,
    private val taskDao: TaskDao,
    private val taskMapper: TaskMapper,
    private val snoozeStore: SnoozeStore,
    registry: ReminderAlarmRegistry,
    private val time: TimeSource,
) {
    companion object {
        private const val TAG = "AlarmScheduler"
    }

    private val alarmManager: AlarmManager
        get() = context.getSystemService(AlarmManager::class.java)

    private val coordinator = ReminderAlarmCoordinator(
        registry = registry,
        backend = object : ReminderAlarmBackend {
            override fun schedule(alarm: ReminderAlarmSpec, taskTitle: String) =
                registerReminderAlarm(alarm, taskTitle)

            override fun cancel(requestCode: Int) = cancelReminderAlarm(requestCode)
        },
        nowMillis = { time.now().toEpochMilliseconds() },
    )

    /** Replaces the alarms of [task] with the ones its reminders call for right now. */
    suspend fun scheduleForTask(task: Task) = coordinator.scheduleForTask(task)

    suspend fun rescheduleAll() {
        Log.d(TAG, "rescheduleAll() start")
        try {
            val entities = taskDao.getAllWithReminders()
            val tasks = entities.map { entity -> with(taskMapper) { entity.toDomain() } }
            // A full pass also cancels alarms of tasks that are no longer open with reminders.
            coordinator.reconcileAll(tasks)
            Log.d(TAG, "rescheduleAll() reconciled ${tasks.size} tasks")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "rescheduleAll() failed", e)
        }
    }

    /**
     * After a sync: brings the alarms of [changed] tasks in line and cancels those of
     * [removedTaskIds], one registry pass for the whole batch. A task that is done or gone also
     * loses a pending snooze.
     */
    suspend fun updateAlarms(changed: List<Task>, removedTaskIds: Set<Long>) {
        coordinator.reconcileTasks(changed, removedTaskIds)
        changed.filter { it.done }.forEach { cancelSnooze(it.id) }
        removedTaskIds.forEach { cancelSnooze(it) }
    }

    /** Cancels everything for a task, including a pending snooze (task done/deleted). */
    suspend fun cancelForTask(taskId: Long) {
        coordinator.cancelForTask(taskId)
        cancelSnooze(taskId)
    }

    /** Sign-out: cancels every reminder alarm and every pending snooze. */
    suspend fun cancelAll() {
        coordinator.cancelAll()
        snoozeStore.all().forEach { cancelSnooze(it.taskId) }
    }

    /** The "Snooze" notification action: fires the reminder again one snooze period from now. */
    suspend fun snooze(taskId: Long, taskTitle: String) {
        scheduleSnooze(taskId, taskTitle, SnoozeRules.triggerAfterSnooze(time.now().toEpochMilliseconds()))
    }

    suspend fun scheduleSnooze(taskId: Long, taskTitle: String, triggerAtMillis: Long) {
        snoozeStore.put(SnoozeEntry(taskId, taskTitle, triggerAtMillis))
        registerSnoozeAlarm(taskId, taskTitle, triggerAtMillis)
    }

    suspend fun cancelSnooze(taskId: Long) {
        val pending = PendingIntent.getBroadcast(
            context,
            snoozeRequestCode(taskId),
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pending != null) {
            alarmManager.cancel(pending)
            pending.cancel()
        }
        snoozeStore.remove(taskId)
    }

    /** Re-registers persisted snoozes after a reboot; past-due ones fire one minute out. */
    suspend fun rescheduleSnoozes() {
        val now = time.now().toEpochMilliseconds()
        snoozeStore.all().forEach { entry ->
            registerSnoozeAlarm(entry.taskId, entry.title, SnoozeRules.triggerAfterRestore(entry.triggerAtMillis, now))
        }
    }

    /** Snoozes live in their own request-code space so the reminder sweep can't hit them. */
    private fun snoozeRequestCode(taskId: Long): Int = "snooze:$taskId".hashCode()

    private fun registerSnoozeAlarm(taskId: Long, taskTitle: String, triggerAtMillis: Long) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId)
            putExtra(AlarmReceiver.EXTRA_TASK_TITLE, taskTitle)
            putExtra(AlarmReceiver.EXTRA_IS_SNOOZE, true)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            snoozeRequestCode(taskId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            return
        }
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
    }

    private fun cancelReminderAlarm(requestCode: Int) {
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pending != null) {
            alarmManager.cancel(pending)
            pending.cancel()
        }
    }

    private fun registerReminderAlarm(alarm: ReminderAlarmSpec, taskTitle: String) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(AlarmReceiver.EXTRA_TASK_ID, alarm.taskId)
            putExtra(AlarmReceiver.EXTRA_TASK_TITLE, taskTitle)
            // The receiver checks that the task still has a reminder resolving to this time.
            putExtra(AlarmReceiver.EXTRA_TRIGGER_AT_MILLIS, alarm.triggerAtMillis)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            alarm.requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            // Without the exact-alarm permission, fall back to an inexact alarm so the
            // reminder still fires (approximately) instead of silently dying. The Settings
            // banner prompts the user to grant exact alarms for precise timing.
            Log.w(TAG, "Exact alarms not permitted - scheduling inexact fallback")
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.triggerAtMillis, pending)
            return
        }

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            alarm.triggerAtMillis,
            pending,
        )
        Log.d(TAG, "Scheduled alarm taskId=${alarm.taskId} index=${alarm.reminderIndex} at ${alarm.triggerAtMillis}")
    }
}
