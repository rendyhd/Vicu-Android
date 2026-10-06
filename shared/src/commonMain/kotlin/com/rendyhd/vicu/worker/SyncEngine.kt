package com.rendyhd.vicu.worker

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.AuthState
import com.rendyhd.vicu.data.local.dao.LabelDao
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.dao.ProjectDao
import com.rendyhd.vicu.data.local.dao.normalizeQueuedPatchPayload
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.mapper.LabelMapper
import com.rendyhd.vicu.data.mapper.ProjectMapper
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.api.CreateRelationDto
import com.rendyhd.vicu.data.remote.api.LabelTaskDto
import com.rendyhd.vicu.data.remote.api.MergePatches
import com.rendyhd.vicu.data.remote.api.TaskDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiException
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.remote.BaseUrlHolder
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.CustomListSyncStatus
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.domain.repository.CustomListRepository
import com.rendyhd.vicu.domain.repository.RoutineRepository
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.isRetriableNetworkError
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.RelationKind
import com.rendyhd.vicu.util.RoutineEnvelope
import com.rendyhd.vicu.util.CustomListEnvelope
import io.ktor.client.plugins.ResponseException
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
    /** Uploads routine history older versions kept only on this phone; see [uploadLocalRoutineHistory]. */
    private val routineRepository: RoutineRepository? = null,
) {
    private val missing = MissingResourceCheck(api)

    companion object {
        private const val TAG = "SyncEngine"
        private const val MAX_RETRIES = 5
        private const val DUPLICATE_WINDOW_SECS = 900L

        /**
         * How long a failed action is kept for the user to retry or discard. After that it is
         * dropped at the start of a sync run and the server's version of the task wins again.
         */
        const val FAILED_ACTION_RETENTION_DAYS = 14

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

        /**
         * Runs [block] while no sync is running and none can start. Used by operations that
         * rewrite the queue or wipe local data, which must not interleave with a run that is
         * mid-action. Never call this from inside a sync run.
         */
        suspend fun <T> exclusive(block: suspend () -> T): T = syncMutex.withLock { block() }
    }

    suspend fun performSync(): Boolean = syncMutex.withLock { performSyncLocked() }

    /** Must only run while holding [syncMutex]. */
    private suspend fun performSyncLocked(): Boolean {
        Logger.d(TAG, "SyncEngine started")

        baseUrlHolder.ensureInitialized()
        authManager.ensureInitializedAndGetToken()

        if (authManager.authState.value == AuthState.NeedsReAuth) {
            // The session ended and the user has to sign in again. Every request would be
            // answered with 401, so send none: the queued changes stay as they are and signing
            // in again starts a new run. Reporting success keeps WorkManager from retrying.
            Logger.w(TAG, "Sync paused: sign-in required, queued actions stay pending")
            return true
        }

        // Safe only because no other run can be mid-action while we hold the lock: whatever is
        // still "processing" belongs to a run that was killed or cancelled.
        pendingActionDao.resetProcessingToPending()
        pendingActionDao.deleteFailedBefore(DateUtils.isoDaysAgo(FAILED_ACTION_RETENTION_DAYS))

        var hasRetriableFailures = false
        var pausedForAuth = false

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
                    when {
                        isUnauthorized(e) -> {
                            // The HTTP client already tried to refresh the token, so the session
                            // is gone, not this action: keep it untouched and stop, because every
                            // later action would be refused the same way.
                            pendingActionDao.updateStatus(action.id, "pending")
                            pausedForAuth = true
                        }
                        resolveMissingResource(action, e, tempIdMap) -> {
                            // What the change was about no longer exists on the server (or the
                            // change already happened there), so retrying can never work and
                            // "failed" would only nag. The local rows were cleaned up.
                            pendingActionDao.updateStatus(action.id, "completed")
                        }
                        isRetriableNetworkError(e) && action.retryCount < MAX_RETRIES -> {
                            pendingActionDao.updateStatus(action.id, "pending", action.retryCount + 1)
                            hasRetriableFailures = true
                        }
                        else -> pendingActionDao.markFailed(action.id, DateUtils.nowIso())
                    }
                    if (pausedForAuth) break
                }
            }

            pendingActionDao.deleteCompleted()

            if (pausedForAuth) {
                // The token refresh flags NeedsReAuth; signing in again starts a new run, so no
                // retry is needed. A 401 without that flag is unexpected: try again later.
                return authManager.authState.value == AuthState.NeedsReAuth
            }

            when (customListRepository.sync()) {
                CustomListSyncStatus.Pending,
                is CustomListSyncStatus.Offline -> hasRetriableFailures = true
                else -> Unit
            }
            val refreshed = refreshAllFromServer()
            uploadLocalRoutineHistory(carriersAuthoritative = refreshed)
        } catch (e: Exception) {
            Logger.e(TAG, "SyncEngine failed: ${e.message}", e)
            throw e
        } finally {
            platformHooks.updateWidgets()
        }

        return !hasRetriableFailures
    }

    private fun isUnauthorized(e: Exception): Boolean = when (e) {
        is VikunjaApiException -> e.httpStatus == 401
        is ResponseException -> e.response.status.value == 401
        else -> false
    }

    /**
     * Handles a queued change the server refused with a 404 that means "that thing no longer
     * exists" (see [MissingResourceCheck]); returns true when the action is finished and must not
     * be retried or marked failed. A 404 that names another missing resource, or that cannot be
     * confirmed, returns false and the action fails visibly.
     */
    private suspend fun resolveMissingResource(
        action: PendingActionEntity,
        e: Exception,
        tempIdMap: Map<Long, Long>,
    ): Boolean {
        if (e !is VikunjaApiException) return false
        return when (action.entityType) {
            "task" -> {
                if (action.actionType !in TASK_DEPENDENT_ACTIONS) return false
                val taskId = tempIdMap[action.entityId] ?: action.entityId
                if (!missing.taskGone(taskId, e)) return false
                dropGoneTask(taskId, action.actionType)
                true
            }
            "label" -> resolveMissingForLabelAction(action, e, tempIdMap)
            else -> false
        }
    }

    private suspend fun resolveMissingForLabelAction(
        action: PendingActionEntity,
        e: VikunjaApiException,
        tempIdMap: Map<Long, Long>,
    ): Boolean {
        val labelId = tempIdMap[action.entityId] ?: action.entityId
        when (action.actionType) {
            "delete" -> {
                // The label is already gone, which is what the user asked for.
                if (e.httpStatus != 404) return false
                labelDao.deleteById(labelId)
                return true
            }
            "update" -> {
                if (!missing.labelGone(labelId, e)) return false
                labelDao.deleteById(labelId)
                return true
            }
            "add_label", "remove_label" -> {
                val (taskId, parsedLabelId) = labelTaskIds(action.payload, tempIdMap) ?: return false
                if (action.actionType == "add_label") {
                    // Already on the task: the change has happened.
                    if (e.httpStatus == 400 && e.problem?.code == MissingResourceCheck.LABEL_ALREADY_ON_TASK) return true
                    return when (missing.labelActionTarget(taskId, parsedLabelId, e)) {
                        LabelActionTarget.TASK_GONE -> {
                            dropGoneTask(taskId, action.actionType)
                            true
                        }
                        LabelActionTarget.LABEL_GONE -> {
                            forgetLabel(taskId, parsedLabelId)
                            true
                        }
                        LabelActionTarget.UNKNOWN -> false
                    }
                }
                // Removing: a 404 means the task or the label is gone, or the label was not on the
                // task. In every case the label is not on the task, which is what was asked for.
                if (e.httpStatus != 404) return false
                if (e.problem?.code == MissingResourceCheck.TASK_DOES_NOT_EXIST) {
                    dropGoneTask(taskId, action.actionType)
                } else {
                    forgetLabel(taskId, parsedLabelId, deleteLabel = false)
                }
                return true
            }
            else -> return false
        }
    }

    /** The task and label ids of an add_label / remove_label payload ("taskId:labelId"), remapped from temp ids. */
    private fun labelTaskIds(payload: String, tempIdMap: Map<Long, Long>): Pair<Long, Long>? {
        val parts = payload.split(":")
        if (parts.size != 2) return null
        val taskId = parts[0].toLongOrNull()?.let { tempIdMap[it] ?: it } ?: return null
        val labelId = parts[1].toLongOrNull()?.let { tempIdMap[it] ?: it } ?: return null
        return taskId to labelId
    }

    /** The label no longer exists on the server: it leaves the cached task, and (by default) the label cache. */
    private suspend fun forgetLabel(taskId: Long, labelId: Long, deleteLabel: Boolean = true) {
        taskDao.getByIdSync(taskId)?.let { entity ->
            taskDao.upsert(with(taskMapper) { entity.withLabelRemoved(labelId) })
        }
        if (deleteLabel) labelDao.deleteById(labelId)
        Logger.w(TAG, "Label $labelId is gone on the server; dropped the change and the label on task $taskId")
    }

    private suspend fun dropGoneTask(taskId: Long, actionType: String) {
        val local = taskDao.getByIdSync(taskId)
        taskDao.deleteById(taskId)
        platformHooks.cancelAlarm(taskId)
        if (RoutineEnvelope.hasMarker(local?.description)) platformHooks.routinesChanged()
        Logger.w(TAG, "Task $taskId no longer exists on the server; dropped $actionType and the local row")
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
                            // Same title in the same project is common; the description tells a
                            // real earlier attempt of this create from a different task.
                            dto.description == task.description &&
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
                linkToQueuedParents(task, created.id, tempIdMap)
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

    /**
     * A subtask created offline carries its parent in the queued task. Now that the subtask
     * exists on the server, link it. A parent that is still only local (its own create has not
     * run) leaves the subtask unlinked rather than failing it.
     */
    private suspend fun linkToQueuedParents(task: Task, childId: Long, tempIdMap: Map<Long, Long>) {
        for (parent in task.relatedTasks[RelationKind.PARENTTASK].orEmpty()) {
            val parentId = tempIdMap[parent.id] ?: parent.id
            if (parentId < 0L) {
                Logger.w(TAG, "Parent $parentId of new subtask $childId is not on the server yet; leaving it unlinked")
                continue
            }
            try {
                api.createRelation(
                    parentId,
                    CreateRelationDto(otherTaskId = childId, relationKind = RelationKind.SUBTASK),
                )
            } catch (e: VikunjaApiException) {
                // 409: the pair is already linked (an earlier attempt got this far).
                if (e.httpStatus != 409) throw e
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
                a.entityType == "task" && a.actionType == "create" && a.entityId != tempId ->
                    remapCreateParent(a, tempId, realId)
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

    /** A queued subtask create that names [tempId] as its parent now names [realId]. */
    private suspend fun remapCreateParent(action: PendingActionEntity, tempId: Long, realId: Long) {
        val task = runCatching { json.decodeFromString<Task>(action.payload) }.getOrNull() ?: return
        val parents = task.relatedTasks[RelationKind.PARENTTASK].orEmpty()
        if (parents.none { it.id == tempId }) return
        val remapped = task.copy(
            relatedTasks = task.relatedTasks + (
                RelationKind.PARENTTASK to parents.map { if (it.id == tempId) it.copy(id = realId) else it }
                ),
        )
        pendingActionDao.remapEntity(
            action.id,
            action.entityId,
            json.encodeToString(Task.serializer(), remapped),
            "pending",
        )
    }

    /**
     * Routine history that versions before 1.9 kept only on this phone goes into archive parts on
     * the server as soon as it can be reached. This is the retry for a first launch that was
     * offline. A failure never fails the sync: the next run, or the next time routines are
     * opened, tries again.
     */
    private suspend fun uploadLocalRoutineHistory(carriersAuthoritative: Boolean) {
        val repository = routineRepository ?: return
        try {
            val result = repository.migrateLocalArchive(carriersAuthoritative)
            if (result is NetworkResult.Error) {
                Logger.w(TAG, "Routine history upload will be retried: ${result.message}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Routine history upload failed: ${e.message}")
        }
    }

    /** True when tasks, labels and projects were all refreshed, so the carrier cache is the server's. */
    private suspend fun refreshAllFromServer(): Boolean {
        try {
            val allTasks = api.getAllTasks()
            // Routine archive parts are read on demand from the server and never cached.
            val visibleTasks = allTasks.filterNot {
                CustomListEnvelope.hasMarker(it.description) || RoutineEnvelope.hasArchiveMarker(it.description)
            }
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
            return true
        } catch (e: Exception) {
            Logger.e(TAG, "Server refresh failed: ${e.message}", e)
            return false
        }
    }
}
