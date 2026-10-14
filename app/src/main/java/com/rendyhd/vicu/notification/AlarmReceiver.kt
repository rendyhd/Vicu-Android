package com.rendyhd.vicu.notification

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.rendyhd.vicu.MainActivity
import com.rendyhd.vicu.R
import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.data.local.SnoozeStore
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.util.ReminderAlarms
import com.rendyhd.vicu.util.ReminderFireDecision
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class AlarmReceiver : BroadcastReceiver(), KoinComponent {

    companion object {
        private const val TAG = "AlarmReceiver"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TASK_TITLE = "task_title"
        const val EXTRA_IS_SNOOZE = "is_snooze"

        /** The time the alarm was scheduled for; absent on alarms scheduled by older versions. */
        const val EXTRA_TRIGGER_AT_MILLIS = "trigger_at_millis"
    }

    private val prefsStore: NotificationPrefsStore by inject()
    private val snoozeStore: SnoozeStore by inject()
    private val taskDao: TaskDao by inject()
    private val taskMapper: TaskMapper by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, 0L)
        val scheduledTitle = intent.getStringExtra(EXTRA_TASK_TITLE) ?: "Task Reminder"
        val isSnooze = intent.getBooleanExtra(EXTRA_IS_SNOOZE, false)
        val triggerAtMillis = if (intent.hasExtra(EXTRA_TRIGGER_AT_MILLIS)) {
            intent.getLongExtra(EXTRA_TRIGGER_AT_MILLIS, 0L)
        } else {
            null
        }
        Log.d(TAG, "Alarm fired for taskId=$taskId")

        if (isSnooze) {
            runBlocking { snoozeStore.remove(taskId) }
        }

        // The task may have been completed, deleted or re-timed on another device since this
        // alarm was set; show nothing unless it still has the reminder that fired.
        val task = runBlocking {
            taskDao.getByIdSync(taskId)?.let { entity -> with(taskMapper) { entity.toDomain() } }
        }
        val taskTitle = when (val decision = ReminderAlarms.decideFire(task, triggerAtMillis, isSnooze)) {
            is ReminderFireDecision.Show -> decision.title.ifBlank { scheduledTitle }
            is ReminderFireDecision.Skip -> {
                Log.d(TAG, "Stale reminder for taskId=$taskId (${decision.reason}), showing nothing")
                return
            }
        }

        // Check if reminders are enabled
        val prefs = runBlocking { prefsStore.getPrefs().first() }
        if (!prefs.taskRemindersEnabled) {
            Log.d(TAG, "Task reminders disabled, skipping")
            return
        }

        // Tap notification → open task detail
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TASK_ID, taskId)
        }
        val tapPending = PendingIntent.getActivity(
            context,
            taskId.toInt(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // "Mark Complete" action
        val completeIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_COMPLETE
            putExtra(EXTRA_TASK_ID, taskId)
        }
        val completePending = PendingIntent.getBroadcast(
            context,
            taskId.toInt() * 10 + 1,
            completeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // "Snooze 15min" action
        val snoozeIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_SNOOZE
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_TASK_TITLE, taskTitle)
        }
        val snoozePending = PendingIntent.getBroadcast(
            context,
            taskId.toInt() * 10 + 2,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, NotificationChannelManager.CHANNEL_TASK_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(taskTitle)
            .setContentText("Task reminder")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .addAction(0, "Mark Complete", completePending)
            .addAction(0, "Snooze 15min", snoozePending)
            .apply {
                if (!prefs.soundEnabled) {
                    setSilent(true)
                }
            }
            .build()

        try {
            NotificationManagerCompat.from(context).notify(taskId.toInt(), notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing POST_NOTIFICATIONS permission", e)
        }
    }
}
