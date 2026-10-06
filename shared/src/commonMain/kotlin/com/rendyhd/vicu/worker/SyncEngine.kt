package com.rendyhd.vicu.worker

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.dao.LabelDao
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.dao.ProjectDao
import com.rendyhd.vicu.data.local.dao.normalizeQueuedPatchPayload
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.mapper.LabelMapper
import com.rendyhd.vicu.data.mapper.ProjectMapper
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.api.LabelTaskDto
import com.rendyhd.vicu.data.remote.api.MergePatches
import com.rendyhd.vicu.data.remote.api.TaskDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.remote.BaseUrlHolder
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.CustomListSyncStatus
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.domain.repository.CustomListRepository
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.isRetriableNetworkError
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.RoutineEnvelope
import com.rendyhd.vicu.util.CustomListEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration.Companion.seconds

fun remapLabelTaskPayload(payload: String, tempId: Long, realId: Long): String? {
    val parts = payload.split(":")
    if (parts.size != 2) return null
    val taskId = parts[0].toLongOrNull() ?: return null
    val labelId = parts[1].toLongOrNull() ?: return null
    if (taskId != tempId && labelId != tempId) return null
    return "${if (taskId == tempId) realId else taskId}:${if (labelId == tempId) realId else labelId}"
}

class SyncEngine(
    private val pendingActionDao: PendingActionDao,
    private val taskDao: TaskDao,
    private val labelDao: LabelDao,
    private val projectDao: ProjectDao,
    private val api: VikunjaApiService,
    private val taskMapper: TaskMapper,
    private val labelMapper: LabelMapper,
    private val projectMapper: ProjectMapper,
    private val platformHooks: PlatformRepositoryHooks,
    private val json: Json,
    private val baseUrlHolder: BaseUrlHolder,
    private val authManager: AuthManager,
    private val customListRepository: CustomListRepository,
) {
    companion object {
        private const val TAG = "SyncEngine"
        private const val MAX_RETRIES = 5
        private const val DUPLICATE_WINDOW_SECS = 900L
        private val TASK_DEPENDENT_ACTIONS = setOf("update", "toggle_done", "delete")
        private val LABEL_TASK_ACTIONS = setOf("add_label", "remove_label")

        /**
         * One sync at a time for the whole process. The queue has two unique works ("when online"
         * and "immediate", which REPLACEs) plus any future entry point, and two runs would each
         * start by resetting the other's in-flight actions to pending and then replay them
         * (duplicate creates, duplicate deletes). Companion-level so it does not depend on how
         * many engine instances exist.
         */
        private val syncMutex = Mutex()
    }

    suspend fun performSync(): Boolean = syncMutex.withLock { performSyncLocked() }

    /** Must only run while holding [syncMutex]. */
    private suspend fun performSyncLocked(): Boolean {
        Logger.d(TAG, "SyncEngine started")

        baseUrlHolder.ensureInitialized()
        authManager.ensureInitializedAndGetToken()

        // Safe only because no other run can be mid-action while we hold the lock: whatever is
        // still "processing" belongs to a run that was killed or cancelled.
        pendingActionDao.resetProcessingToPending()

        var hasRetriableFailures = false

        try {
            val actions = pendingActionDao.getRetryable()
                .sortedBy { if (it.entityType == "task" && it.actionType == "create") 0 else 1 }
            Logger.d(TAG, "Processing ${actions.size} pending actions")
            val tempIdMap = mutableMapOf<Long, Long>()

            for (action in actions) {
                pendingActionDao.updateStatus(action.id, "processing")
                try {
                    processAction(action, tempIdMap)
                    pendingActionDao.updateStatus(action.id, "completed")
                    Logger.d(TAG, "Action ${action.id} (${action.entityType}/${action.actionType}) completed")
                } catch (e: Exception) {
                    if (e is CancellationException && !currentCoroutineContext().isActive) {
                        // The run itself was cancelled (for example replaced by an immediate
                        // sync). That says nothing about the action: put it back untouched and
                        // stop. A CancellationException from a nested timeout in a still-active
                        // run is an ordinary failure and falls through.
                        withContext(NonCancellable) { pendingActionDao.updateStatus(action.id, "pending") }
                        throw e
                    }
                    Logger.e(TAG, "Action ${action.id} failed: ${e.message}", e)
                    if (isRetriableNetworkError(e) && action.retryCount < MAX_RETRIES) {
                        pendingActionDao.updateStatus(action.id, "pending", action.retryCount + 1)
                        hasRetriableFailures = true
                    } else {
                        pendingActionDao.updateStatus(action.id, "failed")
                    }
                }
            }

            pendingActionDao.deleteCompleted()

            when (customListRepository.sync()) {
                CustomListSyncStatus.Pending,
                is CustomListSyncStatus.Offline -> hasRetriableFailures = true
                else -> Unit
            }
            refreshAllFromServer()
        } catch (e: Exception) {
            Logger.e(TAG, "SyncEngine failed: ${e.message}", e)
            throw e
        } finally {
            platformHooks.updateWidgets()
        }

        return !hasRetriableFailures
    }

    private suspend fun processAction(action: PendingActionEntity, tempIdMap: MutableMap<Long, Long>) {
        when (action.entityType) {
            "task" -> processTaskAction(action, tempIdMap)
            "label" -> processLabelAction(action, tempIdMap)
            else -> Logger.w(TAG, "Unknown entity type: ${action.entityType}")
        }
    }

    private suspend fun findRecentDuplicate(task: Task): TaskDto? = try {
        val queuedAt = DateUtils.parseIsoDate(task.created)
        val routineId = RoutineEnvelope.parse(task.description, json).payload?.definition?.id
        if (queuedAt == null) {
            null
        } else {
            api.getAllTasks(mapOf("q" to task.title, "filter" to "project_id = ${task.projectId}"))
                .firstOrNull { dto ->
                    val dt = DateUtils.parseIsoDate(dto.created)
                    dto.title == task.title &&
                        dto.projectId == task.projectId &&
                        dt != null && dt > queuedAt - DUPLICATE_WINDOW_SECS.seconds &&
                        if (routineId != null) {
                            RoutineEnvelope.parse(dto.description, json).payload?.definition?.id == routineId
                        } else {
                            !CustomListEnvelope.isAnyMetadataTask(dto.description)
                        }
                }
        }
    } catch (e: Exception) {
        null
    }

    private suspend fun processTaskAction(action: PendingActionEntity, tempIdMap: MutableMap<Long, Long>) {
        when (action.actionType) {
            "create" -> {
                val task = json.decodeFromString<Task>(action.payload)
                val responseDto = findRecentDuplicate(task)
                    ?: api.createTask(task.projectId, with(taskMapper) { task.toCreateDto() })
                val responseEntity = with(taskMapper) { responseDto.toEntity() }
                taskDao.deleteById(action.entityId)
                taskDao.upsert(responseEntity)

                var finalEntity = responseEntity
                if (task.done) {
                    val toggled = with(taskMapper) { responseEntity.toDomain() }.copy(
                        done = true,
                        doneAt = task.doneAt.ifBlank { DateUtils.nowIso() },
                    )
                    val doneDto = api.updateTask(
                        responseEntity.id,
                        MergePatches.taskDone(done = true),
                    )
                    finalEntity = with(taskMapper) { doneDto.toEntity() }
                    taskDao.upsert(finalEntity)
                }
                val created = with(taskMapper) { finalEntity.toDomain() }
                if (created.done) {
                    platformHooks.cancelAlarm(created.id)
                } else {
                    platformHooks.scheduleAlarm(created)
                }
                if (action.entityId != responseEntity.id) {
                    tempIdMap[action.entityId] = responseEntity.id
                    remapPendingDependents(action.entityId, responseEntity.id)
                }
            }
            "update", "toggle_done" -> {
                val taskId = tempIdMap[action.entityId] ?: action.entityId
                var patch = json.decodeFromString(
                    JsonObject.serializer(),
                    normalizeQueuedPatchPayload("task", action.payload),
                )
                val localEntity = taskDao.getByIdSync(taskId)
                if (localEntity != null && RoutineEnvelope.hasMarker(localEntity.description)) {
                    val localTask = with(taskMapper) { localEntity.toDomain() }
                    val localParsed = RoutineEnvelope.parse(localTask.description, json)
                    val remoteTask = with(taskMapper) { api.getTask(taskId).toEntity().toDomain() }
                    val remoteParsed = RoutineEnvelope.parse(remoteTask.description, json)
                    if (localParsed.payload != null && remoteParsed.payload != null) {
                        val mergedPayload = RoutineEnvelope.mergePayload(localParsed.payload, remoteParsed.payload)
                        val mergedTask = remoteTask.copy(
                            title = mergedPayload.definition.name,
                            description = RoutineEnvelope.upsert(remoteParsed.body, mergedPayload, json),
                            done = true,
                            dueDate = "",
                            repeatAfter = 0,
                            repeatMode = 0,
                            reminders = emptyList(),
                        )
                        patch = MergePatches.task(previous = null, current = mergedTask)
                    }
                }
                val responseDto = api.updateTask(taskId, patch)
                val responseEntity = with(taskMapper) { responseDto.toEntity() }
                taskDao.upsert(responseEntity)
                val updated = with(taskMapper) { responseEntity.toDomain() }
                if (updated.done) {
                    platformHooks.cancelAlarm(updated.id)
                } else {
                    platformHooks.scheduleAlarm(updated)
                }
            }
            "delete" -> {
                api.deleteTask(tempIdMap[action.entityId] ?: action.entityId)
            }
        }
    }

    private suspend fun processLabelAction(action: PendingActionEntity, tempIdMap: MutableMap<Long, Long>) {
        when (action.actionType) {
            "create" -> {
                val label = json.decodeFromString<Label>(action.payload)
                val dto = with(labelMapper) { label.toCreateDto() }
                val responseDto = api.createLabel(dto)
                val entity = with(labelMapper) { responseDto.toEntity() }
                labelDao.deleteById(action.entityId)
                labelDao.upsert(entity)
                if (action.entityId != responseDto.id) {
                    tempIdMap[action.entityId] = responseDto.id
                    remapPendingDependents(action.entityId, responseDto.id)
                }
            }
            "update" -> {
                val patch = json.decodeFromString(
                    JsonObject.serializer(),
                    normalizeQueuedPatchPayload("label", action.payload),
                )
                val responseDto = api.updateLabel(action.entityId, patch)
                val entity = with(labelMapper) { responseDto.toEntity() }
                labelDao.upsert(entity)
            }
            "delete" -> {
                api.deleteLabel(action.entityId)
            }
            "add_label" -> {
                val parts = action.payload.split(":")
                val taskId = parts[0].toLong().let { tempIdMap[it] ?: it }
                val labelId = parts[1].toLong().let { tempIdMap[it] ?: it }
                api.addLabelToTask(taskId, LabelTaskDto(labelId = labelId))
            }
            "remove_label" -> {
                val parts = action.payload.split(":")
                val taskId = parts[0].toLong().let { tempIdMap[it] ?: it }
                val labelId = parts[1].toLong().let { tempIdMap[it] ?: it }
                api.removeLabelFromTask(taskId, labelId)
            }
        }
    }

    private suspend fun remapPendingDependents(tempId: Long, realId: Long) {
        for (a in pendingActionDao.getRemappable()) {
            when {
                a.entityType == "task" && a.entityId == tempId &&
                    a.actionType in TASK_DEPENDENT_ACTIONS -> {
                    pendingActionDao.remapEntity(a.id, realId, a.payload, "pending")
                }
                a.entityType == "label" && a.actionType in LABEL_TASK_ACTIONS -> {
                    remapLabelTaskPayload(a.payload, tempId, realId)?.let { newPayload ->
                        pendingActionDao.remapEntity(a.id, a.entityId, newPayload, "pending")
                    }
                }
            }
        }
    }

    private suspend fun refreshAllFromServer() {
        try {
            val allTasks = api.getAllTasks()
            val visibleTasks = allTasks.filterNot { CustomListEnvelope.hasMarker(it.description) }
            val taskEntities = visibleTasks.map { with(taskMapper) { it.toEntity() } }
            val pendingTaskIds = pendingActionDao.getTaskIdsWithPendingActions().toSet()
            val existingById = taskDao.getAllSync().associateBy { it.id }
            val safeEntities = taskEntities.filter { it.id !in pendingTaskIds }
            val changed = safeEntities.filter { existingById[it.id] != it }
            taskDao.upsertAll(changed)
            var routinesTouched = changed.any { entity ->
                RoutineEnvelope.hasMarker(entity.description) ||
                    RoutineEnvelope.hasMarker(existingById[entity.id]?.description)
            }
            var alarmsTouched = changed.any { e ->
                val old = existingById[e.id]
                old == null || old.remindersJson != e.remindersJson ||
                    old.dueDate != e.dueDate || old.done != e.done
            }
            val serverTaskIds = visibleTasks.map { it.id }.toSet() + pendingTaskIds
            val deletedIds = existingById.keys - serverTaskIds
            if (deletedIds.isNotEmpty()) {
                routinesTouched = routinesTouched || deletedIds.any { id ->
                    RoutineEnvelope.hasMarker(existingById[id]?.description)
                }
                taskDao.deleteByIds(deletedIds.toList())
                alarmsTouched = true
            }
            if (alarmsTouched) platformHooks.rescheduleAlarms()
            if (routinesTouched) platformHooks.routinesChanged()
            Logger.d(TAG, "Refreshed ${changed.size} changed tasks from server (skipped ${taskEntities.size - safeEntities.size} with pending actions)")

            val labelDtos = api.getAllLabels()
            val labelEntities = labelDtos.map { with(labelMapper) { it.toEntity() } }
            labelDao.upsertAll(labelEntities)
            Logger.d(TAG, "Refreshed ${labelEntities.size} labels from server")

            val projectDtos = api.getAllProjects(includeArchived = true)
            val projectEntities = projectDtos.map { with(projectMapper) { it.toEntity() } }
            projectDao.replaceAll(projectEntities)
            Logger.d(TAG, "Refreshed ${projectEntities.size} projects from server")
        } catch (e: Exception) {
            Logger.e(TAG, "Server refresh failed: ${e.message}", e)
        }
    }
}
