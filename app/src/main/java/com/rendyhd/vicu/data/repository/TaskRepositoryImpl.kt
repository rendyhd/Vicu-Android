package com.rendyhd.vicu.data.repository

import android.content.Context
import android.util.Log
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.notification.AlarmScheduler
import com.rendyhd.vicu.util.Constants
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.isRetriableNetworkError
import com.rendyhd.vicu.widget.WidgetUpdateScheduler
import com.rendyhd.vicu.worker.SyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskDao: TaskDao,
    private val api: VikunjaApiService,
    private val pendingActionDao: PendingActionDao,
    private val taskMapper: TaskMapper,
    private val alarmScheduler: AlarmScheduler,
    private val json: Json,
) : TaskRepository {

    companion object {
        private const val TAG = "TaskRepoImpl"
    }

    private val tempIdCounter = AtomicLong(-(System.currentTimeMillis() / 1000))

    private suspend fun queueTaskAction(entityId: Long, actionType: String, payload: String) {
        val action = PendingActionEntity(
            entityType = "task",
            entityId = entityId,
            actionType = actionType,
            payload = payload,
            createdAt = DateUtils.nowIso(),
            updatedAt = DateUtils.nowIso(),
        )
        if (actionType == "create") {
            pendingActionDao.insert(action)
        } else {
            pendingActionDao.replaceForEntity("task", entityId, action)
        }
        SyncScheduler.enqueueWhenOnline(context)
    }

    override fun getInboxTasks(inboxProjectId: Long): Flow<List<Task>> =
        taskDao.getInboxTasks(inboxProjectId).map { entities ->
            entities.map { with(taskMapper) { it.toDomain() } }
        }

    override fun getTodayTasks(): Flow<List<Task>> =
        taskDao.getTodayTasks(DateUtils.getEndOfToday()).map { entities ->
            entities.map { with(taskMapper) { it.toDomain() } }
        }

    override fun getUpcomingTasks(): Flow<List<Task>> =
        taskDao.getUpcomingTasks(DateUtils.getEndOfToday()).map { entities ->
            entities.map { with(taskMapper) { it.toDomain() } }
        }

    override fun getAnytimeTasks(inboxProjectId: Long): Flow<List<Task>> =
        taskDao.getAnytimeTasks(inboxProjectId).map { entities ->
            entities.map { with(taskMapper) { it.toDomain() } }
        }

    override fun getLogbookTasks(): Flow<List<Task>> =
        taskDao.getLogbookTasks().map { entities ->
            entities.map { with(taskMapper) { it.toDomain() } }
        }

    override fun getByProjectId(projectId: Long): Flow<List<Task>> =
        taskDao.getByProjectId(projectId).map { entities ->
            entities.map { with(taskMapper) { it.toDomain() } }
        }

    override fun getById(id: Long): Flow<Task?> =
        taskDao.getById(id).map { entity ->
            entity?.let { with(taskMapper) { it.toDomain() } }
        }

    override fun searchByTitle(query: String): Flow<List<Task>> =
        taskDao.searchByTitle(query).map { entities ->
            entities.map { with(taskMapper) { it.toDomain() } }
        }

    override fun getAllOpenTasks(): Flow<List<Task>> =
        taskDao.getAllOpenTasks().map { entities ->
            entities.map { with(taskMapper) { it.toDomain() } }
        }

    override suspend fun create(task: Task): NetworkResult<Task> {
        return try {
            val createDto = with(taskMapper) { task.toCreateDto() }
            val responseDto = api.createTask(task.projectId, createDto)
            val responseEntity = with(taskMapper) { responseDto.toEntity() }
            taskDao.upsert(responseEntity)
            val created = with(taskMapper) { responseEntity.toDomain() }
            alarmScheduler.scheduleForTask(created)
            WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
            NetworkResult.Success(created)
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
                val tempId = tempIdCounter.decrementAndGet()
                val localTask = task.copy(
                    id = tempId,
                    created = DateUtils.nowIso(),
                    updated = DateUtils.nowIso(),
                )
                val dto = with(taskMapper) { localTask.toDto() }
                val entity = with(taskMapper) { dto.toEntity() }
                taskDao.upsert(entity)
                queueTaskAction(tempId, "create", json.encodeToString(Task.serializer(), localTask))
                WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
                NetworkResult.Success(localTask)
            } else {
                NetworkResult.Error(e.localizedMessage ?: "Failed to create task")
            }
        }
    }

    override suspend fun update(task: Task): NetworkResult<Task> {
        return try {
            // Optimistic local update
            val dto = with(taskMapper) { task.toDto() }
            val optimisticEntity = with(taskMapper) { dto.toEntity() }
            taskDao.upsert(optimisticEntity)

            // Remote update — send complete object (Go zero-value problem)
            val responseDto = api.updateTask(task.id, dto)
            val responseEntity = with(taskMapper) { responseDto.toEntity() }
            taskDao.upsert(responseEntity)

            val updated = with(taskMapper) { responseEntity.toDomain() }
            alarmScheduler.scheduleForTask(updated)
            WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
            NetworkResult.Success(updated)
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
                // Optimistic write already done above
                queueTaskAction(task.id, "update", json.encodeToString(Task.serializer(), task))
                WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
                NetworkResult.Success(task)
            } else {
                NetworkResult.Error(e.localizedMessage ?: "Failed to update task")
            }
        }
    }

    override suspend fun delete(taskId: Long): NetworkResult<Unit> {
        return try {
            alarmScheduler.cancelForTask(taskId)
            // Optimistic local delete
            taskDao.deleteById(taskId)
            api.deleteTask(taskId)
            WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
                // Local delete already done
                queueTaskAction(taskId, "delete", "")
                WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
                NetworkResult.Success(Unit)
            } else {
                NetworkResult.Error(e.localizedMessage ?: "Failed to delete task")
            }
        }
    }

    override suspend fun createSubtask(parentTaskId: Long, subtask: Task): NetworkResult<Task> {
        return try {
            val createDto = with(taskMapper) { subtask.toCreateDto() }
            val createdDto = api.createTask(subtask.projectId, createDto)
            val createdEntity = with(taskMapper) { createdDto.toEntity() }
            taskDao.upsert(createdEntity)

            // Create relation: parent "subtask" -> child
            api.createRelation(
                parentTaskId,
                com.rendyhd.vicu.data.remote.api.CreateRelationDto(
                    otherTaskId = createdDto.id,
                    relationKind = "subtask",
                ),
            )

            // Re-fetch parent to get updated relatedTasks
            val parentDto = api.getTask(parentTaskId)
            val parentEntity = with(taskMapper) { parentDto.toEntity() }
            taskDao.upsert(parentEntity)

            NetworkResult.Success(with(taskMapper) { createdEntity.toDomain() })
        } catch (e: Exception) {
            NetworkResult.Error(e.localizedMessage ?: "Failed to create subtask")
        }
    }

    override suspend fun deleteRelation(
        taskId: Long,
        relationKind: String,
        otherTaskId: Long,
    ): NetworkResult<Unit> {
        return try {
            api.deleteRelation(taskId, relationKind, otherTaskId)
            // Re-fetch to get updated relatedTasks
            val dto = api.getTask(taskId)
            val entity = with(taskMapper) { dto.toEntity() }
            taskDao.upsert(entity)
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            NetworkResult.Error(e.localizedMessage ?: "Failed to delete relation")
        }
    }

    override suspend fun toggleDone(task: Task): NetworkResult<Task> {
        return try {
            val toggled = task.copy(
                done = !task.done,
                doneAt = if (!task.done) DateUtils.nowIso() else "",
            )
            // Don't update Room yet — let the ViewModel show strikethrough via
            // completedTaskIds so the task stays visible for undo.
            // On refresh, the ViewModel calls deleteLocalByIds() to clean up.
            val dto = with(taskMapper) { toggled.toDto() }
            val responseDto = api.updateTask(task.id, dto)
            val responseEntity = with(taskMapper) { responseDto.toEntity() }
            val result = with(taskMapper) { responseEntity.toDomain() }
            if (toggled.done) {
                alarmScheduler.cancelForTask(task.id)
            } else {
                alarmScheduler.scheduleForTask(result)
            }
            WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
            NetworkResult.Success(result)
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
                val toggled = task.copy(
                    done = !task.done,
                    doneAt = if (!task.done) DateUtils.nowIso() else "",
                )
                // Persist toggled state to Room for offline
                val dto = with(taskMapper) { toggled.toDto() }
                val entity = with(taskMapper) { dto.toEntity() }
                taskDao.upsert(entity)
                queueTaskAction(task.id, "toggle_done", json.encodeToString(Task.serializer(), toggled))
                if (toggled.done) {
                    alarmScheduler.cancelForTask(task.id)
                }
                WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
                NetworkResult.Success(toggled)
            } else {
                NetworkResult.Error(e.localizedMessage ?: "Failed to toggle task")
            }
        }
    }

    override suspend fun deleteLocalByIds(ids: Set<Long>) {
        ids.forEach { taskDao.deleteById(it) }
    }

    override suspend fun refreshAll(filters: Map<String, String>): NetworkResult<Unit> {
        Log.d(TAG, "refreshAll() called with filters=$filters")
        return try {
            val allTasks = mutableListOf<com.rendyhd.vicu.data.remote.api.TaskDto>()
            var page = 1
            while (true) {
                val params = buildMap {
                    if (!filters.containsKey("filter")) put("filter", "done = false")
                    putAll(filters)
                    put("page", page.toString())
                    put("per_page", Constants.DEFAULT_PAGE_SIZE.toString())
                }
                Log.d(TAG, "refreshAll() fetching page=$page params=$params")
                val batch = api.getAllTasks(params)
                Log.d(TAG, "refreshAll() page=$page returned ${batch.size} tasks")
                allTasks.addAll(batch)
                if (batch.size < Constants.DEFAULT_PAGE_SIZE) break
                page++
            }
            val entities = allTasks.map { with(taskMapper) { it.toEntity() } }
            taskDao.upsertAll(entities)
            alarmScheduler.rescheduleAll()
            Log.d(TAG, "refreshAll() SUCCESS: upserted ${entities.size} total tasks")
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "refreshAll() FAILED: ${e.message}", e)
            NetworkResult.Error(e.localizedMessage ?: "Failed to refresh tasks")
        }
    }
}
