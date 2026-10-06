package com.rendyhd.vicu.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.dao.ProjectDao
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.local.entity.ProjectEntity
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-memory [DataStore] so the preference stores can run in plain unit tests. */
class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

/**
 * In-memory [TaskDao]. Only the queries the repository and sync code under test use are
 * implemented; the list queries return empty flows. Every call to a method that binds an id
 * list records the list size, so tests can check the SQLite variable limit is respected.
 */
class FakeTaskDao(initial: List<TaskEntity> = emptyList()) : TaskDao {
    private val rows = LinkedHashMap<Long, TaskEntity>().apply { initial.forEach { put(it.id, it) } }
    private val lock = Mutex()

    /** Sizes of every id list handed to a query that binds one parameter per id. */
    val boundIdListSizes = mutableListOf<Int>()

    suspend fun snapshot(): List<TaskEntity> = lock.withLock { rows.values.toList() }
    suspend fun entity(id: Long): TaskEntity? = lock.withLock { rows[id] }

    override fun getInboxTasks(inboxProjectId: Long, includeDated: Boolean): Flow<List<TaskEntity>> = flowOf(emptyList())
    override fun getTodayTasks(endOfToday: String): Flow<List<TaskEntity>> = flowOf(emptyList())
    override fun getUpcomingTasks(endOfToday: String): Flow<List<TaskEntity>> = flowOf(emptyList())
    override fun getAnytimeTasks(inboxProjectId: Long): Flow<List<TaskEntity>> = flowOf(emptyList())
    override fun getLogbookTasks(cutoff: String): Flow<List<TaskEntity>> = flowOf(emptyList())
    override fun getByProjectId(projectId: Long): Flow<List<TaskEntity>> = flowOf(emptyList())
    override fun getById(id: Long): Flow<TaskEntity?> = flowOf(null)
    override fun searchByTitle(query: String): Flow<List<TaskEntity>> = flowOf(emptyList())
    override fun searchByTitleIncludingDone(query: String): Flow<List<TaskEntity>> = flowOf(emptyList())
    override fun getAllOpenTasks(): Flow<List<TaskEntity>> = flowOf(emptyList())
    override fun getAllTasksFlow(): Flow<List<TaskEntity>> = flowOf(emptyList())
    override fun getRoutineCarriersFlow(): Flow<List<TaskEntity>> = flowOf(emptyList())

    override suspend fun getByIds(ids: List<Long>): List<TaskEntity> = lock.withLock {
        boundIdListSizes += ids.size
        ids.mapNotNull { rows[it] }
    }

    override suspend fun getByIdSync(id: Long): TaskEntity? = lock.withLock { rows[id] }
    override suspend fun countOverdue(startOfToday: String): Int = 0
    override suspend fun countDueToday(startOfToday: String, endOfToday: String): Int = 0
    override suspend fun countUpcoming(endOfToday: String): Int = 0
    override suspend fun getTodayTasksSync(endOfToday: String, limit: Int): List<TaskEntity> = emptyList()
    override suspend fun getDueTodaySync(startOfToday: String, endOfToday: String, limit: Int): List<TaskEntity> = emptyList()
    override suspend fun getInboxTasksSync(inboxProjectId: Long, limit: Int, includeDated: Boolean): List<TaskEntity> = emptyList()
    override suspend fun getUpcomingTasksSync(endOfToday: String, limit: Int): List<TaskEntity> = emptyList()
    override suspend fun getAnytimeTasksSync(inboxProjectId: Long, limit: Int): List<TaskEntity> = emptyList()
    override suspend fun getByProjectIdSync(projectId: Long, limit: Int): List<TaskEntity> = emptyList()
    override suspend fun getAllOpenTasksSync(limit: Int): List<TaskEntity> = emptyList()
    override suspend fun getAllWithReminders(): List<TaskEntity> = lock.withLock {
        rows.values.filter { it.remindersJson != "[]" && !it.done }
    }

    override suspend fun upsert(task: TaskEntity) {
        lock.withLock { rows[task.id] = task }
    }

    override suspend fun upsertAll(tasks: List<TaskEntity>) {
        lock.withLock { tasks.forEach { rows[it.id] = it } }
    }

    override suspend fun getAllSync(): List<TaskEntity> = lock.withLock { rows.values.toList() }
    override suspend fun getRoutineCarriersSync(): List<TaskEntity> = emptyList()

    override suspend fun deleteById(id: Long) {
        lock.withLock { rows.remove(id) }
    }

    override suspend fun deleteByIds(ids: List<Long>) {
        lock.withLock {
            boundIdListSizes += ids.size
            ids.forEach { rows.remove(it) }
        }
    }

    override suspend fun deleteNotIn(ids: Set<Long>) {
        lock.withLock {
            boundIdListSizes += ids.size
            rows.keys.retainAll(ids)
        }
    }

    override suspend fun updatePosition(taskId: Long, position: Double) {
        lock.withLock { rows[taskId]?.let { rows[taskId] = it.copy(position = position) } }
    }

    override suspend fun deleteAll() {
        lock.withLock { rows.clear() }
    }
}

/**
 * In-memory [PendingActionDao]. The default (transactional) methods of the interface, including
 * the queue-merging logic, run unchanged on top of these primitives.
 */
class FakePendingActionDao : PendingActionDao {
    private val rows = LinkedHashMap<Long, PendingActionEntity>()
    private val lock = Mutex()
    private var nextId = 1L

