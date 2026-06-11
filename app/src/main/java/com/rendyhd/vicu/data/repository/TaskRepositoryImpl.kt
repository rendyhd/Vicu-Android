package com.rendyhd.vicu.data.repository

import android.content.Context
import android.util.Log
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.LogbookPrefsStore
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.api.TaskPositionDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.notification.AlarmScheduler
import com.rendyhd.vicu.util.CompletionSoundPlayer
import com.rendyhd.vicu.util.Constants
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.isRetriableNetworkError
import com.rendyhd.vicu.widget.WidgetUpdateScheduler
import com.rendyhd.vicu.worker.SyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import com.rendyhd.vicu.data.local.ScheduleAction
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class TaskRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskDao: TaskDao,
    private val api: VikunjaApiService,
    private val pendingActionDao: PendingActionDao,
    private val taskMapper: TaskMapper,
    private val alarmScheduler: AlarmScheduler,
    private val completionSoundPlayer: CompletionSoundPlayer,
    private val json: Json,
    private val behaviorPrefsStore: BehaviorPrefsStore,
    private val logbookPrefsStore: LogbookPrefsStore,
) : TaskRepository {

    companion object {
        private const val TAG = "TaskRepoImpl"
    }

    private val tempIdCounter = AtomicLong(-(System.currentTimeMillis() / 1000))

    /**
     * Best-effort: after a successful task create, find the project's list view and POST
     * the new task to the bottom of it. Vikunja position is per-view, so this is a separate
     * round-trip. Any failure is logged and swallowed — the task is already created.
     */
    private suspend fun anchorNewTaskAtEnd(projectId: Long, newTaskId: Long) {
        if (projectId <= 0L) return
        try {
            val views = api.getProjectViews(projectId)
            val listView = views.firstOrNull { it.viewKind == "list" } ?: return
            // One row, highest position — avoids paging the whole view (and the old code
            // only read page 1 anyway, which anchored mid-list in projects with >50 tasks).
            val existing = api.getViewTasks(
                projectId,
                listView.id,
                mapOf("sort_by" to "position", "order_by" to "desc", "per_page" to "1"),
            )
            val maxPos = existing.firstOrNull()?.position ?: 0.0
            api.updateTaskPosition(
                newTaskId,
                TaskPositionDto(position = maxPos + 65_536.0, projectViewId = listView.id),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "anchorNewTaskAtEnd failed (non-fatal) for project=$projectId task=$newTaskId", e)
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
        SyncScheduler.enqueueWhenOnline(context)
    }

    override fun getInboxTasks(inboxProjectId: Long): Flow<List<Task>> =
        behaviorPrefsStore.getPrefs()
            .map { it.inboxExcludeDated }
            .distinctUntilChanged()
            .flatMapLatest { excludeDated ->
                taskDao.getInboxTasks(inboxProjectId, includeDated = !excludeDated).distinctUntilChanged().map { entities ->
                    entities.map { with(taskMapper) { it.toDomain() } }
                }
            }

    override fun getTodayTasks(): Flow<List<Task>> =
        DateUtils.endOfTodayFlow()
            .distinctUntilChanged()
            .flatMapLatest { endOfToday ->
                taskDao.getTodayTasks(endOfToday).distinctUntilChanged().map { entities ->
                    entities.map { with(taskMapper) { it.toDomain() } }
                }
            }

    override fun getUpcomingTasks(): Flow<List<Task>> =
        DateUtils.endOfTodayFlow()
            .distinctUntilChanged()
            .flatMapLatest { endOfToday ->
                taskDao.getUpcomingTasks(endOfToday).distinctUntilChanged().map { entities ->
                    entities.map { with(taskMapper) { it.toDomain() } }
                }
            }

    override fun getAnytimeTasks(inboxProjectId: Long): Flow<List<Task>> =
        taskDao.getAnytimeTasks(inboxProjectId).distinctUntilChanged().map { entities ->
            entities.map { with(taskMapper) { it.toDomain() } }
        }

    override fun getLogbookTasks(): Flow<List<Task>> =
        logbookPrefsStore.getPrefs()
            .map { if (it.enabled) DateUtils.isoDaysAgo(it.retentionDays) else "" }
            .distinctUntilChanged()
            .flatMapLatest { cutoff ->
                taskDao.getLogbookTasks(cutoff).distinctUntilChanged().map { entities ->
                    entities.map { with(taskMapper) { it.toDomain() } }
                }
            }

    override fun getByProjectId(projectId: Long): Flow<List<Task>> =
        taskDao.getByProjectId(projectId).distinctUntilChanged().map { entities ->
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

    override fun searchByTitleIncludingDone(query: String): Flow<List<Task>> =
        taskDao.searchByTitleIncludingDone(query).map { list ->
            list.map { with(taskMapper) { it.toDomain() } }
        }

    override fun getAllOpenTasks(): Flow<List<Task>> =
        taskDao.getAllOpenTasks().distinctUntilChanged().map { entities ->
            entities.map { with(taskMapper) { it.toDomain() } }
        }

    override fun getAllTasks(): Flow<List<Task>> =
        taskDao.getAllTasksFlow().distinctUntilChanged().map { entities ->
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
            // Anchor the new task at the bottom of the project's list view. Best-effort:
            // Vikunja's create endpoint defaults position to 0 (top), which makes fresh
            // tasks jump above the user's custom-order list. Mirror desktop b1fb6f7.
            anchorNewTaskAtEnd(task.projectId, created.id)
            WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
            NetworkResult.Success(created)
        } catch (e: CancellationException) {
            throw e
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
        val previous = taskDao.getByIdSync(task.id)
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
        } catch (e: CancellationException) {
            // Optimistic write already done — queue for sync before re-throwing
            withContext(NonCancellable) {
                queueTaskAction(task.id, "update", json.encodeToString(Task.serializer(), task))
                WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
            }
            throw e
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
                // Optimistic write already done above
                queueTaskAction(task.id, "update", json.encodeToString(Task.serializer(), task))
                WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
                NetworkResult.Success(task)
            } else {
                // Roll back the optimistic write on a hard (non-retriable) failure
                // so a server-rejected change doesn't persist locally until a refetch.
                previous?.let { taskDao.upsert(it) }
                NetworkResult.Error(e.localizedMessage ?: "Failed to update task")
            }
        }
    }

    override suspend fun getByIds(ids: Set<Long>): List<Task> =
        taskDao.getByIds(ids.toList()).map { with(taskMapper) { it.toDomain() } }

    override suspend fun applyScheduleAction(task: Task): NetworkResult<Task> {
        val action = behaviorPrefsStore.getPrefs().first().scheduleAction
        val updated = when (action) {
            ScheduleAction.DUE_TODAY -> task.copy(dueDate = DateUtils.todayEndIso())
            ScheduleAction.PRIORITY_URGENT -> task.copy(priority = 4)
        }
        // Sends the COMPLETE Task (Go zero-value problem).
        return update(updated)
    }

    override suspend fun moveToProject(taskId: Long, newProjectId: Long): NetworkResult<Unit> {
        val entity = taskDao.getByIdSync(taskId)
            ?: return NetworkResult.Error("Task $taskId not in local cache; cannot move")
        val task = with(taskMapper) { entity.toDomain() }
        if (task.projectId == newProjectId) return NetworkResult.Success(Unit)
        // Reuse update() so the move goes through the same optimistic + complete-object
        // (Go zero-value) path, including the offline pending-action queue.
        return when (val r = update(task.copy(projectId = newProjectId))) {
            is NetworkResult.Success -> NetworkResult.Success(Unit)
            is NetworkResult.Error -> r
            NetworkResult.Loading -> NetworkResult.Success(Unit)
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
        } catch (e: CancellationException) {
            // Local delete already done — queue for sync before re-throwing
            withContext(NonCancellable) {
                queueTaskAction(taskId, "delete", "")
                WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
            }
            throw e
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

            // Merge the new child into the parent's cached relatedTasks directly rather than
            // re-fetching: GET /tasks/{id} immediately after creating a relation can lag and omit
            // it, which left the first subtask invisible until the next mutation (issue #6).
            taskDao.getByIdSync(parentTaskId)?.let { parent ->
                taskDao.upsert(with(taskMapper) { parent.withRelatedTaskAdded("subtask", createdDto) })
            }

            NetworkResult.Success(with(taskMapper) { createdEntity.toDomain() })
        } catch (e: Exception) {
            NetworkResult.Error(e.localizedMessage ?: "Failed to create subtask")
        }
    }

    override suspend fun toggleSubtaskDone(parentTaskId: Long, subtask: Task): NetworkResult<Task> {
        // Read the child's full cached row first so the toggle is computed from the authoritative
        // state and we preserve its other fields (labels/reminders/attachments) when flipping.
        val cached = taskDao.getByIdSync(subtask.id)
        val current = cached?.let { with(taskMapper) { it.toDomain() } } ?: subtask
        val toggled = current.copy(
            done = !current.done,
            doneAt = if (!current.done) DateUtils.nowIso() else "",
        )
        if (toggled.done) {
            completionSoundPlayer.play()
        }

        // Optimistic local writes. UNLIKE the list toggleDone path (which keeps Room untouched so
        // a row stays visible for undo), the detail sheet has no completedTaskIds fallback — the
        // subtask checkbox reads done from the parent's cached relatedTasks, so we must flip it
        // here or it never updates (issue #6). Parent cache first so the first Room emission
        // already carries the new state, then the child's own row (other fields preserved).
        taskDao.getByIdSync(parentTaskId)?.let { parent ->
            taskDao.upsert(with(taskMapper) { parent.withRelatedTaskDone(subtask.id, toggled.done) })
        }
        cached?.let {
            taskDao.upsert(it.copy(done = toggled.done, doneAt = DateUtils.normalizeToUtc(toggled.doneAt)))
        }

        return try {
            val responseDto = api.updateTask(subtask.id, with(taskMapper) { toggled.toDto() })
            val responseEntity = with(taskMapper) { responseDto.toEntity() }
            taskDao.upsert(responseEntity)
            taskDao.getByIdSync(parentTaskId)?.let { parent ->
                taskDao.upsert(with(taskMapper) { parent.withRelatedTaskDone(subtask.id, responseDto.done) })
            }
            val result = with(taskMapper) { responseEntity.toDomain() }
            if (toggled.done) alarmScheduler.cancelForTask(subtask.id) else alarmScheduler.scheduleForTask(result)
            WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
            NetworkResult.Success(result)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                queueTaskAction(subtask.id, "toggle_done", json.encodeToString(Task.serializer(), toggled))
                if (toggled.done) alarmScheduler.cancelForTask(subtask.id)
                WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
            }
            throw e
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
                queueTaskAction(subtask.id, "toggle_done", json.encodeToString(Task.serializer(), toggled))
                if (toggled.done) alarmScheduler.cancelForTask(subtask.id)
                WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
                NetworkResult.Success(toggled)
            } else {
                // Hard failure — roll back the optimistic writes.
                cached?.let { taskDao.upsert(it) }
                taskDao.getByIdSync(parentTaskId)?.let { parent ->
                    taskDao.upsert(with(taskMapper) { parent.withRelatedTaskDone(subtask.id, current.done) })
                }
                NetworkResult.Error(e.localizedMessage ?: "Failed to update subtask")
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
            // Fetch the other task so we can show the relation with its real title and refresh
            // its cache (Vikunja auto-creates the reciprocal on it), then merge it into the base
            // task's cached relatedTasks directly. We deliberately do NOT re-fetch the base task:
            // GET /tasks/{id} right after creating a relation can lag and omit the new relation,
            // which made it briefly show stale/wrong data (e.g. the base's own title) until a
            // reload (issue #6).
            val otherDto = try {
                api.getTask(otherTaskId).also { taskDao.upsert(with(taskMapper) { it.toEntity() }) }
            } catch (e: Exception) {
                null
            }
            val baseEntity = taskDao.getByIdSync(taskId)
            if (otherDto != null && baseEntity != null) {
                taskDao.upsert(with(taskMapper) { baseEntity.withRelatedTaskAdded(relationKind, otherDto) })
            } else {
                // Couldn't merge locally — fall back to a server re-fetch of the base task.
                val dto = api.getTask(taskId)
                taskDao.upsert(with(taskMapper) { dto.toEntity() })
            }
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            NetworkResult.Error(e.localizedMessage ?: "Failed to create relation")
        }
    }

    override suspend fun deleteRelation(
        taskId: Long,
        relationKind: String,
        otherTaskId: Long,
    ): NetworkResult<Unit> {
        return try {
            api.deleteRelation(taskId, relationKind, otherTaskId)
            // Optimistically drop it from the base task's cached relatedTasks so it disappears
            // immediately (same eventual-consistency reasoning as createRelation). Removing the
            // relation only unlinks the tasks — the other task itself is NOT deleted.
            taskDao.getByIdSync(taskId)?.let { base ->
                taskDao.upsert(with(taskMapper) { base.withRelatedTaskRemoved(relationKind, otherTaskId) })
            }
            // Refresh the other task's cache (its reciprocal relation was removed server-side).
            try {
                val otherDto = api.getTask(otherTaskId)
                taskDao.upsert(with(taskMapper) { otherDto.toEntity() })
            } catch (e: Exception) {
                // Best-effort.
            }
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            NetworkResult.Error(e.localizedMessage ?: "Failed to delete relation")
        }
    }

    override suspend fun toggleDone(task: Task): NetworkResult<Task> {
        val toggled = task.copy(
            done = !task.done,
            doneAt = if (!task.done) DateUtils.nowIso() else "",
        )
        // Play completion sound on undone → done transition, regardless of network
        // outcome — the user has visually confirmed the toggle and the offline
        // path will sync later. Failure inside the player is silent (see its impl).
        if (toggled.done) {
            completionSoundPlayer.play()
        }
        return try {
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
        } catch (e: CancellationException) {
            // API call cancelled — persist to Room and queue for sync before re-throwing
            withContext(NonCancellable) {
                val dto = with(taskMapper) { toggled.toDto() }
                val entity = with(taskMapper) { dto.toEntity() }
                taskDao.upsert(entity)
                queueTaskAction(task.id, "toggle_done", json.encodeToString(Task.serializer(), toggled))
                if (toggled.done) {
                    alarmScheduler.cancelForTask(task.id)
                }
                WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
            }
            throw e
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
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
        if (ids.isNotEmpty()) taskDao.deleteByIds(ids.toList())
    }

    override suspend fun refreshAll(filters: Map<String, String>): NetworkResult<Unit> {
        Log.d(TAG, "refreshAll() called with filters=$filters")
        return try {
            val allTasks = mutableListOf<com.rendyhd.vicu.data.remote.api.TaskDto>()
            var page = 1
            val maxPages = 100
            while (page <= maxPages) {
                val params = buildMap {
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
            // Skip tasks with pending local modifications to avoid overwriting unsynced changes
            val pendingTaskIds = pendingActionDao.getTaskIdsWithPendingActions().toSet()
            val existingById = taskDao.getAllSync().associateBy { it.id }
            val safeEntities = entities.filter { it.id !in pendingTaskIds }
            // Only write rows that actually changed — unconditional upserts invalidate every
            // observing Flow and re-trigger full-list JSON decoding on all alive screens.
            val changed = safeEntities.filter { existingById[it.id] != it }
            taskDao.upsertAll(changed)
            var alarmsTouched = changed.any { e ->
                val old = existingById[e.id]
                old == null || old.remindersJson != e.remindersJson ||
                    old.dueDate != e.dueDate || old.done != e.done
            }
            // Only prune on a FULL fetch — a filtered fetch returns a subset, so deleting
            // "missing" tasks would wrongly drop everything outside the filter. Keep pending
            // (e.g. locally-created temp-id) tasks regardless.
            if (filters.isEmpty()) {
                val serverTaskIds = allTasks.map { it.id }.toSet() + pendingTaskIds
                val deletedIds = existingById.keys - serverTaskIds
                if (deletedIds.isNotEmpty()) {
                    taskDao.deleteNotIn(serverTaskIds)
                    alarmsTouched = true
                }
            }
            // Alarm registration costs ~100 PendingIntent ops per reminder-task — only pay it
            // when reminder-relevant fields actually changed.
            if (alarmsTouched) alarmScheduler.rescheduleAll()
            WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
            Log.d(TAG, "refreshAll() SUCCESS: upserted ${changed.size} changed tasks (skipped ${entities.size - safeEntities.size} with pending actions)")
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "refreshAll() FAILED: ${e.message}", e)
            NetworkResult.Error(e.localizedMessage ?: "Failed to refresh tasks")
        }
    }
}
