package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.LogbookPrefsStore
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.api.TaskPositionDto
import com.rendyhd.vicu.data.remote.api.MergePatches
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.CustomListEnvelope
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.isRetriableNetworkError
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.RelationKind
import com.rendyhd.vicu.util.RoutineEnvelope
import com.rendyhd.vicu.util.withoutNestedSubtasks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import com.rendyhd.vicu.data.local.ScheduleAction
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.datetime.Clock
import com.rendyhd.vicu.util.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class TaskRepositoryImpl(
    private val taskDao: TaskDao,
    private val api: VikunjaApiService,
    private val pendingActionDao: PendingActionDao,
    private val taskMapper: TaskMapper,
    private val platformHooks: PlatformRepositoryHooks,
    private val json: Json,
    private val behaviorPrefsStore: BehaviorPrefsStore,
    private val logbookPrefsStore: LogbookPrefsStore,
) : TaskRepository {

    companion object {
        private const val TAG = "TaskRepoImpl"
    }

    private val tempIdCounter = AtomicLong(-(Clock.System.now().epochSeconds))
    private val completionBatchesMutex = Mutex()
    private val completionBatches = mutableMapOf<Long, List<DescendantLink>>()

    private data class DescendantLink(
        val parentId: Long,
        val task: Task,
    )

    private suspend fun descendantLinks(root: Task): List<DescendantLink> {
        val result = mutableListOf<DescendantLink>()
        val visited = mutableSetOf(root.id)

        suspend fun visit(parent: Task) {
            val cached = taskDao.getByIdSync(parent.id)?.let { with(taskMapper) { it.toDomain() } }
            val children = cached?.relatedTasks?.get(RelationKind.SUBTASK)
                ?.takeIf { it.isNotEmpty() }
                ?: parent.relatedTasks[RelationKind.SUBTASK].orEmpty()
            children.forEach { child ->
                if (visited.add(child.id)) {
                    val current = taskDao.getByIdSync(child.id)
                        ?.let { with(taskMapper) { it.toDomain() } }
                        ?: child
                    result += DescendantLink(parent.id, current)
                    visit(current)
                }
            }
        }

        visit(root)
        return result
    }

    private suspend fun anchorNewTaskAtEnd(projectId: Long, newTaskId: Long) {
        if (projectId <= 0L) return
        try {
            val views = api.getProjectViews(projectId)
            val listView = views.firstOrNull { it.viewKind == "list" } ?: return
            val existing = api.getViewTasksPage(
                projectId,
                listView.id,
                mapOf("sort_by" to "position", "order_by" to "desc", "per_page" to "1"),
            ).items
            val maxPos = existing.firstOrNull()?.position ?: 0.0
            api.updateTaskPosition(
                newTaskId,
                TaskPositionDto(position = maxPos + 65_536.0, projectViewId = listView.id),
            )
        } catch (e: Exception) {
            Logger.w(TAG, "anchorNewTaskAtEnd failed (non-fatal) for project=$projectId task=$newTaskId: ${e.message}")
        }
    }

    override suspend fun updatePosition(taskId: Long, projectId: Long, newPosition: Double) {
        taskDao.updatePosition(taskId, newPosition)
        try {
            val views = api.getProjectViews(projectId)
            val listView = views.firstOrNull { it.viewKind == "list" } ?: return
            api.updateTaskPosition(
                taskId,
                TaskPositionDto(position = newPosition, projectViewId = listView.id),
            )
        } catch (e: Exception) {
            Logger.w(TAG, "updatePosition failed (non-fatal) for task=$taskId: ${e.message}")
        }
    }

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
            pendingActionDao.queueTaskActionMerging(action)
        }
        platformHooks.triggerSync()
    }

    /**
     * The row to store while an edit of [edited] is in flight or queued: the cached row with only
     * the edited fields replaced. Falls back to a full mapping when the task was never cached.
     */
    private fun optimisticEntity(cached: TaskEntity?, edited: Task): TaskEntity =
        with(taskMapper) {
            cached?.withEditedFields(edited) ?: edited.toDto().toEntity()
        }

    /** Like [optimisticEntity], for a completion toggle: only `done` and `done_at` change. */
    private fun optimisticDoneEntity(cached: TaskEntity?, toggled: Task): TaskEntity =
        with(taskMapper) {
            cached?.withDoneState(toggled.done, toggled.doneAt) ?: toggled.toDto().toEntity()
        }

    private fun queuedUpdatePayload(task: Task, patch: JsonObject): String =
        if (task.id < 0L) {
            json.encodeToString(Task.serializer(), task)
        } else {
            json.encodeToString(JsonObject.serializer(), patch)
        }

    private fun List<TaskEntity>.toTopLevelTasks(): List<Task> =
        filterNot { CustomListEnvelope.isAnyMetadataTask(it.description) }
            .map { with(taskMapper) { it.toDomain() } }
            .withoutNestedSubtasks()

    override fun getInboxTasks(inboxProjectId: Long): Flow<List<Task>> =
        behaviorPrefsStore.getPrefs()
            .map { it.inboxExcludeDated }
            .distinctUntilChanged()
            .flatMapLatest { excludeDated ->
                taskDao.getInboxTasks(inboxProjectId, includeDated = !excludeDated).distinctUntilChanged().map { entities ->
                    entities.toTopLevelTasks()
                }
            }

    override fun getTodayTasks(): Flow<List<Task>> =
        DateUtils.endOfTodayFlow()
            .distinctUntilChanged()
            .flatMapLatest { endOfToday ->
                taskDao.getTodayTasks(endOfToday).distinctUntilChanged().map { entities ->
                    entities.toTopLevelTasks()
                }
            }

    override fun getUpcomingTasks(): Flow<List<Task>> =
        DateUtils.endOfTodayFlow()
            .distinctUntilChanged()
            .flatMapLatest { endOfToday ->
                taskDao.getUpcomingTasks(endOfToday).distinctUntilChanged().map { entities ->
                    entities.toTopLevelTasks()
                }
            }

    override fun getAnytimeTasks(inboxProjectId: Long): Flow<List<Task>> =
        taskDao.getAnytimeTasks(inboxProjectId).distinctUntilChanged().map { entities ->
            entities.toTopLevelTasks()
        }

    override fun getLogbookTasks(): Flow<List<Task>> =
        logbookPrefsStore.getPrefs()
            .map { if (it.enabled) DateUtils.isoDaysAgo(it.retentionDays) else "" }
            .distinctUntilChanged()
            .flatMapLatest { cutoff ->
                taskDao.getLogbookTasks(cutoff).distinctUntilChanged().map { entities ->
                    entities.toTopLevelTasks()
                }
            }

    override fun getByProjectId(projectId: Long): Flow<List<Task>> =
        taskDao.getByProjectId(projectId).distinctUntilChanged().map { entities ->
            entities.toTopLevelTasks()
        }

    override fun getById(id: Long): Flow<Task?> =
        taskDao.getById(id).map { entity ->
            entity
                ?.takeUnless { CustomListEnvelope.isAnyMetadataTask(it.description) }
                ?.let { with(taskMapper) { it.toDomain() } }
        }

    override fun searchByTitle(query: String): Flow<List<Task>> =
        taskDao.searchByTitle(query).map { entities ->
            entities.toTopLevelTasks()
        }

    override fun searchByTitleIncludingDone(query: String): Flow<List<Task>> =
        taskDao.searchByTitleIncludingDone(query).map { list ->
            list.toTopLevelTasks()
        }

    override fun getAllOpenTasks(): Flow<List<Task>> =
        taskDao.getAllOpenTasks().distinctUntilChanged().map { entities ->
            entities.toTopLevelTasks()
        }

    override fun getAllTasks(): Flow<List<Task>> =
        taskDao.getAllTasksFlow().distinctUntilChanged().map { entities ->
            entities.toTopLevelTasks()
        }

    override suspend fun create(task: Task): NetworkResult<Task> {
        return try {
            val createDto = with(taskMapper) { task.toCreateDto() }
            val responseDto = api.createTask(task.projectId, createDto)
            val responseEntity = with(taskMapper) { responseDto.toEntity() }
            taskDao.upsert(responseEntity)
            val created = with(taskMapper) { responseEntity.toDomain() }
            platformHooks.scheduleAlarm(created)
            anchorNewTaskAtEnd(task.projectId, created.id)
            platformHooks.updateWidgets()
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
                platformHooks.updateWidgets()
                NetworkResult.Success(localTask)
            } else {
                NetworkResult.Error(e.message ?: "Failed to create task")
            }
        }
    }

    override suspend fun update(task: Task): NetworkResult<Task> {
        val previous = taskDao.getByIdSync(task.id)
        val previousTask = previous?.let { with(taskMapper) { it.toDomain() } }
        val patch = MergePatches.task(previousTask, task)
        taskDao.upsert(optimisticEntity(previous, task))

        if (task.id < 0L) {
            queueTaskAction(
                task.id,
                "update",
                queuedUpdatePayload(task, patch),
            )
            platformHooks.updateWidgets()
            return NetworkResult.Success(task)
        }
        if (patch.isEmpty()) return NetworkResult.Success(task)

        return try {
            val requestPatch = if (RoutineEnvelope.hasMarker(task.description)) {
                val localParsed = RoutineEnvelope.parse(task.description, json)
                val remoteTask = with(taskMapper) { api.getTask(task.id).toEntity().toDomain() }
                val remoteParsed = RoutineEnvelope.parse(remoteTask.description, json)
                val localPayload = localParsed.payload
                val remotePayload = remoteParsed.payload
                if (localPayload != null && remotePayload != null) {
                    val mergedPayload = RoutineEnvelope.mergePayload(localPayload, remotePayload)
                    val mergedTask = remoteTask.copy(
                        title = mergedPayload.definition.name,
                        description = RoutineEnvelope.upsert(remoteParsed.body, mergedPayload, json),
                        done = true,
                        dueDate = "",
                        repeatAfter = 0,
                        repeatMode = 0,
                        reminders = emptyList(),
                    )
                    MergePatches.task(previous = null, current = mergedTask)
                } else {
                    patch
                }
            } else {
                patch
            }
            val responseDto = api.updateTask(task.id, requestPatch)
            val responseEntity = with(taskMapper) { responseDto.toEntity() }
            taskDao.upsert(responseEntity)

            val updated = with(taskMapper) { responseEntity.toDomain() }
            platformHooks.scheduleAlarm(updated)
            platformHooks.updateWidgets()
            NetworkResult.Success(updated)
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
                queueTaskAction(task.id, "update", queuedUpdatePayload(task, patch))
                platformHooks.updateWidgets()
                NetworkResult.Success(task)
            } else {
                previous?.let { taskDao.upsert(it) }
                NetworkResult.Error(e.message ?: "Failed to update task")
            }
        }
    }

    override suspend fun getByIds(ids: Set<Long>): List<Task> =
        taskDao.getByIds(ids.toList())
            .filterNot { CustomListEnvelope.isAnyMetadataTask(it.description) }
            .map { with(taskMapper) { it.toDomain() } }

    override suspend fun applyScheduleAction(taskId: Long): NetworkResult<Task> {
        // Always start from the stored row: the swipe that triggered this may belong to a row
        // that was composed before the task changed elsewhere. update() diffs against the same
        // row, so the patch holds only the field the action sets.
        val current = taskDao.getByIdSync(taskId)?.let { with(taskMapper) { it.toDomain() } }
            ?: return NetworkResult.Error("Task $taskId is not in the local cache")
        val action = behaviorPrefsStore.getPrefs().first().scheduleAction
        val updated = when (action) {
            ScheduleAction.DUE_TODAY -> current.copy(dueDate = DateUtils.todayEndIso())
            ScheduleAction.PRIORITY_URGENT -> current.copy(priority = 4)
        }
        return update(updated)
    }

    override suspend fun moveToProject(taskId: Long, newProjectId: Long): NetworkResult<Unit> {
        val entity = taskDao.getByIdSync(taskId)
            ?: return NetworkResult.Error("Task $taskId not in local cache; cannot move")
        val task = with(taskMapper) { entity.toDomain() }
        if (task.projectId == newProjectId) return NetworkResult.Success(Unit)
        return when (val r = update(task.copy(projectId = newProjectId))) {
            is NetworkResult.Success -> NetworkResult.Success(Unit)
            is NetworkResult.Error -> r
            NetworkResult.Loading -> NetworkResult.Success(Unit)
        }
    }

    override suspend fun moveDescendantsToProject(taskId: Long, newProjectId: Long): NetworkResult<Int> {
        val root = taskDao.getByIdSync(taskId)?.let { with(taskMapper) { it.toDomain() } }
            ?: return NetworkResult.Success(0)
        val descendants = descendantLinks(root).map { it.task }.filter { it.projectId != newProjectId }
        var moved = 0
        var failed = 0
        var firstError: String? = null
        for (descendant in descendants) {
            when (val result = moveToProject(descendant.id, newProjectId)) {
                is NetworkResult.Success -> moved++
                is NetworkResult.Error -> {
                    failed++
                    if (firstError == null) firstError = result.message
                }
                NetworkResult.Loading -> Unit
            }
        }
        return if (failed == 0) {
            NetworkResult.Success(moved)
        } else {
            NetworkResult.Error(
                "The task was moved, but $failed of ${descendants.size} subtasks could not be: $firstError",
            )
        }
    }

    override suspend fun delete(taskId: Long, deleteSubtasks: Boolean): NetworkResult<Unit> {
        if (deleteSubtasks) {
            val root = taskDao.getByIdSync(taskId)?.let { with(taskMapper) { it.toDomain() } }
            if (root != null) {
                for (link in descendantLinks(root).asReversed()) {
                    when (val result = deleteSingle(link.task.id)) {
                        is NetworkResult.Error -> return result
                        else -> Unit
                    }
                }
            }
        }
        completionBatchesMutex.withLock { completionBatches.remove(taskId) }
        return deleteSingle(taskId)
    }

    private suspend fun deleteSingle(taskId: Long): NetworkResult<Unit> {
        if (taskId < 0L) {
            platformHooks.cancelAlarm(taskId)
            taskDao.deleteById(taskId)
            queueTaskAction(taskId, "delete", "")
            platformHooks.updateWidgets()
            return NetworkResult.Success(Unit)
        }
        return try {
            platformHooks.cancelAlarm(taskId)
            taskDao.deleteById(taskId)
            api.deleteTask(taskId)
            platformHooks.updateWidgets()
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
                queueTaskAction(taskId, "delete", "")
                platformHooks.updateWidgets()
                NetworkResult.Success(Unit)
            } else {
                NetworkResult.Error(e.message ?: "Failed to delete task")
            }
        }
    }

    override suspend fun createSubtask(parentTaskId: Long, subtask: Task): NetworkResult<Task> {
        return try {
            val createDto = with(taskMapper) { subtask.toCreateDto() }
            val createdDto = api.createTask(subtask.projectId, createDto)
            val createdEntity = with(taskMapper) { createdDto.toEntity() }
            taskDao.upsert(createdEntity)

            api.createRelation(
                parentTaskId,
                com.rendyhd.vicu.data.remote.api.CreateRelationDto(
                    otherTaskId = createdDto.id,
                    relationKind = RelationKind.SUBTASK,
                ),
            )

            val linkedChildEntity = taskDao.getByIdSync(parentTaskId)?.let { parent ->
                with(taskMapper) {
                    taskDao.upsert(parent.withRelatedTaskAdded(RelationKind.SUBTASK, createdDto))
                    createdEntity.withRelatedTaskAdded(RelationKind.PARENTTASK, parent.toDomain().toDto())
                }
            } ?: createdEntity
            taskDao.upsert(linkedChildEntity)

            NetworkResult.Success(with(taskMapper) { linkedChildEntity.toDomain() })
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Failed to create subtask")
        }
    }

    override suspend fun toggleSubtaskDone(parentTaskId: Long, subtask: Task): NetworkResult<Task> {
        return toggleTaskTree(subtask, parentTaskId)
    }

    private suspend fun toggleTaskTree(
        task: Task,
        explicitParentId: Long? = null,
    ): NetworkResult<Task> {
        val targetDone = !task.done
        if (!targetDone) {
            val batch = completionBatchesMutex.withLock { completionBatches.remove(task.id) }.orEmpty()
            val rootResult = if (explicitParentId != null) {
                setLinkedTaskDone(explicitParentId, task, targetDone = false, playSound = false)
            } else {
                setTaskDone(task, targetDone = false, playSound = false)
            }
            if (rootResult !is NetworkResult.Success) return rootResult

            // Restore only descendants that were auto-completed with this parent. Descendants
            // already completed by the user before the cascade are deliberately left alone.
            batch.asReversed().forEach { link ->
                val current = taskDao.getByIdSync(link.task.id)
                    ?.let { with(taskMapper) { it.toDomain() } }
                    ?: link.task.copy(done = true)
                if (current.done) {
                    val restored = setLinkedTaskDone(
                        link.parentId,
                        current,
                        targetDone = false,
                        playSound = false,
                    )
                    if (restored is NetworkResult.Error) {
                        Logger.w(TAG, "Could not restore auto-completed subtask ${link.task.id}: ${restored.message}")
                    }
                }
            }
            return rootResult
        }

        val autoCompleted = descendantLinks(task).filterNot { it.task.done }
        val completed = mutableListOf<DescendantLink>()
        for (link in autoCompleted.asReversed()) {
            when (val childResult = setLinkedTaskDone(
                link.parentId,
                link.task,
                targetDone = true,
                playSound = false,
            )) {
                is NetworkResult.Success -> completed += link
                is NetworkResult.Error -> {
                    rollbackAutoCompleted(completed)
                    return childResult
                }
                NetworkResult.Loading -> Unit
            }
        }

        val rootResult = if (explicitParentId != null) {
            setLinkedTaskDone(explicitParentId, task, targetDone = true, playSound = true)
        } else {
            setTaskDone(task, targetDone = true, playSound = true)
        }
        if (rootResult is NetworkResult.Success) {
            completionBatchesMutex.withLock { completionBatches[task.id] = autoCompleted }
        } else {
            rollbackAutoCompleted(completed)
        }
        return rootResult
    }

    private suspend fun rollbackAutoCompleted(completed: List<DescendantLink>) {
        completed.forEach { link ->
            val current = taskDao.getByIdSync(link.task.id)
                ?.let { with(taskMapper) { it.toDomain() } }
                ?: link.task.copy(done = true)
            if (current.done) {
                setLinkedTaskDone(link.parentId, current, targetDone = false, playSound = false)
            }
        }
    }

    private suspend fun setLinkedTaskDone(
        parentTaskId: Long,
        subtask: Task,
        targetDone: Boolean,
        playSound: Boolean,
    ): NetworkResult<Task> {
        val cached = taskDao.getByIdSync(subtask.id)
        val current = cached?.let { with(taskMapper) { it.toDomain() } } ?: subtask
        if (current.done == targetDone) {
            updateParentDoneReferences(current, targetDone, parentTaskId)
            return NetworkResult.Success(current)
        }
        val toggled = current.copy(
            done = targetDone,
            doneAt = if (targetDone) DateUtils.nowIso() else "",
        )
        if (playSound && toggled.done) {
            platformHooks.playCompletionSound()
        }

        updateParentDoneReferences(current, toggled.done, parentTaskId)
        cached?.let {
            taskDao.upsert(it.copy(done = toggled.done, doneAt = DateUtils.normalizeToUtc(toggled.doneAt)))
        }

        val patch = MergePatches.taskDone(toggled.done)
        if (subtask.id < 0L) {
            queueTaskAction(
                subtask.id,
                "toggle_done",
                queuedUpdatePayload(toggled, patch),
            )
            if (toggled.done) platformHooks.cancelAlarm(subtask.id)
            platformHooks.updateWidgets()
            return NetworkResult.Success(toggled)
        }
        return try {
            val responseDto = api.updateTask(subtask.id, patch)
            val responseEntity = with(taskMapper) { responseDto.toEntity() }
            taskDao.upsert(responseEntity)
            val result = with(taskMapper) { responseEntity.toDomain() }
            updateParentDoneReferences(result, responseDto.done, parentTaskId)
            if (toggled.done) platformHooks.cancelAlarm(subtask.id) else platformHooks.scheduleAlarm(result)
            platformHooks.updateWidgets()
            NetworkResult.Success(result)
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
                queueTaskAction(
                    subtask.id,
                    "toggle_done",
                    queuedUpdatePayload(toggled, patch),
                )
                if (toggled.done) platformHooks.cancelAlarm(subtask.id)
                platformHooks.updateWidgets()
                NetworkResult.Success(toggled)
            } else {
                cached?.let { taskDao.upsert(it) }
                updateParentDoneReferences(current, current.done, parentTaskId)
                NetworkResult.Error(e.message ?: "Failed to update subtask")
            }
        }
    }

    private suspend fun updateParentDoneReferences(
        task: Task,
        done: Boolean,
        explicitParentId: Long? = null,
    ) {
        val parentIds = buildSet {
            explicitParentId?.let(::add)
            task.relatedTasks[RelationKind.PARENTTASK].orEmpty().forEach { add(it.id) }
            taskDao.getByIdSync(task.id)?.let { entity ->
                with(taskMapper) { entity.toDomain() }
                    .relatedTasks[RelationKind.PARENTTASK]
                    .orEmpty()
                    .forEach { add(it.id) }
            }
        }
        parentIds.forEach { parentId ->
            taskDao.getByIdSync(parentId)?.let { parent ->
                taskDao.upsert(with(taskMapper) { parent.withRelatedTaskDone(task.id, done) })
            }
        }
    }

    override suspend fun createRelation(
        taskId: Long,
        otherTaskId: Long,
        relationKind: String,
    ): NetworkResult<Unit> {
        return try {
            api.createRelation(
                taskId,
                com.rendyhd.vicu.data.remote.api.CreateRelationDto(
                    otherTaskId = otherTaskId,
                    relationKind = relationKind,
                ),
            )
            val otherDto = try {
                api.getTask(otherTaskId).also { taskDao.upsert(with(taskMapper) { it.toEntity() }) }
            } catch (e: Exception) {
                null
            }
            val baseEntity = taskDao.getByIdSync(taskId)
            if (otherDto != null && baseEntity != null) {
                taskDao.upsert(with(taskMapper) { baseEntity.withRelatedTaskAdded(relationKind, otherDto) })
            } else {
                val dto = api.getTask(taskId)
                taskDao.upsert(with(taskMapper) { dto.toEntity() })
            }
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Failed to create relation")
        }
    }

    override suspend fun deleteRelation(
        taskId: Long,
        relationKind: String,
        otherTaskId: Long,
    ): NetworkResult<Unit> {
        return try {
            api.deleteRelation(taskId, relationKind, otherTaskId)
            taskDao.getByIdSync(taskId)?.let { base ->
                taskDao.upsert(with(taskMapper) { base.withRelatedTaskRemoved(relationKind, otherTaskId) })
            }
            try {
                val otherDto = api.getTask(otherTaskId)
                taskDao.upsert(with(taskMapper) { otherDto.toEntity() })
            } catch (e: Exception) {
            }
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Failed to delete relation")
        }
    }

    override suspend fun toggleDone(task: Task): NetworkResult<Task> {
        return toggleTaskTree(task)
    }

    override suspend fun setDone(taskId: Long, done: Boolean): NetworkResult<Task> {
        val current = taskDao.getByIdSync(taskId)?.let { with(taskMapper) { it.toDomain() } }
            ?: return NetworkResult.Error("Task $taskId is not in the local cache")
        if (current.done == done) return NetworkResult.Success(current)
        // The task is not in the requested state, so flipping it reaches that state; this goes
        // through the same path (subtask cascade, queueing) as completing from a list.
        return toggleTaskTree(current)
    }

    private suspend fun setTaskDone(
        task: Task,
        targetDone: Boolean,
        playSound: Boolean,
    ): NetworkResult<Task> {
        val cachedEntity = taskDao.getByIdSync(task.id)
        val cached = cachedEntity?.let { with(taskMapper) { it.toDomain() } }
        val current = cached ?: task
        val toggled = task.copy(
            relatedTasks = current.relatedTasks.ifEmpty { task.relatedTasks },
            done = targetDone,
            doneAt = if (targetDone) DateUtils.nowIso() else "",
        )
        if (playSound && toggled.done) {
            platformHooks.playCompletionSound()
        }
        val patch = MergePatches.taskDone(toggled.done)
        if (task.id < 0L) {
            taskDao.upsert(optimisticDoneEntity(cachedEntity, toggled))
            queueTaskAction(
                task.id,
                "toggle_done",
                queuedUpdatePayload(toggled, patch),
            )
            updateParentDoneReferences(toggled, toggled.done)
            if (toggled.done) platformHooks.cancelAlarm(task.id)
            platformHooks.updateWidgets()
            return NetworkResult.Success(toggled)
        }
        return try {
            val responseDto = api.updateTask(task.id, patch)
            val responseEntity = with(taskMapper) { responseDto.toEntity() }
            val result = with(taskMapper) { responseEntity.toDomain() }
            updateParentDoneReferences(toggled, responseDto.done)
            if (toggled.done) {
                platformHooks.cancelAlarm(task.id)
            } else {
                platformHooks.scheduleAlarm(result)
            }
            platformHooks.updateWidgets()
            NetworkResult.Success(result)
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
                taskDao.upsert(optimisticDoneEntity(cachedEntity, toggled))
                queueTaskAction(
                    task.id,
                    "toggle_done",
                    queuedUpdatePayload(toggled, patch),
                )
                updateParentDoneReferences(toggled, toggled.done)
                if (toggled.done) {
                    platformHooks.cancelAlarm(task.id)
                }
                platformHooks.updateWidgets()
                NetworkResult.Success(toggled)
            } else {
                NetworkResult.Error(e.message ?: "Failed to toggle task")
            }
        }
    }

    override suspend fun deleteLocalByIds(ids: Set<Long>) {
        if (ids.isNotEmpty()) taskDao.deleteByIds(ids.toList())
    }

    override suspend fun refreshAll(filters: Map<String, String>): NetworkResult<Unit> {
        Logger.d(TAG, "refreshAll() called with filters=$filters")
        return try {
            val allTasks = api.getAllTasks(filters)
            // Routine carriers remain cached for their existing merge engine. Custom-list
            // carriers are owned by CustomListRepository and must never enter user task data.
            val visibleTasks = allTasks.filterNot { CustomListEnvelope.hasMarker(it.description) }
            val entities = visibleTasks.map { with(taskMapper) { it.toEntity() } }
            val pendingTaskIds = pendingActionDao.getTaskIdsWithPendingActions().toSet()
            val existingById = taskDao.getAllSync().associateBy { it.id }
            val safeEntities = entities.filter { it.id !in pendingTaskIds }
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
            if (filters.isEmpty()) {
                val serverTaskIds = visibleTasks.map { it.id }.toSet() + pendingTaskIds
                val deletedIds = existingById.keys - serverTaskIds
                if (deletedIds.isNotEmpty()) {
                    routinesTouched = routinesTouched || deletedIds.any { id ->
                        RoutineEnvelope.hasMarker(existingById[id]?.description)
                    }
                    taskDao.deleteByIds(deletedIds.toList())
                    alarmsTouched = true
                }
            }
            if (alarmsTouched) platformHooks.rescheduleAlarms()
            if (routinesTouched) platformHooks.routinesChanged()
            platformHooks.updateWidgets()
            Logger.d(TAG, "refreshAll() SUCCESS: upserted ${changed.size} changed tasks (skipped ${entities.size - safeEntities.size} with pending actions)")
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            Logger.e(TAG, "refreshAll() FAILED: ${e.message}", e)
            NetworkResult.Error(e.message ?: "Failed to refresh tasks")
        }
    }
}
