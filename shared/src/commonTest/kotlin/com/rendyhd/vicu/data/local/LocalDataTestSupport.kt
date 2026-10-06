package com.rendyhd.vicu.data.local

import com.rendyhd.vicu.data.local.dao.LocalDataDao
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.data.repository.RecordingRepositoryHooks
import com.rendyhd.vicu.data.sync.SyncStaleness
import com.rendyhd.vicu.worker.FakeCustomListRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.yield

/**
 * In-memory stand-in for the tables [LocalDataDao] clears. Its delete methods model the SQL of
 * the real queries (what "queued" means, which rows a subquery keeps), and the interface's own
 * transactional methods (clearCaches, discardUnsyncedAndClearCaches, clearEverything) run
 * unchanged on top of them, so the tests check which of those the wiper calls and what that
 * leaves behind.
 */
class FakeLocalDataDao : LocalDataDao {
    val taskIds = mutableSetOf<Long>()
    val projectIds = mutableSetOf<Long>()
    val labelIds = mutableSetOf<Long>()
    val attachmentIds = mutableSetOf<Long>()
    val pending = mutableListOf<PendingActionEntity>()
    val routineArchive = mutableSetOf<String>()

    private val archiveCount = MutableStateFlow(0)

    private fun queued(vararg types: String): Set<Long> =
        pending
            .filter { it.entityType in types && it.status in QUEUED }
            .map { it.entityId }
            .toSet()

    override suspend fun deleteTasksWithoutQueuedActions() {
        suspendPoint()
        taskIds.retainAll(queued("task", "routine"))
    }

    override suspend fun deleteAllTasks() {
        suspendPoint()
        taskIds.clear()
    }

    override suspend fun deleteAllProjects() {
        suspendPoint()
        projectIds.clear()
    }

    override suspend fun deleteLabelsWithoutQueuedActions() {
        suspendPoint()
        labelIds.retainAll(queued("label"))
    }

    override suspend fun deleteAllLabels() {
        suspendPoint()
        labelIds.clear()
    }

    override suspend fun deleteAllAttachments() {
        suspendPoint()
        attachmentIds.clear()
    }

    override suspend fun deleteAllPendingActions() {
        suspendPoint()
        pending.clear()
    }

    override suspend fun deleteAllRoutineArchive() {
        suspendPoint()
        routineArchive.clear()
        archiveCount.value = 0
    }

    override suspend fun countUnsyncedActions(): Int {
        suspendPoint()
        return pending.count { it.status in QUEUED }
    }

    /**
     * Room's suspend DAO calls hop to a query dispatcher and so notice a cancelled caller. Doing
     * the same here keeps a test honest about code that must survive cancellation.
     */
    private suspend fun suspendPoint() = yield()

    override fun observeRoutineArchiveCount(): Flow<Int> = archiveCount

    fun addRoutineHistory(vararg keys: String) {
        routineArchive += keys
        archiveCount.value = routineArchive.size
    }

    private companion object {
        val QUEUED = setOf("pending", "failed", "processing")
    }
}

fun queuedAction(
    entityId: Long,
    entityType: String = "task",
    status: String = "pending",
    actionType: String = "update",
) = PendingActionEntity(
    entityType = entityType,
    entityId = entityId,
    actionType = actionType,
    payload = "{}",
    status = status,
)

/** A [LocalDataWiper] over fakes, with handles on everything it touches. */
class WiperFixture(
    val dao: FakeLocalDataDao = FakeLocalDataDao(),
    val customLists: FakeCustomListRepository = FakeCustomListRepository(),
    val hooks: RecordingRepositoryHooks = RecordingRepositoryHooks(),
    val staleness: SyncStaleness = SyncStaleness(),
) {
    val bottomBar = BottomBarPrefsStore(InMemoryPreferencesDataStore())
    val projectSections = ProjectSectionPrefsStore(InMemoryPreferencesDataStore())
    val labelOrder = LabelOrderPrefsStore(InMemoryPreferencesDataStore())
    val routinePrefs = RoutinePrefsStore(InMemoryPreferencesDataStore())
    val widgetPrefs = WidgetPrefsStore(InMemoryPreferencesDataStore())
    val wiper = LocalDataWiper(
        dao, customLists, bottomBar, hooks, staleness,
        projectSections, labelOrder, routinePrefs, widgetPrefs,
    )
}
