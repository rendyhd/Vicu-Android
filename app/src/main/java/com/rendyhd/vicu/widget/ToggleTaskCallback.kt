package com.rendyhd.vicu.widget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.notification.AlarmScheduler
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.worker.SyncScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json

class ToggleTaskCallback : ActionCallback {

    companion object {
        private const val TAG = "ToggleTaskCallback"
        val TaskIdKey = ActionParameters.Key<Long>("task_id")
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ToggleEntryPoint {
        fun taskDao(): TaskDao
        fun taskMapper(): TaskMapper
        fun alarmScheduler(): AlarmScheduler
        fun pendingActionDao(): PendingActionDao
        fun json(): Json
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val taskId = parameters[TaskIdKey] ?: return
        Log.d(TAG, "Toggling task $taskId from widget")

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ToggleEntryPoint::class.java,
        )
        val taskDao = entryPoint.taskDao()
        val taskMapper = entryPoint.taskMapper()
        val alarmScheduler = entryPoint.alarmScheduler()
        val pendingActionDao = entryPoint.pendingActionDao()
        val json = entryPoint.json()

        try {
            val entity = taskDao.getByIdSync(taskId) ?: return
            val task = with(taskMapper) { entity.toDomain() }
            val toggled = task.copy(done = true, doneAt = DateUtils.nowIso())

            // 1. Optimistic local update (Room)
            val dto = with(taskMapper) { toggled.toDto() }
            val optimisticEntity = with(taskMapper) { dto.toEntity() }
            taskDao.upsert(optimisticEntity)

            // 2. Immediately update this widget's Glance state (remove the task)
            updateAppWidgetState(
                context,
                TaskWidgetStateDefinition,
                glanceId,
            ) { prefs ->
                val state = TaskWidgetStateDefinition.parseState(prefs)
                val updatedState = state.copy(
                    tasks = state.tasks.filter { it.id != taskId },
                    totalCount = (state.totalCount - 1).coerceAtLeast(0),
                )
                prefs.toMutablePreferences().apply {
                    this[TaskWidgetStateDefinition.KEY_STATE] =
                        TaskWidgetStateDefinition.encodeState(updatedState)
                }
            }
            TaskListWidget().update(context, glanceId)

            // 3. Queue pending action for background sync
            val action = PendingActionEntity(
                entityType = "task",
                entityId = taskId,
                actionType = "toggle_done",
                payload = json.encodeToString(Task.serializer(), toggled),
                createdAt = DateUtils.nowIso(),
                updatedAt = DateUtils.nowIso(),
            )
            pendingActionDao.replaceForEntity("task", taskId, action)

            // 4. Cancel reminders + schedule background sync
            alarmScheduler.cancelForTask(taskId)
            SyncScheduler.enqueueImmediate(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle task $taskId", e)
        }

        // Refresh all widgets (covers other widget instances showing the same task)
        WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
    }
}
