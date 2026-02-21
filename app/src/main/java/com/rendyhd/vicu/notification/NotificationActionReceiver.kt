package com.rendyhd.vicu.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.util.DateUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotifActionReceiver"
        const val ACTION_COMPLETE = "com.rendyhd.vicu.ACTION_COMPLETE"
        const val ACTION_SNOOZE = "com.rendyhd.vicu.ACTION_SNOOZE"
    }

    @Inject lateinit var taskDao: TaskDao
    @Inject lateinit var taskMapper: TaskMapper
    @Inject lateinit var api: VikunjaApiService
    @Inject lateinit var alarmScheduler: AlarmScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(AlarmReceiver.EXTRA_TASK_ID, 0L)
        if (taskId == 0L) return

        // Dismiss the notification
        NotificationManagerCompat.from(context).cancel(taskId.toInt())

        when (intent.action) {
            ACTION_COMPLETE -> handleComplete(taskId)
            ACTION_SNOOZE -> handleSnooze(context, taskId, intent)
        }
    }

    private fun handleComplete(taskId: Long) {
        Log.d(TAG, "Completing task $taskId")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val entity = taskDao.getByIdSync(taskId) ?: return@launch
                val task = with(taskMapper) { entity.toDomain() }
                val toggled = task.copy(done = true, doneAt = DateUtils.nowIso())
                val dto = with(taskMapper) { toggled.toDto() }
                api.updateTask(taskId, dto)
                val responseEntity = with(taskMapper) { dto.toEntity() }
                taskDao.upsert(responseEntity)
                alarmScheduler.cancelForTask(taskId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to complete task $taskId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleSnooze(context: Context, taskId: Long, intent: Intent) {
        val taskTitle = intent.getStringExtra(AlarmReceiver.EXTRA_TASK_TITLE) ?: "Task Reminder"
        val triggerAt = System.currentTimeMillis() + 15 * 60 * 1000
        Log.d(TAG, "Snoozing task $taskId for 15 minutes")
        alarmScheduler.scheduleSnooze(taskId, taskTitle, triggerAt)
    }
}
