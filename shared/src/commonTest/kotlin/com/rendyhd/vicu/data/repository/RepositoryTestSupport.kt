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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-memory [DataStore] so the preference stores can run in plain unit tests. */
class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val writeLock = Mutex()
    override val data: Flow<Preferences> = state

    /** One writer at a time, like the real DataStore, so concurrent edits do not lose updates. */
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        writeLock.withLock {
            val updated = transform(state.value)
            state.value = updated
            updated
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
    /** The day boundaries the Today and Upcoming queries were started with, in order. */
    val todayBoundaries = mutableListOf<String>()
    val upcomingBoundaries = mutableListOf<String>()

    override fun getTodayTasks(startOfTomorrow: String): Flow<List<TaskEntity>> {
        todayBoundaries += startOfTomorrow
        return flowOf(emptyList())
    }

    override fun getUpcomingTasks(startOfTomorrow: String): Flow<List<TaskEntity>> {
        upcomingBoundaries += startOfTomorrow
        return flowOf(emptyList())
    }
    override fun getAnytimeTasks(inboxProjectId: Long): Flow<List<TaskEntity>> = flowOf(emptyList())
    override fun getLogbookTasks(cutoff: String): Flow<List<TaskEntity>> = flowOf(emptyList())
    override fun getByProjectId(projectId: Long): Flow<List<TaskEntity>> = flowOf(emptyList())
    override fun getById(id: Long): Flow<TaskEntity?> = flowOf(null)
    override fun searchByTitle(query: String): Flow<List<TaskEntity>> = flowOf(emptyList())
    override fun searchByTitleIncludingDone(query: String): Flow<List<TaskEntity>> = flowOf(emptyList())
    override fun getAllOpenTasks(): Flow<List<TaskEntity>> =
        flow { emit(lock.withLock { rows.values.filter { !it.done } }) }
    override fun getAllTasksFlow(): Flow<List<TaskEntity>> = flow { emit(lock.withLock { rows.values.toList() }) }
    /** The SQL is `description LIKE '%<!-- vicu-routine:%'`: main carriers and archive parts alike. */
    private fun List<TaskEntity>.routineMetadata() = filter { it.description.contains("<!-- vicu-routine:") }

    override fun getRoutineCarriersFlow(): Flow<List<TaskEntity>> =
        flow { emit(lock.withLock { rows.values.toList() }.routineMetadata()) }

    override suspend fun getByIdsChunk(ids: List<Long>): List<TaskEntity> = lock.withLock {
        boundIdListSizes += ids.size
        ids.mapNotNull { rows[it] }
    }

    override suspend fun getByIdSync(id: Long): TaskEntity? = lock.withLock { rows[id] }
    override suspend fun countOverdue(startOfToday: String): Int = 0
    override suspend fun countDueToday(startOfToday: String, startOfTomorrow: String): Int = 0
    override suspend fun countUpcoming(startOfTomorrow: String): Int = 0
    override suspend fun getTodayTasksSync(startOfTomorrow: String, limit: Int): List<TaskEntity> = emptyList()
    override suspend fun getDueTodaySync(startOfToday: String, startOfTomorrow: String, limit: Int): List<TaskEntity> = emptyList()
    override suspend fun getInboxTasksSync(inboxProjectId: Long, limit: Int, includeDated: Boolean): List<TaskEntity> = emptyList()
    override suspend fun getUpcomingTasksSync(startOfTomorrow: String, limit: Int): List<TaskEntity> = emptyList()
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
    override suspend fun getRoutineCarriersSync(): List<TaskEntity> =
        lock.withLock { rows.values.toList() }.routineMetadata()

    override suspend fun deleteById(id: Long) {
        lock.withLock { rows.remove(id) }
    }

    override suspend fun deleteByIdsChunk(ids: List<Long>) {
        lock.withLock {
            boundIdListSizes += ids.size
            ids.forEach { rows.remove(it) }
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

    /** Bumped after every write so the count flows below can re-read the rows. */
    private val version = MutableStateFlow(0)

    suspend fun snapshot(): List<PendingActionEntity> = lock.withLock { rows.values.toList() }

    private suspend fun countWithStatus(status: String): Int =
        lock.withLock { rows.values.count { it.status == status } }

    override fun getPending(): Flow<List<PendingActionEntity>> = flowOf(emptyList())
    override fun getPendingCount(): Flow<Int> = version.map { countWithStatus("pending") }

    override suspend fun getRetryable(): List<PendingActionEntity> = lock.withLock {
        rows.values.filter { it.status == "pending" && it.retryCount < it.maxRetries }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
    }

    override suspend fun insert(action: PendingActionEntity): Long = lock.withLock {
        val id = nextId++
        rows[id] = action.copy(id = id)
        version.value++
        id
    }

    override suspend fun updateStatusOnly(id: Long, status: String) {
        lock.withLock { rows[id]?.let { rows[id] = it.copy(status = status) } }
        version.value++
    }

    override suspend fun updateStatusAndRetry(id: Long, status: String, retryCount: Int) {
        lock.withLock { rows[id]?.let { rows[id] = it.copy(status = status, retryCount = retryCount) } }
        version.value++
    }

    override suspend fun markFailed(id: Long, failedAt: String) {
        lock.withLock { rows[id]?.let { rows[id] = it.copy(status = "failed", updatedAt = failedAt) } }
        version.value++
    }

    override suspend fun deleteFailedBefore(cutoff: String) {
        lock.withLock { rows.values.removeAll { it.status == "failed" && it.updatedAt < cutoff } }
        version.value++
    }

    override suspend fun deleteCompleted() {
        lock.withLock { rows.values.removeAll { it.status == "completed" } }
    }

    override suspend fun deleteByEntity(entityType: String, entityId: Long) {
        lock.withLock { rows.values.removeAll { it.entityType == entityType && it.entityId == entityId } }
    }

    override fun getFailed(): Flow<List<PendingActionEntity>> = flowOf(emptyList())
    override fun getFailedCount(): Flow<Int> = version.map { countWithStatus("failed") }

    override suspend fun deleteFailed() {
        lock.withLock { rows.values.removeAll { it.status == "failed" } }
        version.value++
    }

    override suspend fun retryAllFailed() {
        lock.withLock {
            rows.keys.toList().forEach { id ->
                val row = rows.getValue(id)
                if (row.status == "failed") rows[id] = row.copy(status = "pending", retryCount = 0)
            }
        }
        version.value++
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

    override suspend fun getAllIds(): List<Long> = rows.keys.toList()

    override suspend fun deleteByIdsChunk(ids: List<Long>) {
        boundIdListSizes += ids.size
        ids.forEach { rows.remove(it) }
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
    var cancelAllAlarmsCalls = 0
        private set
    var cancelAccountBackgroundWorkCalls = 0
        private set
    var clearWidgetConfigurationsCalls = 0
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

    override suspend fun cancelAllAlarms() {
        cancelAllAlarmsCalls++
    }

    override suspend fun cancelAccountBackgroundWork() {
        cancelAccountBackgroundWorkCalls++
    }

    override suspend fun clearWidgetConfigurations() {
        clearWidgetConfigurationsCalls++
    }
}
