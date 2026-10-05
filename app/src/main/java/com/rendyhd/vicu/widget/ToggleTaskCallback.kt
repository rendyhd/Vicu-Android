package com.rendyhd.vicu.widget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.NetworkResult
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

class ToggleTaskCallback : ActionCallback, KoinComponent {

    companion object {
        private const val TAG = "ToggleTaskCallback"
        val TaskIdKey = ActionParameters.Key<Long>("task_id")
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val taskId = parameters[TaskIdKey] ?: return
        Log.d(TAG, "Toggling task $taskId from widget")

        val taskDao = get<TaskDao>()
        val taskMapper = get<TaskMapper>()
        val taskRepository = get<TaskRepository>()

        var pending = false
        try {
            val entity = taskDao.getByIdSync(taskId) ?: return
            val task = with(taskMapper) { entity.toDomain() }

            // Show completion without waiting for the server or descendant updates.
            updateAppWidgetState(
                context,
                TaskWidgetStateDefinition,
                glanceId,
            ) { prefs ->
                val state = TaskWidgetStateDefinition.parseState(prefs)
                val updatedState = state.copy(
                    pendingCompletionIds = state.pendingCompletionIds + taskId,
                ).hidePendingCompletions()
                prefs.toMutablePreferences().apply {
                    this[TaskWidgetStateDefinition.KEY_STATE] =
                        TaskWidgetStateDefinition.encodeState(updatedState)
                }
            }
            pending = true
            TaskListWidget().update(context, glanceId)
            if (taskRepository.toggleDone(task) is NetworkResult.Error) {
                Log.w(TAG, "Task $taskId could not be toggled; refreshing widget state")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle task $taskId", e)
        } finally {
            if (pending) {
                updateAppWidgetState(context, TaskWidgetStateDefinition, glanceId) { prefs ->
                    val state = TaskWidgetStateDefinition.parseState(prefs)
                    prefs.toMutablePreferences().apply {
                        this[TaskWidgetStateDefinition.KEY_STATE] = TaskWidgetStateDefinition.encodeState(
                            state.copy(pendingCompletionIds = state.pendingCompletionIds - taskId),
                        )
                    }
                }
            }
            // Reconcile failures and refresh other instances from Room.
            WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
        }
    }
}
