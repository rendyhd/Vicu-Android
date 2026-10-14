package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.LogbookPrefsStore
import com.rendyhd.vicu.data.local.TempIdGenerator
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.api.MergePatches
import com.rendyhd.vicu.data.remote.api.VikunjaApiException
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.sync.TaskRefresher
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LogbookPage
import com.rendyhd.vicu.domain.repository.QuickDue
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.CustomListEnvelope
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.PositionUpdate
import com.rendyhd.vicu.util.isNetworkFailure
import com.rendyhd.vicu.util.isRetriableNetworkError
import com.rendyhd.vicu.util.mayHaveReachedServer
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.RelationKind
import com.rendyhd.vicu.util.RoutineEnvelope
import com.rendyhd.vicu.util.SqlLike
import com.rendyhd.vicu.util.withoutNestedSubtasks
import com.rendyhd.vicu.worker.MissingResourceCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import com.rendyhd.vicu.data.local.ScheduleAction
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.datetime.Instant

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
    private val dayClock: DayClock,
    private val tempIds: TempIdGenerator,
    private val refresher: TaskRefresher,
    /** Puts new tasks at the end of their list, after the create has returned, and remembers what it asked. */
    private val positioner: ListPositioner,
    /** Sends a change to an existing task or queues it; shared with the label repository. */
    private val writeGate: TaskWriteGate = TaskWriteGate(pendingActionDao),
    /**
     * Where the Room rows of the live lists are mapped to domain tasks (JSON decoding, nesting).
     * Without it that work ran on the collector's dispatcher, the main thread, for every emission
     * of every live list (A-UI-19). Tests pass an unconfined dispatcher to stay deterministic.
     */
    private val mappingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TaskRepository {

    companion object {
        private const val TAG = "TaskRepoImpl"

        /** Completed tasks fetched per Logbook page. */
        const val LOGBOOK_PAGE_SIZE = 50

        /** How many repeating completions are remembered for Undo; an Undo comes seconds later. */
        private const val MAX_REMEMBERED_ADVANCES = 32

        /** Shown when a change is refused because the task was deleted on the server. */
        private const val TASK_DELETED_ELSEWHERE = "This task was deleted on another device"
    }

    private val missing = MissingResourceCheck(api)

    /**
     * A repeating task as it was before its completion, and as the server returned it (still
     * open, with its dates moved on). Undo of that completion has to move the dates back: the
     * task is open already, so reopening it would change nothing.
     */
    private class AdvancedTask(val before: Task, val after: Task)

    private val advancedMutex = Mutex()
    private val advanced = LinkedHashMap<Long, AdvancedTask>()

    /**
     * Remembers the completion of a repeating task: the server answered a completion with a task
     * that is still open and whose dates or reminders moved on.
     */
    private suspend fun noteAdvanced(before: Task, after: Task) {
        if (before.done || after.done) return
        val moved = before.dueDate != after.dueDate || before.startDate != after.startDate ||
            before.endDate != after.endDate || before.reminders != after.reminders
        if (!moved) return
        advancedMutex.withLock {
            advanced.remove(before.id)
            advanced[before.id] = AdvancedTask(before, after)
            while (advanced.size > MAX_REMEMBERED_ADVANCES) advanced.remove(advanced.keys.first())
        }
    }

    /**
     * Undo of a repeating task's completion: puts the dates and reminders back with a merge patch.
     * Null when this is not such a task, or when it was edited since (then its dates are the
     * user's, not the completion's).
     */
    private suspend fun undoAdvance(current: Task): NetworkResult<Task>? {
        val entry = advancedMutex.withLock { advanced.remove(current.id) } ?: return null
        val unchanged = current.dueDate == entry.after.dueDate && current.startDate == entry.after.startDate &&
            current.endDate == entry.after.endDate && current.reminders == entry.after.reminders
        if (!unchanged) return null
        return update(
            current.copy(
                dueDate = entry.before.dueDate,
                startDate = entry.before.startDate,
                endDate = entry.before.endDate,
                reminders = entry.before.reminders,
            ),
        )
    }

    /**
     * A change to [taskId] was refused with [e]. When that says the task no longer exists on the
     * server (a 404 the server's task-missing code or a second look confirms), the cached row is a
     * ghost left by a deletion on another device: remove it. Returns whether it was removed.
     */
    private suspend fun dropIfDeleted(taskId: Long, e: Exception): Boolean {
        if (e !is VikunjaApiException || e.httpStatus != 404) return false
        if (!missing.taskGone(taskId, e)) return false
        platformHooks.cancelAlarm(taskId)
        taskDao.deleteById(taskId)
        platformHooks.updateWidgets()
        return true
    }

    private val completionBatchesMutex = Mutex()
    private val parentReferenceMutex = Mutex()
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

    override suspend fun updatePosition(taskId: Long, projectId: Long, newPosition: Double): NetworkResult<Unit> =
        applyPositions(projectId, listOf(PositionUpdate(taskId, newPosition)))

    override suspend fun applyPositions(projectId: Long, updates: List<PositionUpdate>): NetworkResult<Unit> {
        if (updates.isEmpty()) return NetworkResult.Success(Unit)
        // The list is drawn from the cached rows, so the new order shows at once.
        taskDao.updatePositions(updates.associate { it.id to it.position })
        for (update in updates) {
            // A task that only exists here has no position on the server yet; the sync gives it one.
            if (update.id < 0L) continue
            if (!positioner.setPosition(projectId, update.id, update.position)) {
                return NetworkResult.Error("Could not save the new order")
            }
        }
        return NetworkResult.Success(Unit)
    }

    override suspend fun refreshListPositions(projectId: Long): NetworkResult<Unit> {
        return try {
            val viewId = positioner.listViewIdOrNull(projectId) ?: return NetworkResult.Success(Unit)
            val listed = api.getAllViewTasks(
                projectId,
                viewId,
                mapOf("filter" to "done = false", "sort_by" to "position", "order_by" to "asc"),
                expandSubtasks = false,
            )
            val cached = taskDao.getByIds(listed.map { it.id }).associateBy { it.id }
            val changed = listed
                .filter { dto -> cached[dto.id]?.let { it.position != dto.position } == true }
                .associate { it.id to it.position }
            if (changed.isNotEmpty()) taskDao.updatePositions(changed)
            NetworkResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "refreshListPositions($projectId) failed: ${e.message}")
            refreshFailure(e, "Failed to read the order of the list")
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
     * [id] itself, or the server's id when [id] is the temporary id of a task whose create has gone
     * through since the caller read it: the sync swapped in the created task, and a screen or a
     * notification may still hold the old id.
     */
    private suspend fun currentId(id: Long): Long {
        if (id >= 0L || taskDao.getByIdSync(id) != null) return id
        return tempIds.realIdFor(id) ?: id
    }

    /** What happened to a change of a task that only exists on this device. */
    private sealed interface LocalChange<out T> {
        /** Made here and queued behind the task's create. */
        data class Done<T>(val result: NetworkResult<T>) : LocalChange<T>

        /** Nothing was done: the create has gone through and the task is [realId] now. */
        data class Created(val realId: Long) : LocalChange<Nothing>
    }

    /**
     * Runs [change] for the offline-created task [tempId] while the sync cannot swap in the created
     * task (it does that under the same lock), so the change either lands on the local row before
     * the swap, and moves to the real id with the other queued changes, or goes to the real task
     * after it. Without that it could bring a deleted local row back as a duplicate.
     */
    private suspend fun <T> changeLocalTask(tempId: Long, change: suspend () -> NetworkResult<T>): LocalChange<T> =
        writeGate.withTaskLock(tempId) {
            val realId = tempIds.realIdFor(tempId)
            if (realId != null) LocalChange.Created(realId) else LocalChange.Done(change())
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

    /**
     * Every task, nested subtasks included; sync metadata tasks are never user tasks. The list
     * queries already leave them out in SQL; the check is a stored flag, not a description scan.
     */
    private fun List<TaskEntity>.toTasks(): List<Task> =
        filterNot { it.isMetadata }.map { with(taskMapper) { it.toDomain() } }

    private fun List<TaskEntity>.toTopLevelTasks(): List<Task> = toTasks().withoutNestedSubtasks()

    override fun getInboxTasks(inboxProjectId: Long): Flow<List<Task>> =
        behaviorPrefsStore.getPrefs()
            .map { it.inboxExcludeDated }
            .distinctUntilChanged()
            .flatMapLatest { excludeDated ->
                taskDao.getInboxTasks(inboxProjectId, includeDated = !excludeDated).distinctUntilChanged().map { entities ->
                    entities.toTopLevelTasks()
                }
            }
            .flowOn(mappingDispatcher)

    /**
     * The start of the local day after today: the exclusive end of today. Today is
     * `dueDate < startOfTomorrow` and Upcoming is `dueDate >= startOfTomorrow`. It moves at
     * midnight and when the time zone changes.
     */
    private fun startOfTomorrowFlow(): Flow<String> =
        dayClock.day.map { DueDates.startOfTomorrow(it.date, it.zone).toString() }

    override fun getTodayTasks(): Flow<List<Task>> =
        startOfTomorrowFlow()
            .distinctUntilChanged()
            .flatMapLatest { startOfTomorrow ->
                taskDao.getTodayTasks(startOfTomorrow).distinctUntilChanged().map { entities ->
                    entities.toTopLevelTasks()
                }
            }
            .flowOn(mappingDispatcher)

    override fun getUpcomingTasks(): Flow<List<Task>> =
        startOfTomorrowFlow()
            .distinctUntilChanged()
            .flatMapLatest { startOfTomorrow ->
                taskDao.getUpcomingTasks(startOfTomorrow).distinctUntilChanged().map { entities ->
                    entities.toTopLevelTasks()
                }
            }
            .flowOn(mappingDispatcher)

    override fun getAnytimeTasks(inboxProjectId: Long): Flow<List<Task>> =
        taskDao.getAnytimeTasks(inboxProjectId).distinctUntilChanged().map { entities ->
            entities.toTopLevelTasks()
        }.flowOn(mappingDispatcher)

    override fun getLogbookTasks(): Flow<List<Task>> =
        logbookPrefsStore.getPrefs()
            .map { if (it.enabled) DateUtils.isoDaysAgo(it.retentionDays) else "" }
            .distinctUntilChanged()
            .flatMapLatest { cutoff ->
                taskDao.getLogbookTasks(cutoff).distinctUntilChanged().map { entities ->
                    entities.toTopLevelTasks()
                }
            }
            .flowOn(mappingDispatcher)

    override fun getByProjectId(projectId: Long): Flow<List<Task>> =
        taskDao.getByProjectId(projectId).distinctUntilChanged().map { entities ->
            entities.toTopLevelTasks()
        }.flowOn(mappingDispatcher)

    override fun getById(id: Long): Flow<Task?> =
        taskDao.getById(id).map { entity ->
            entity
                ?.takeUnless { it.isMetadata }
                ?.let { with(taskMapper) { it.toDomain() } }
        }.flowOn(mappingDispatcher)

    override fun searchTasks(query: String): Flow<List<Task>> {
        val text = query.trim()
        if (text.isEmpty()) return flowOf(emptyList())
        return taskDao.search(SqlLike.contains(text)).distinctUntilChanged().map { entities ->
            entities.toTasks()
        }.flowOn(mappingDispatcher)
    }

    override fun getAllOpenTasksFlat(): Flow<List<Task>> =
        taskDao.getAllOpenTasks().distinctUntilChanged().map { entities ->
            entities.toTasks()
        }.flowOn(mappingDispatcher)

    override fun getAllTasksFlat(): Flow<List<Task>> =
        taskDao.getAllTasksFlow().distinctUntilChanged().map { entities ->
            entities.toTasks()
        }.flowOn(mappingDispatcher)

    override suspend fun create(task: Task): NetworkResult<Task> {
        return try {
            val createDto = with(taskMapper) { task.toCreateDto() }
            val responseDto = api.createTask(task.projectId, createDto)
            val responseEntity = with(taskMapper) { responseDto.toEntity() }
            taskDao.upsert(responseEntity)
            val created = with(taskMapper) { responseEntity.toDomain() }
            platformHooks.scheduleAlarm(created)
            // The server puts a new task at the top; moving it to the end happens in the
            // background, so the create returns as soon as the task exists. Metadata tasks
            // (routine carriers) are hidden from every list, so their position does not matter.
            if (!CustomListEnvelope.isAnyMetadataTask(task.description)) {
                positioner.anchorAtEndInBackground(task.projectId, created.id)
            }
            platformHooks.updateWidgets()
            NetworkResult.Success(created)
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
                val tempId = tempIds.next()
                // A timeout or a 5xx may have created the task after all: the sync looks for it
                // before creating it again.
                if (mayHaveReachedServer(e)) tempIds.markCreateMaybeSent(tempId)
                val localTask = task.copy(
                    id = tempId,
                    created = DateUtils.nowIso(),
                    updated = DateUtils.nowIso(),
                )
                val dto = with(taskMapper) { localTask.toDto() }
                val entity = with(taskMapper) { dto.toEntity() }
                taskDao.upsert(entity)
                queueTaskAction(tempId, "create", json.encodeToString(Task.serializer(), localTask))
                // The reminders must fire without waiting for the sync. The alarm is keyed by the
                // temporary id; the sync cancels it and schedules the real one when the create replays.
                platformHooks.scheduleAlarm(localTask)
                platformHooks.updateWidgets()
                NetworkResult.Success(localTask)
            } else {
                NetworkResult.Error(e.message ?: "Failed to create task")
            }
        }
    }

    override suspend fun update(task: Task): NetworkResult<Task> {
        if (task.id < 0L) {
            return when (val outcome = changeLocalTask(task.id) { updateLocalTask(task) }) {
                is LocalChange.Done -> outcome.result
                is LocalChange.Created -> update(task.copy(id = outcome.realId))
            }
        }
        val previous = taskDao.getByIdSync(task.id)
        val previousTask = previous?.let { with(taskMapper) { it.toDomain() } }
        val patch = MergePatches.task(previousTask, task)
        taskDao.upsert(optimisticEntity(previous, task))
        if (previous != null && previous.projectId != task.projectId) {
            // The task changes lists: what is remembered about where each list ends is out of date.
            positioner.invalidate(previous.projectId)
            positioner.invalidate(task.projectId)
            // A position belongs to one list view: the old one means nothing in the new list. 0 is
            // "not known", so a list drawn in manual order asks the server for the real one.
            taskDao.updatePosition(task.id, 0.0)
        }

        if (patch.isEmpty()) return NetworkResult.Success(task)

        return writeGate.sendOrQueue(
            taskId = task.id,
            send = { sendUpdate(task, patch) },
            queue = {
                queueTaskAction(task.id, "update", queuedUpdatePayload(task, patch))
                // The reminders may have changed with this edit; the alarms follow the local row.
                platformHooks.scheduleAlarm(task)
                platformHooks.updateWidgets()
                NetworkResult.Success(task)
            },
            refused = { e ->
                if (dropIfDeleted(task.id, e)) {
                    NetworkResult.Error(TASK_DELETED_ELSEWHERE)
                } else {
                    previous?.let { taskDao.upsert(it) }
                    NetworkResult.Error(e.message ?: "Failed to update task")
                }
            },
        )
    }

    /** An edit of a task that only exists here: the local row changes and the queued create takes it. */
    private suspend fun updateLocalTask(task: Task): NetworkResult<Task> {
        // Gone without having been created (deleted here, or its create was discarded): writing
        // it would bring back a task nobody can sync.
        val previous = taskDao.getByIdSync(task.id)
            ?: return NetworkResult.Error("This task is no longer on this device")
        taskDao.upsert(optimisticEntity(previous, task))
        if (previous.projectId != task.projectId) {
            positioner.invalidate(previous.projectId)
            positioner.invalidate(task.projectId)
            taskDao.updatePosition(task.id, 0.0)
        }
        queueTaskAction(task.id, "update", json.encodeToString(Task.serializer(), task))
        // A task that only exists here: its reminders may have changed with this edit.
        platformHooks.scheduleAlarm(task)
        platformHooks.updateWidgets()
        return NetworkResult.Success(task)
    }

    private suspend fun sendUpdate(task: Task, patch: JsonObject): NetworkResult<Task> {
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
        return NetworkResult.Success(updated)
    }

    override suspend fun getByIds(ids: Set<Long>): List<Task> =
        taskDao.getByIds(ids.toList())
            .filterNot { it.isMetadata }
            .map { with(taskMapper) { it.toDomain() } }

    override suspend fun applyScheduleAction(taskId: Long): NetworkResult<Task> {
        // Always start from the stored row: the swipe that triggered this may belong to a row
        // that was composed before the task changed elsewhere. update() diffs against the same
        // row, so the patch holds only the field the action sets.
        val current = taskDao.getByIdSync(currentId(taskId))?.let { with(taskMapper) { it.toDomain() } }
            ?: return NetworkResult.Error("Task $taskId is not in the local cache")
        val action = behaviorPrefsStore.getPrefs().first().scheduleAction
        val updated = when (action) {
            ScheduleAction.DUE_TODAY -> {
                val day = dayClock.day.value
                current.copy(dueDate = DueDates.today(day.date, day.zone).toString())
            }
            ScheduleAction.PRIORITY_URGENT -> current.copy(priority = 4)
        }
        return update(updated)
    }

    override suspend fun scheduleDue(taskId: Long, due: QuickDue): NetworkResult<Task> {
        val current = taskDao.getByIdSync(currentId(taskId))?.let { with(taskMapper) { it.toDomain() } }
            ?: return NetworkResult.Error("Task $taskId is not in the local cache")
        val day = dayClock.day.value
        val instant = when (due) {
            QuickDue.TODAY -> DueDates.today(day.date, day.zone)
            QuickDue.TOMORROW -> DueDates.tomorrow(day.date, day.zone)
        }
        return update(current.copy(dueDate = instant.toString()))
    }

    override suspend fun moveToProject(taskId: Long, newProjectId: Long): NetworkResult<Unit> {
        val entity = taskDao.getByIdSync(currentId(taskId))
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
        val root = taskDao.getByIdSync(currentId(taskId))?.let { with(taskMapper) { it.toDomain() } }
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
        val heldId = taskId
        @Suppress("NAME_SHADOWING")
        val taskId = currentId(heldId)
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
        completionBatchesMutex.withLock {
            completionBatches.remove(taskId)
            completionBatches.remove(heldId)
        }
        return deleteSingle(taskId)
    }

    private suspend fun deleteSingle(taskId: Long): NetworkResult<Unit> {
        if (taskId < 0L) {
            val outcome = changeLocalTask(taskId) {
                platformHooks.cancelAlarm(taskId)
                taskDao.deleteById(taskId)
                queueTaskAction(taskId, "delete", "")
                platformHooks.updateWidgets()
                NetworkResult.Success(Unit)
            }
            return when (outcome) {
                is LocalChange.Done -> outcome.result
                is LocalChange.Created -> deleteSingle(outcome.realId)
            }
        }
        platformHooks.cancelAlarm(taskId)
        taskDao.deleteById(taskId)
        return writeGate.sendOrQueue(
            taskId = taskId,
            send = {
                api.deleteTask(taskId)
                platformHooks.updateWidgets()
                NetworkResult.Success(Unit)
            },
            queue = {
                queueTaskAction(taskId, "delete", "")
                platformHooks.updateWidgets()
                NetworkResult.Success(Unit)
            },
            refused = { e -> NetworkResult.Error(e.message ?: "Failed to delete task") },
        )
    }

    override suspend fun createSubtask(parentTaskId: Long, subtask: Task): NetworkResult<Task> {
        @Suppress("NAME_SHADOWING")
        val parentTaskId = currentId(parentTaskId)
        // A parent that only exists on this device cannot be linked on the server yet.
        if (parentTaskId < 0L) return queueSubtaskCreate(parentTaskId, subtask)
        var createdOnServer: Long? = null
        return try {
            val createDto = with(taskMapper) { subtask.toCreateDto() }
            val createdDto = api.createTask(subtask.projectId, createDto)
            createdOnServer = createdDto.id

            api.createRelation(
                parentTaskId,
                com.rendyhd.vicu.data.remote.api.CreateRelationDto(
                    otherTaskId = createdDto.id,
                    relationKind = RelationKind.SUBTASK,
                ),
            )
            val createdEntity = with(taskMapper) { createdDto.toEntity() }
            taskDao.upsert(createdEntity)

            val linkedChildEntity = taskDao.getByIdSync(parentTaskId)?.let { parent ->
                with(taskMapper) {
                    taskDao.upsert(parent.withRelatedTaskAdded(RelationKind.SUBTASK, createdDto))
                    createdEntity.withRelatedTaskAdded(RelationKind.PARENTTASK, parent.toDomain().toDto())
                }
            } ?: createdEntity
            taskDao.upsert(linkedChildEntity)

            val createdSubtask = with(taskMapper) { linkedChildEntity.toDomain() }
            // Like any new task: its reminders are armed, and it goes to the end of its list.
            platformHooks.scheduleAlarm(createdSubtask)
            if (!CustomListEnvelope.isAnyMetadataTask(subtask.description)) {
                positioner.anchorAtEndInBackground(subtask.projectId, createdSubtask.id)
            }
            platformHooks.updateWidgets()
            NetworkResult.Success(createdSubtask)
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
                // Offline, or the server is down. This also covers a task that was created but
                // could not be linked: the sync engine takes that task (by its id) instead of
                // creating a second one, and links it.
                queueSubtaskCreate(parentTaskId, subtask, createdOnServer, maybeSent = mayHaveReachedServer(e))
            } else {
                NetworkResult.Error(e.message ?: "Failed to create subtask")
            }
        }
    }

    /**
     * Stores a subtask that could not be created on the server yet: a local row with a temporary
     * id, shown under its parent, and a queued create that carries the parent so the sync engine
     * links the two once both exist.
     */
    private suspend fun queueSubtaskCreate(
        parentTaskId: Long,
        subtask: Task,
        /** The server's id when the create went through and only the link failed. */
        createdOnServer: Long? = null,
        /** An attempt that may have created the task without an answer. */
        maybeSent: Boolean = false,
    ): NetworkResult<Task> {
        val parentEntity = taskDao.getByIdSync(parentTaskId)
        val parentStub = parentEntity
            ?.let { with(taskMapper) { it.toDomain() } }
            ?.copy(relatedTasks = emptyMap(), attachments = emptyList())
            ?: Task(id = parentTaskId, title = "", projectId = subtask.projectId)
        val tempId = tempIds.next()
        if (createdOnServer != null) {
            tempIds.rememberRealId(tempId, createdOnServer)
        } else if (maybeSent) {
            tempIds.markCreateMaybeSent(tempId)
        }
        val now = DateUtils.nowIso()
        val localTask = subtask.copy(
            id = tempId,
            created = now,
            updated = now,
            relatedTasks = subtask.relatedTasks + (RelationKind.PARENTTASK to listOf(parentStub)),
        )
        val localDto = with(taskMapper) { localTask.toDto() }
        taskDao.upsert(with(taskMapper) { localDto.toEntity() })
        parentEntity?.let { parent ->
            taskDao.upsert(with(taskMapper) { parent.withRelatedTaskAdded(RelationKind.SUBTASK, localDto) })
        }
        queueTaskAction(tempId, "create", json.encodeToString(Task.serializer(), localTask))
        platformHooks.updateWidgets()
        return NetworkResult.Success(localTask)
    }

    override suspend fun toggleSubtaskDone(parentTaskId: Long, subtask: Task): NetworkResult<Task> {
        return toggleTaskTree(current(subtask), currentId(parentTaskId))
    }

    /** [task], or the created task it became when its create has gone through since it was read. */
    private suspend fun current(task: Task): Task {
        val id = currentId(task.id)
        if (id == task.id) return task
        return taskDao.getByIdSync(id)?.let { with(taskMapper) { it.toDomain() } } ?: task.copy(id = id)
    }

    private suspend fun toggleTaskTree(
        task: Task,
        explicitParentId: Long? = null,
        /** Store and queue every change without sending it; see [setDoneInBackground]. */
        queueOnly: Boolean = false,
    ): NetworkResult<Task> {
        val targetDone = !task.done
        if (!targetDone) {
            val batch = completionBatchesMutex.withLock { completionBatches.remove(task.id) }.orEmpty()
            val rootResult = if (explicitParentId != null) {
                setLinkedTaskDone(explicitParentId, task, targetDone = false, playSound = false, queueOnly = queueOnly)
            } else {
                setTaskDone(task, targetDone = false, playSound = false, queueOnly = queueOnly)
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
                        queueOnly = queueOnly,
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
                queueOnly = queueOnly,
            )) {
                is NetworkResult.Success -> completed += link
                is NetworkResult.Error -> {
                    rollbackAutoCompleted(completed, queueOnly)
                    return childResult
                }
                NetworkResult.Loading -> Unit
            }
        }

        val rootResult = if (explicitParentId != null) {
            setLinkedTaskDone(explicitParentId, task, targetDone = true, playSound = true, queueOnly = queueOnly)
        } else {
            setTaskDone(task, targetDone = true, playSound = true, queueOnly = queueOnly)
        }
        if (rootResult is NetworkResult.Success) {
            completionBatchesMutex.withLock { completionBatches[task.id] = autoCompleted }
        } else {
            rollbackAutoCompleted(completed, queueOnly)
        }
        return rootResult
    }

    private suspend fun rollbackAutoCompleted(completed: List<DescendantLink>, queueOnly: Boolean) {
        completed.forEach { link ->
            val current = taskDao.getByIdSync(link.task.id)
                ?.let { with(taskMapper) { it.toDomain() } }
                ?: link.task.copy(done = true)
            if (current.done) {
                setLinkedTaskDone(link.parentId, current, targetDone = false, playSound = false, queueOnly = queueOnly)
            }
        }
    }

    private suspend fun setLinkedTaskDone(
        parentTaskId: Long,
        subtask: Task,
        targetDone: Boolean,
        playSound: Boolean,
        queueOnly: Boolean = false,
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

        val patch = MergePatches.taskDone(toggled.done)
        if (subtask.id < 0L) {
            val outcome = changeLocalTask(subtask.id) {
                // Read again under the lock: a row the sync has just replaced must not come back.
                taskDao.getByIdSync(subtask.id)?.let {
                    taskDao.upsert(it.copy(done = toggled.done, doneAt = DateUtils.normalizeToUtc(toggled.doneAt)))
                }
                queueTaskAction(
                    subtask.id,
                    "toggle_done",
                    queuedUpdatePayload(toggled, patch),
                )
                if (toggled.done) platformHooks.cancelAlarm(subtask.id)
                platformHooks.updateWidgets()
                NetworkResult.Success(toggled)
            }
            return when (outcome) {
                is LocalChange.Done -> outcome.result
                is LocalChange.Created ->
                    setLinkedTaskDone(parentTaskId, subtask.copy(id = outcome.realId), targetDone, playSound = false, queueOnly = queueOnly)
            }
        }
        cached?.let {
            taskDao.upsert(it.copy(done = toggled.done, doneAt = DateUtils.normalizeToUtc(toggled.doneAt)))
        }
        val queue: suspend () -> NetworkResult<Task> = {
            queueTaskAction(
                subtask.id,
                "toggle_done",
                queuedUpdatePayload(toggled, patch),
            )
            if (toggled.done) platformHooks.cancelAlarm(subtask.id)
            platformHooks.updateWidgets()
            NetworkResult.Success(toggled)
        }
        if (queueOnly) return queue()
        return writeGate.sendOrQueue(
            taskId = subtask.id,
            send = {
                val responseDto = api.updateTask(subtask.id, patch)
                val responseEntity = with(taskMapper) { responseDto.toEntity() }
                taskDao.upsert(responseEntity)
                val result = with(taskMapper) { responseEntity.toDomain() }
                if (targetDone) noteAdvanced(current, result)
                updateParentDoneReferences(result, responseDto.done, parentTaskId)
                if (result.done) platformHooks.cancelAlarm(subtask.id) else platformHooks.scheduleAlarm(result)
                platformHooks.updateWidgets()
                NetworkResult.Success(result)
            },
            queue = queue,
            refused = { e ->
                if (dropIfDeleted(subtask.id, e)) {
                    NetworkResult.Error(TASK_DELETED_ELSEWHERE)
                } else {
                    cached?.let { taskDao.upsert(it) }
                    updateParentDoneReferences(current, current.done, parentTaskId)
                    NetworkResult.Error(e.message ?: "Failed to update subtask")
                }
            },
        )
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
        // Read, change and write one parent row at a time: siblings completed together (a bulk
        // complete runs several at once) would otherwise overwrite each other's change.
        parentReferenceMutex.withLock {
            parentIds.forEach { parentId ->
                taskDao.getByIdSync(parentId)?.let { parent ->
                    taskDao.upsert(with(taskMapper) { parent.withRelatedTaskDone(task.id, done) })
                }
            }
        }
    }

    override suspend fun createRelation(
        taskId: Long,
        otherTaskId: Long,
        relationKind: String,
    ): NetworkResult<Unit> {
        @Suppress("NAME_SHADOWING")
        val taskId = currentId(taskId)
        @Suppress("NAME_SHADOWING")
        val otherTaskId = currentId(otherTaskId)
        return try {
            api.createRelation(
                taskId,
                com.rendyhd.vicu.data.remote.api.CreateRelationDto(
                    otherTaskId = otherTaskId,
                    relationKind = relationKind,
                ),
            )
            val otherDto = try {
                api.getTask(otherTaskId).also { storeUnlessProtected(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val baseEntity = taskDao.getByIdSync(taskId)
            if (otherDto != null && baseEntity != null) {
                taskDao.upsert(with(taskMapper) { baseEntity.withRelatedTaskAdded(relationKind, otherDto) })
            } else {
                storeUnlessProtected(api.getTask(taskId))
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
        @Suppress("NAME_SHADOWING")
        val taskId = currentId(taskId)
        @Suppress("NAME_SHADOWING")
        val otherTaskId = currentId(otherTaskId)
        return try {
            api.deleteRelation(taskId, relationKind, otherTaskId)
            taskDao.getByIdSync(taskId)?.let { base ->
                taskDao.upsert(with(taskMapper) { base.withRelatedTaskRemoved(relationKind, otherTaskId) })
            }
            try {
                storeUnlessProtected(api.getTask(otherTaskId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Failed to delete relation")
        }
    }

    /**
     * Stores a task read back from the server, unless its local row holds changes the server does
     * not have yet (queued or failed): like the refreshers, the queued values stay on screen until
     * they have been sent.
     */
    private suspend fun storeUnlessProtected(dto: com.rendyhd.vicu.data.remote.api.TaskDto) {
        if (dto.id in pendingActionDao.getTaskIdsWithPendingActions()) return
        taskDao.upsert(with(taskMapper) { dto.toEntity() })
    }

    override suspend fun toggleDone(task: Task): NetworkResult<Task> {
        return toggleTaskTree(current(task))
    }

    override suspend fun setDone(taskId: Long, done: Boolean): NetworkResult<Task> {
        val current = taskDao.getByIdSync(currentId(taskId))?.let { with(taskMapper) { it.toDomain() } }
            ?: return NetworkResult.Error("Task $taskId is not in the local cache")
        if (current.done == done) {
            // A repeating task comes back from its completion open: undoing it moves its dates back.
            if (!done) undoAdvance(current)?.let { return it }
            return NetworkResult.Success(current)
        }
        // The task is not in the requested state, so flipping it reaches that state; this goes
        // through the same path (subtask cascade, queueing) as completing from a list.
        return toggleTaskTree(current)
    }

    override suspend fun setDoneInBackground(taskId: Long, done: Boolean): NetworkResult<Task> {
        val current = taskDao.getByIdSync(currentId(taskId))?.let { with(taskMapper) { it.toDomain() } }
            ?: return NetworkResult.Error("Task $taskId is not in the local cache")
        if (current.done == done) return NetworkResult.Success(current)
        return toggleTaskTree(current, queueOnly = true)
    }

    private suspend fun setTaskDone(
        task: Task,
        targetDone: Boolean,
        playSound: Boolean,
        queueOnly: Boolean = false,
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
            val outcome = changeLocalTask(task.id) {
                // Read again under the lock: a row the sync has just replaced must not come back.
                val local = taskDao.getByIdSync(task.id)
                    ?: return@changeLocalTask NetworkResult.Error("This task is no longer on this device")
                taskDao.upsert(optimisticDoneEntity(local, toggled))
                queueTaskAction(
                    task.id,
                    "toggle_done",
                    queuedUpdatePayload(toggled, patch),
                )
                updateParentDoneReferences(toggled, toggled.done)
                if (toggled.done) platformHooks.cancelAlarm(task.id)
                platformHooks.updateWidgets()
                NetworkResult.Success(toggled)
            }
            return when (outcome) {
                is LocalChange.Done -> outcome.result
                is LocalChange.Created ->
                    setTaskDone(task.copy(id = outcome.realId), targetDone, playSound = false, queueOnly = queueOnly)
            }
        }
        val queue: suspend () -> NetworkResult<Task> = {
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
        }
        if (queueOnly) return queue()
        return writeGate.sendOrQueue(
            taskId = task.id,
            send = {
                val responseDto = api.updateTask(task.id, patch)
                val responseEntity = with(taskMapper) { responseDto.toEntity() }
                // Store what the server answered: the task as it is now, with its relations and
                // attachments. A repeating task comes back still open with its next due date.
                // Lists keep the row on screen themselves for a moment (CompletionHold).
                taskDao.upsert(responseEntity)
                val result = with(taskMapper) { responseEntity.toDomain() }
                if (targetDone) noteAdvanced(current, result)
                updateParentDoneReferences(toggled, responseDto.done)
                if (result.done) {
                    platformHooks.cancelAlarm(task.id)
                } else {
                    platformHooks.scheduleAlarm(result)
                }
                platformHooks.updateWidgets()
                NetworkResult.Success(result)
            },
            queue = queue,
            refused = { e ->
                if (dropIfDeleted(task.id, e)) {
                    NetworkResult.Error(TASK_DELETED_ELSEWHERE)
                } else {
                    NetworkResult.Error(e.message ?: "Failed to toggle task")
                }
            },
        )
    }

    override suspend fun deleteLocalByIds(ids: Set<Long>) {
        if (ids.isNotEmpty()) taskDao.deleteByIds(ids.toList())
    }

    override suspend fun refreshAll(filters: Map<String, String>, full: Boolean): NetworkResult<Unit> {
        Logger.d(TAG, "refreshAll() called with filters=$filters full=$full")
        return try {
            if (filters.isEmpty()) {
                refresher.refresh(forceFull = full)
            } else {
                refresher.refreshMatching(filters)
            }
            NetworkResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "refreshAll() FAILED: ${e.message}", e)
            refreshFailure(e, "Failed to refresh tasks")
        }
    }

    override suspend fun loadLogbookPage(page: Int): NetworkResult<LogbookPage> {
        return try {
            val prefs = logbookPrefsStore.getPrefs().first()
            // Only the retention window is fetched when it is set; seconds are enough for a cutoff.
            val completedSince = if (prefs.enabled) {
                DateUtils.parseIsoDate(DateUtils.isoDaysAgo(prefs.retentionDays))
                    ?.let { Instant.fromEpochSeconds(it.epochSeconds).toString() }
            } else {
                null
            }
            val loaded = refresher.loadCompletedPage(page, LOGBOOK_PAGE_SIZE, completedSince)
            NetworkResult.Success(LogbookPage(page, loaded.hasMore))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "loadLogbookPage($page) FAILED: ${e.message}", e)
            refreshFailure(e, "Failed to load completed tasks")
        }
    }

    private fun refreshFailure(e: Exception, fallback: String): NetworkResult.Error {
        val offline = isNetworkFailure(e)
        return NetworkResult.Error(
            message = if (offline) "Can't reach the server" else e.message?.takeIf { it.isNotBlank() } ?: fallback,
            code = (e as? VikunjaApiException)?.httpStatus,
            offline = offline,
        )
    }
}
