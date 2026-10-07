package com.rendyhd.vicu.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.remote.BaseUrlHolder
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.widget.WidgetUpdateScheduler
import com.rendyhd.vicu.worker.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class NotificationActionReceiver : BroadcastReceiver(), KoinComponent {

    companion object {
        private const val TAG = "NotifActionReceiver"
        const val ACTION_COMPLETE = "com.rendyhd.vicu.ACTION_COMPLETE"
        const val ACTION_SNOOZE = "com.rendyhd.vicu.ACTION_SNOOZE"
    }

    private val alarmScheduler: AlarmScheduler by inject()
    private val taskRepository: TaskRepository by inject()
    private val baseUrlHolder: BaseUrlHolder by inject()
    private val authManager: AuthManager by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(AlarmReceiver.EXTRA_TASK_ID, 0L)
        if (taskId == 0L) return

        // Dismiss the notification
        NotificationManagerCompat.from(context).cancel(taskId.toInt())

        when (intent.action) {
            ACTION_COMPLETE -> handleComplete(context, taskId)
            ACTION_SNOOZE -> handleSnooze(context, taskId, intent)
        }
    }

    private fun handleComplete(context: Context, taskId: Long) {
        Log.d(TAG, "Completing task $taskId")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Ensure network layer is initialized (cold start after process death)
                baseUrlHolder.ensureInitialized()
                authManager.ensureInitializedAndGetToken()

                // Explicit completion, never a toggle: if sync already stored the task as done
                // (completed on another device) this must leave it done, not reopen it.
                taskRepository.setDone(taskId, true)
                SyncScheduler.enqueueWhenOnline(context)

                alarmScheduler.cancelForTask(taskId)
                WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to complete task $taskId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleSnooze(context: Context, taskId: Long, intent: Intent) {
        val taskTitle = intent.getStringExtra(AlarmReceiver.EXTRA_TASK_TITLE) ?: "Task Reminder"
        Log.d(TAG, "Snoozing task $taskId for 15 minutes")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                alarmScheduler.snooze(taskId, taskTitle)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
