package com.rendyhd.vicu.widget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.remote.interceptor.BaseUrlHolder
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.notification.AlarmScheduler
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.worker.SyncScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

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
        fun pendingActionDao(): PendingActionDao
        fun baseUrlHolder(): BaseUrlHolder
        fun authManager(): AuthManager
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
        val api = entryPoint.api()
        val alarmScheduler = entryPoint.alarmScheduler()
        val pendingActionDao = entryPoint.pendingActionDao()
        val baseUrlHolder = entryPoint.baseUrlHolder()
        val authManager = entryPoint.authManager()
        val json = entryPoint.json()

        var didOptimisticUpdate = false
        try {
            // Ensure network layer is initialized (cold start after process death)
            baseUrlHolder.ensureInitialized()
            authManager.ensureInitializedAndGetToken()

            val entity = taskDao.getByIdSync(taskId) ?: return
            val task = with(taskMapper) { entity.toDomain() }
            val toggled = task.copy(done = true, doneAt = DateUtils.nowIso())

            // Optimistic local update
            val dto = with(taskMapper) { toggled.toDto() }
            val optimisticEntity = with(taskMapper) { dto.toEntity() }
            taskDao.upsert(optimisticEntity)
            didOptimisticUpdate = true

            // Remote update
            try {
                val responseDto = api.updateTask(taskId, dto)
                val responseEntity = with(taskMapper) { responseDto.toEntity() }
                taskDao.upsert(responseEntity)
            } catch (e: CancellationException) {
                Log.w(TAG, "Remote toggle cancelled for task $taskId, queuing for sync", e)
                withContext(NonCancellable) {
                    val action = PendingActionEntity(
                        entityType = "task",
                        entityId = taskId,
                        actionType = "toggle_done",
                        payload = json.encodeToString(Task.serializer(), toggled),
                        createdAt = DateUtils.nowIso(),
                        updatedAt = DateUtils.nowIso(),
                    )
                    pendingActionDao.replaceForEntity("task", taskId, action)
                    SyncScheduler.enqueueWhenOnline(context)
                }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Remote toggle failed for task $taskId, queuing for sync", e)
                val action = PendingActionEntity(
                    entityType = "task",
                    entityId = taskId,
                    actionType = "toggle_done",
                    payload = json.encodeToString(Task.serializer(), toggled),
                    createdAt = DateUtils.nowIso(),
                    updatedAt = DateUtils.nowIso(),
                )
                pendingActionDao.replaceForEntity("task", taskId, action)
                SyncScheduler.enqueueWhenOnline(context)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle task $taskId", e)
        } finally {
            if (didOptimisticUpdate) {
                alarmScheduler.cancelForTask(taskId)
            }
            WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
        }
    }
}
