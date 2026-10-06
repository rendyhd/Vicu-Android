package com.rendyhd.vicu.widget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.worker.SyncScheduler
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

        val taskRepository = get<TaskRepository>()

        try {
            // Take the row out of this widget at once, before the request is sent, so the tap
            // feels instant. The refresh at the end reads the stored tasks, which now include
            // the completion, so the row does not come back; if the completion failed the
            // refresh shows it again.
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

            // setDone, not a toggle: a second tap on a row that is already being completed must
            // not reopen it. The repository owns the recursive completion and the offline queue.
            // It also cancels the reminders, or schedules the next one of a repeating task.
            when (val result = taskRepository.setDone(taskId, true)) {
                is NetworkResult.Error -> Log.w(TAG, "Could not complete task $taskId: ${result.message}")
                else -> SyncScheduler.enqueueImmediate(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle task $taskId", e)
        }

        // Refresh all widgets from the stored tasks (covers other widget instances showing the
        // same task, and puts the row back if the completion failed)
        WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
    }
}