    suspend fun snapshot(): List<PendingActionEntity> = lock.withLock { rows.values.toList() }

    override fun getPending(): Flow<List<PendingActionEntity>> = flowOf(emptyList())
    override fun getPendingCount(): Flow<Int> = flowOf(0)

    override suspend fun getRetryable(): List<PendingActionEntity> = lock.withLock {
        rows.values.filter { it.status == "pending" && it.retryCount < it.maxRetries }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
    }

    override suspend fun insert(action: PendingActionEntity): Long = lock.withLock {
        val id = nextId++
        rows[id] = action.copy(id = id)
        id
    }

    override suspend fun updateStatusOnly(id: Long, status: String) {
        lock.withLock { rows[id]?.let { rows[id] = it.copy(status = status) } }
    }

    override suspend fun updateStatusAndRetry(id: Long, status: String, retryCount: Int) {
        lock.withLock { rows[id]?.let { rows[id] = it.copy(status = status, retryCount = retryCount) } }
    }

    override suspend fun deleteCompleted() {
        lock.withLock { rows.values.removeAll { it.status == "completed" } }
    }

    override suspend fun deleteByEntity(entityType: String, entityId: Long) {
        lock.withLock { rows.values.removeAll { it.entityType == entityType && it.entityId == entityId } }
    }

    override fun getFailed(): Flow<List<PendingActionEntity>> = flowOf(emptyList())
    override fun getFailedCount(): Flow<Int> = flowOf(0)

    override suspend fun deleteFailed() {
        lock.withLock { rows.values.removeAll { it.status == "failed" } }
    }

    override suspend fun retryAllFailed() {
        lock.withLock {
            rows.keys.toList().forEach { id ->
                val row = rows.getValue(id)
                if (row.status == "failed") rows[id] = row.copy(status = "pending", retryCount = 0)
            }
        }
    }

    override suspend fun resetProcessingToPending() {
        lock.withLock {
            rows.keys.toList().forEach { id ->
                val row = rows.getValue(id)
                if (row.status == "processing") rows[id] = row.copy(status = "pending")
            }
        }
    }

    override suspend fun getTaskIdsWithPendingActions(): List<Long> = lock.withLock {
        rows.values
            .filter { it.entityType in setOf("task", "routine") && it.status in setOf("pending", "failed", "processing") }
            .map { it.entityId }
    }

    override suspend fun getRemappable(): List<PendingActionEntity> = lock.withLock {
        rows.values.filter { it.status == "pending" || it.status == "failed" }
    }

    override suspend fun remapEntity(id: Long, entityId: Long, payload: String, status: String) {
        lock.withLock {
            rows[id]?.let { rows[id] = it.copy(entityId = entityId, payload = payload, status = status, retryCount = 0) }
        }
    }

    override suspend fun getActiveByEntity(entityType: String, entityId: Long): List<PendingActionEntity> =
        lock.withLock {
            rows.values.filter {
                it.entityType == entityType && it.entityId == entityId &&
                    it.status in setOf("pending", "failed", "processing")
            }
        }
}

class FakeProjectDao(initial: List<ProjectEntity> = emptyList()) : ProjectDao {
    private val rows = LinkedHashMap<Long, ProjectEntity>().apply { initial.forEach { put(it.id, it) } }
    val boundIdListSizes = mutableListOf<Int>()

    fun snapshot(): List<ProjectEntity> = rows.values.toList()

    override fun getAll(): Flow<List<ProjectEntity>> = flowOf(emptyList())
    override fun getAllIncludingArchived(): Flow<List<ProjectEntity>> = flowOf(emptyList())
    override fun getById(id: Long): Flow<ProjectEntity?> = flowOf(null)
    override fun getChildren(parentId: Long): Flow<List<ProjectEntity>> = flowOf(emptyList())
    override suspend fun getAllSync(): List<ProjectEntity> = rows.values.filter { !it.isArchived }
    override suspend fun getAllIncludingArchivedSync(): List<ProjectEntity> = rows.values.toList()
    override suspend fun getByIdSync(id: Long): ProjectEntity? = rows[id]

    override suspend fun upsert(project: ProjectEntity) {
        rows[project.id] = project
    }

    override suspend fun upsertAll(projects: List<ProjectEntity>) {
        projects.forEach { rows[it.id] = it }
    }

    override suspend fun deleteById(id: Long) {
        rows.remove(id)
    }

    override suspend fun deleteNotIn(serverIds: List<Long>) {
        boundIdListSizes += serverIds.size
        rows.keys.retainAll(serverIds.toSet())
    }

    override suspend fun deleteAll() {
        rows.clear()
    }
}

/** Records the platform side effects (alarms, widgets, sync triggers) a repository asks for. */
class RecordingRepositoryHooks : PlatformRepositoryHooks {
    val scheduled = mutableListOf<Long>()
    val cancelled = mutableListOf<Long>()
    var syncTriggers = 0
        private set
    var widgetUpdates = 0
        private set
    var rescheduleAllCalls = 0
        private set

    override fun triggerSync() {
        syncTriggers++
    }

    override fun updateWidgets() {
        widgetUpdates++
    }

    override fun playCompletionSound() = Unit

    override suspend fun scheduleAlarm(task: Task) {
        scheduled += task.id
    }

    override suspend fun cancelAlarm(taskId: Long) {
        cancelled += taskId
    }

    override suspend fun rescheduleAlarms() {
        rescheduleAllCalls++
    }
}
