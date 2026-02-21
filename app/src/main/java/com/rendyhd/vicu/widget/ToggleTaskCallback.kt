package com.rendyhd.vicu.widget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.notification.AlarmScheduler
import com.rendyhd.vicu.util.DateUtils
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

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
        fun api(): VikunjaApiService
        fun alarmScheduler(): AlarmScheduler
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
        val api = entryPoint.api()
        val alarmScheduler = entryPoint.alarmScheduler()

        try {
            val entity = taskDao.getByIdSync(taskId) ?: return
            val task = with(taskMapper) { entity.toDomain() }
            val toggled = task.copy(done = true, doneAt = DateUtils.nowIso())

            // Optimistic local update
            val dto = with(taskMapper) { toggled.toDto() }
            val optimisticEntity = with(taskMapper) { dto.toEntity() }
            taskDao.upsert(optimisticEntity)

            // Remote update
            try {
                val responseDto = api.updateTask(taskId, dto)
                val responseEntity = with(taskMapper) { responseDto.toEntity() }
                taskDao.upsert(responseEntity)
            } catch (e: Exception) {
                Log.w(TAG, "Remote toggle failed for task $taskId, local update kept", e)
            }

            alarmScheduler.cancelForTask(taskId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle task $taskId", e)
        }

        // Refresh all widgets
        WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
    }
}
