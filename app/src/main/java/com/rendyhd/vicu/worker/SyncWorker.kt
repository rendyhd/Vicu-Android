package com.rendyhd.vicu.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rendyhd.vicu.data.local.dao.LabelDao
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.mapper.LabelMapper
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.api.LabelTaskDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.notification.AlarmScheduler
import com.rendyhd.vicu.util.Constants
import com.rendyhd.vicu.util.isRetriableNetworkError
import com.rendyhd.vicu.widget.WidgetUpdateScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.Json

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val pendingActionDao: PendingActionDao,
    private val taskDao: TaskDao,
    private val labelDao: LabelDao,
    private val api: VikunjaApiService,
    private val taskMapper: TaskMapper,
    private val labelMapper: LabelMapper,
    private val alarmScheduler: AlarmScheduler,
    private val json: Json,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SyncWorker"
        private const val MAX_RETRIES = 5
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "SyncWorker started")
        var hasRetriableFailures = false

        try {
            val actions = pendingActionDao.getRetryable()
            Log.d(TAG, "Processing ${actions.size} pending actions")

            for (action in actions) {
                pendingActionDao.updateStatus(action.id, "processing")
                try {
                    processAction(action)
                    pendingActionDao.updateStatus(action.id, "completed")
                    Log.d(TAG, "Action ${action.id} (${action.entityType}/${action.actionType}) completed")
                } catch (e: Exception) {
                    Log.e(TAG, "Action ${action.id} failed: ${e.message}", e)
                    if (isRetriableNetworkError(e) && action.retryCount < MAX_RETRIES) {
                        pendingActionDao.updateStatus(action.id, "pending", action.retryCount + 1)
                        hasRetriableFailures = true
                    } else {
                        pendingActionDao.updateStatus(action.id, "failed")
                    }
                }
            }

            pendingActionDao.deleteCompleted()

            // Full refresh from server
            refreshAllFromServer()

            WidgetUpdateScheduler.enqueueImmediateUpdateAll(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "SyncWorker failed: ${e.message}", e)
            return Result.retry()
        }

        return if (hasRetriableFailures) Result.retry() else Result.success()
    }

    private suspend fun processAction(action: PendingActionEntity) {
        when (action.entityType) {
            "task" -> processTaskAction(action)
            "label" -> processLabelAction(action)
            else -> Log.w(TAG, "Unknown entity type: ${action.entityType}")
        }
    }

    private suspend fun processTaskAction(action: PendingActionEntity) {
        when (action.actionType) {
            "create" -> {
                val task = json.decodeFromString<Task>(action.payload)
                val createDto = with(taskMapper) { task.toCreateDto() }
                val responseDto = api.createTask(task.projectId, createDto)
                val responseEntity = with(taskMapper) { responseDto.toEntity() }
                // Delete the temp-ID entity and insert the real one
                taskDao.deleteById(action.entityId)
                taskDao.upsert(responseEntity)
                val created = with(taskMapper) { responseEntity.toDomain() }
                alarmScheduler.scheduleForTask(created)
            }
            "update", "toggle_done" -> {
                val task = json.decodeFromString<Task>(action.payload)
                val dto = with(taskMapper) { task.toDto() }
                val responseDto = api.updateTask(task.id, dto)
                val responseEntity = with(taskMapper) { responseDto.toEntity() }
                taskDao.upsert(responseEntity)
                val updated = with(taskMapper) { responseEntity.toDomain() }
                if (updated.done) {
                    alarmScheduler.cancelForTask(updated.id)
                } else {
                    alarmScheduler.scheduleForTask(updated)
                }
            }
            "delete" -> {
                api.deleteTask(action.entityId)
            }
        }
    }

    private suspend fun processLabelAction(action: PendingActionEntity) {
        when (action.actionType) {
            "create" -> {
                val label = json.decodeFromString<Label>(action.payload)
                val dto = with(labelMapper) { label.toDto() }
                val responseDto = api.createLabel(dto)
                val entity = with(labelMapper) { responseDto.toEntity() }
                // Delete temp-ID entity and insert real one
                labelDao.deleteById(action.entityId)
                labelDao.upsert(entity)
            }
            "update" -> {
                val label = json.decodeFromString<Label>(action.payload)
                val dto = with(labelMapper) { label.toDto() }
                val responseDto = api.updateLabel(label.id, dto)
                val entity = with(labelMapper) { responseDto.toEntity() }
                labelDao.upsert(entity)
            }
            "delete" -> {
                api.deleteLabel(action.entityId)
            }
            "add_label" -> {
                val parts = action.payload.split(":")
                val taskId = parts[0].toLong()
                val labelId = parts[1].toLong()
                api.addLabelToTask(taskId, LabelTaskDto(labelId = labelId))
            }
            "remove_label" -> {
                val parts = action.payload.split(":")
                val taskId = parts[0].toLong()
                val labelId = parts[1].toLong()
                api.removeLabelFromTask(taskId, labelId)
            }
        }
    }

    private suspend fun refreshAllFromServer() {
        try {
            // Refresh tasks
            val allTasks = mutableListOf<com.rendyhd.vicu.data.remote.api.TaskDto>()
            var page = 1
            while (true) {
                val params = mapOf(
                    "filter" to "done = false",
                    "page" to page.toString(),
                    "per_page" to Constants.DEFAULT_PAGE_SIZE.toString(),
                )
                val batch = api.getAllTasks(params)
                allTasks.addAll(batch)
                if (batch.size < Constants.DEFAULT_PAGE_SIZE) break
                page++
            }
            val taskEntities = allTasks.map { with(taskMapper) { it.toEntity() } }
            taskDao.upsertAll(taskEntities)
            alarmScheduler.rescheduleAll()
            Log.d(TAG, "Refreshed ${taskEntities.size} tasks from server")

            // Refresh labels
            val labelDtos = api.getAllLabels()
            val labelEntities = labelDtos.map { with(labelMapper) { it.toEntity() } }
            labelDao.upsertAll(labelEntities)
            Log.d(TAG, "Refreshed ${labelEntities.size} labels from server")
        } catch (e: Exception) {
            Log.e(TAG, "Server refresh failed: ${e.message}", e)
        }
    }
}
