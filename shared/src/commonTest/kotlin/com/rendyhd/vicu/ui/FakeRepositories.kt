package com.rendyhd.vicu.ui

import com.rendyhd.vicu.data.sync.ScreenRefresher
import com.rendyhd.vicu.data.sync.SyncStaleness
import com.rendyhd.vicu.domain.model.Attachment
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.AttachmentRepository
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.LogbookPage
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.QuickDue
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.DEFAULT_MAX_UPLOAD_BYTES
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.PositionUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The refresh a list screen runs, over the given fakes. It starts fresh (so a screen that opens
 * does not refresh) unless [fresh] is false.
 */
fun fakeScreenRefresher(
    tasks: TaskRepository,
    projects: ProjectRepository,
    labels: LabelRepository,
    fresh: Boolean = true,
) = ScreenRefresher(tasks, projects, labels, SyncStaleness().apply { if (fresh) markSynced() })

/**
 * In-memory [TaskRepository] for view model tests. It behaves like the real one where tests
 * depend on it: [update] writes the row optimistically, asks [updateHandler] for the outcome and
 * rolls the row back when that is an error. Methods a test does not need answer with an error.
 */
class FakeTaskRepository : TaskRepository {
    private val rows = HashMap<Long, MutableStateFlow<Task?>>()

    private val all = MutableStateFlow<List<Task>>(emptyList())

    private fun rowFor(id: Long) = rows.getOrPut(id) { MutableStateFlow(null) }

    /** Every write goes through here so the row flows and the list flows stay in step. */
    private fun write(id: Long, task: Task?) {
        rowFor(id).value = task
        all.value = if (task == null) {
            all.value.filter { it.id != id }
        } else if (all.value.any { it.id == id }) {
            all.value.map { if (it.id == id) task else it }
        } else {
            all.value + task
        }
    }

    fun put(task: Task) {
        write(task.id, task)
    }

    fun current(id: Long): Task? = rowFor(id).value

    val updates = mutableListOf<Task>()
    var updateHandler: suspend (Task) -> NetworkResult<Task> = { NetworkResult.Success(it) }

    val moveDescendantsCalls = mutableListOf<Pair<Long, Long>>()
    var moveDescendantsResult: NetworkResult<Int> = NetworkResult.Success(0)

    val deleted = mutableListOf<Long>()

    /** Tasks passed to [toggleDone] and (id, done) pairs passed to [setDone], in call order. */
    val toggled = mutableListOf<Long>()
    val setDoneCalls = mutableListOf<Pair<Long, Boolean>>()

    /**
     * Decides the outcome of a [toggleDone] or [setDone]: null stores the change like Room does
     * (a stored task changes at once), an error leaves the task as it was.
     */
    var completionOutcome: suspend (Long) -> NetworkResult<Task>? = { null }

    override fun getInboxTasks(inboxProjectId: Long): Flow<List<Task>> =
        all.map { list -> list.filter { !it.done && it.projectId == inboxProjectId } }
    /** What [getTodayTasks] and [getUpcomingTasks] emit; a test sets them to the rows a screen shows. */
    val todayTasks = MutableStateFlow<List<Task>?>(null)
    val upcomingTasks = MutableStateFlow<List<Task>?>(null)

    override fun getTodayTasks(): Flow<List<Task>> = todayTasks.filterNotNull()
    override fun getUpcomingTasks(): Flow<List<Task>> = upcomingTasks.filterNotNull()
    override fun getAnytimeTasks(inboxProjectId: Long): Flow<List<Task>> =
        all.map { list -> list.filter { !it.done && it.projectId != inboxProjectId } }
    override fun getLogbookTasks(): Flow<List<Task>> = all.map { tasks -> tasks.filter { it.done } }
    override fun getByProjectId(projectId: Long): Flow<List<Task>> =
        all.map { tasks -> tasks.filter { it.projectId == projectId } }
    override fun getById(id: Long): Flow<Task?> = rowFor(id)
    override suspend fun getByIds(ids: Set<Long>): List<Task> = ids.mapNotNull { rowFor(it).value }

    /** The queries [searchTasks] was started with, in order. */
    val searches = mutableListOf<String>()

    /** Title or description contains the text (plain, case-insensitive), open tasks first. */
    override fun searchTasks(query: String): Flow<List<Task>> {
        searches += query
        val text = query.trim()
        if (text.isEmpty()) return flowOf(emptyList())
        return all.map { tasks ->
            tasks
                .filter { it.title.contains(text, ignoreCase = true) || it.description.contains(text, ignoreCase = true) }
                .sortedBy { it.done }
        }
    }
    override fun getAllOpenTasksFlat(): Flow<List<Task>> = all.map { tasks -> tasks.filter { !it.done } }
    override fun getAllTasksFlat(): Flow<List<Task>> = all

    /** Tasks passed to [create]; [createHandler] decides the outcome (an error unless a test sets it). */
    val created = mutableListOf<Task>()
    var createHandler: (Task) -> NetworkResult<Task> = { NetworkResult.Error("not faked") }

    override suspend fun create(task: Task): NetworkResult<Task> {
        created += task
        return createHandler(task)
    }

    override suspend fun update(task: Task): NetworkResult<Task> {
        updates += task
        val previous = rowFor(task.id).value
        write(task.id, task)
        val result = updateHandler(task)
        when (result) {
            is NetworkResult.Success -> write(task.id, result.data)
            else -> write(task.id, previous)
        }
        return result
    }

    override suspend fun applyScheduleAction(taskId: Long): NetworkResult<Task> = NetworkResult.Error("not faked")

    /** (task id, day) of every [scheduleDue]; the stored row is not changed. */
    val quickDues = mutableListOf<Pair<Long, QuickDue>>()

    override suspend fun scheduleDue(taskId: Long, due: QuickDue): NetworkResult<Task> {
        quickDues += taskId to due
        return NetworkResult.Error("not faked")
    }
    override suspend fun moveToProject(taskId: Long, newProjectId: Long): NetworkResult<Unit> =
        NetworkResult.Error("not faked")

    override suspend fun moveDescendantsToProject(taskId: Long, newProjectId: Long): NetworkResult<Int> {
        moveDescendantsCalls += taskId to newProjectId
        return moveDescendantsResult
    }

    /** (project id, updates) of every [applyPositions]; [positionResult] decides the outcome. */
    val appliedPositions = mutableListOf<Pair<Long, List<PositionUpdate>>>()
    var positionResult: NetworkResult<Unit> = NetworkResult.Success(Unit)

    /** Like the real repository, a reorder is stored on the cached rows at once, whatever the server says. */
    override suspend fun applyPositions(projectId: Long, updates: List<PositionUpdate>): NetworkResult<Unit> {
        appliedPositions += projectId to updates
        updates.forEach { update -> rowFor(update.id).value?.let { write(it.id, it.copy(position = update.position)) } }
        return positionResult
    }

    override suspend fun updatePosition(taskId: Long, projectId: Long, newPosition: Double): NetworkResult<Unit> =
        applyPositions(projectId, listOf(PositionUpdate(taskId, newPosition)))

    /** The projects [refreshListPositions] was asked about; [listPositions] is what the "server" says. */
    val positionRefreshes = mutableListOf<Long>()
    var listPositions: Map<Long, Double> = emptyMap()
    var positionRefreshResult: NetworkResult<Unit> = NetworkResult.Success(Unit)

    override suspend fun refreshListPositions(projectId: Long): NetworkResult<Unit> {
        positionRefreshes += projectId
        if (positionRefreshResult is NetworkResult.Success) {
            listPositions.forEach { (id, position) -> rowFor(id).value?.let { write(id, it.copy(position = position)) } }
        }
        return positionRefreshResult
    }

    override suspend fun delete(taskId: Long, deleteSubtasks: Boolean): NetworkResult<Unit> {
        deleted += taskId
        write(taskId, null)
        return NetworkResult.Success(Unit)
    }

    override suspend fun toggleDone(task: Task): NetworkResult<Task> {
        toggled += task.id
        return storeDone(task.id, !task.done, task)
    }

    override suspend fun setDone(taskId: Long, done: Boolean): NetworkResult<Task> {
        setDoneCalls += taskId to done
        val current = rowFor(taskId).value ?: return NetworkResult.Error("Task $taskId is not in the local cache")
        if (current.done == done) return NetworkResult.Success(current)
        return storeDone(taskId, done, current)
    }

    private suspend fun storeDone(taskId: Long, done: Boolean, shown: Task): NetworkResult<Task> {
        completionOutcome(taskId)?.let { return it }
        val stored = (rowFor(taskId).value ?: shown).copy(done = done)
        write(taskId, stored)
        return NetworkResult.Success(stored)
    }

    override suspend fun createSubtask(parentTaskId: Long, subtask: Task): NetworkResult<Task> =
        NetworkResult.Error("not faked")

    override suspend fun toggleSubtaskDone(parentTaskId: Long, subtask: Task): NetworkResult<Task> =
        NetworkResult.Error("not faked")

    override suspend fun deleteRelation(taskId: Long, relationKind: String, otherTaskId: Long): NetworkResult<Unit> =
        NetworkResult.Error("not faked")

    override suspend fun createRelation(taskId: Long, otherTaskId: Long, relationKind: String): NetworkResult<Unit> =
        NetworkResult.Error("not faked")

    override suspend fun deleteLocalByIds(ids: Set<Long>) = Unit

    /** The filters of every [refreshAll] call, and which of them asked for a full reconcile. */
    val refreshes = mutableListOf<Map<String, String>>()
    val fullRefreshes = mutableListOf<Boolean>()

    /** What [refreshAll] answers; a test sets an error to model a failed or offline refresh. */
    var refreshResult: NetworkResult<Unit> = NetworkResult.Success(Unit)

    /** Holds every [refreshAll] until a test completes it, to model a slow network. */
    var refreshGate: CompletableDeferred<Unit>? = null

    /** How many [refreshAll] calls ran to the end; a call cancelled while it waited on [refreshGate] is not counted. */
    var completedRefreshes = 0
        private set

    override suspend fun refreshAll(filters: Map<String, String>, full: Boolean): NetworkResult<Unit> {
        refreshes += filters
        fullRefreshes += full
        refreshGate?.await()
        completedRefreshes++
        return refreshResult
    }

    /** The pages of every [loadLogbookPage] call; [logbookResult] decides the outcome. */
    val logbookPages = mutableListOf<Int>()
    var logbookResult: (Int) -> NetworkResult<LogbookPage> = { NetworkResult.Success(LogbookPage(it, hasMore = false)) }

    override suspend fun loadLogbookPage(page: Int): NetworkResult<LogbookPage> {
        logbookPages += page
        return logbookResult(page)
    }
}

class FakeLabelRepository(initial: List<Label> = emptyList()) : LabelRepository {
    val labels = MutableStateFlow(initial)
    val created = mutableListOf<Label>()
    val addedToTask = mutableListOf<Pair<Long, Long>>()
    private var nextId = 1_000L

    override fun getAll(): Flow<List<Label>> = labels
    override suspend fun getById(id: Long): Label? = labels.value.firstOrNull { it.id == id }

    override suspend fun create(label: Label): NetworkResult<Label> {
        val saved = label.copy(id = nextId++)
        created += saved
        labels.value = labels.value + saved
        return NetworkResult.Success(saved)
    }

    override suspend fun update(label: Label): NetworkResult<Label> = NetworkResult.Success(label)
    override suspend fun delete(labelId: Long): NetworkResult<Unit> = NetworkResult.Success(Unit)

    override suspend fun addToTask(taskId: Long, labelId: Long): NetworkResult<Unit> {
        addedToTask += taskId to labelId
        return NetworkResult.Success(Unit)
    }

    override suspend fun removeFromTask(taskId: Long, labelId: Long): NetworkResult<Unit> = NetworkResult.Success(Unit)

    var refreshCalls = 0
    var refreshResult: NetworkResult<Unit> = NetworkResult.Success(Unit)

    override suspend fun refreshAll(): NetworkResult<Unit> {
        refreshCalls++
        return refreshResult
    }
}

class FakeProjectRepository(initial: List<Project> = emptyList()) : ProjectRepository {
    val projects = MutableStateFlow(initial)

    override fun getAll(): Flow<List<Project>> = projects
    override fun getAllIncludingArchived(): Flow<List<Project>> = projects
    override fun getById(id: Long): Flow<Project?> = projects.map { list -> list.firstOrNull { it.id == id } }
    override fun getChildren(parentId: Long): Flow<List<Project>> =
        flowOf(projects.value.filter { it.parentProjectId == parentId })

    override suspend fun create(project: Project): NetworkResult<Project> = NetworkResult.Success(project)
    /** Projects passed to [update], in call order. */
    val updates = mutableListOf<Project>()

    /** What [update] answers; a test makes it fail to model a refused change. */
    var updateResult: (Project) -> NetworkResult<Project> = { NetworkResult.Success(it) }

    override suspend fun update(project: Project): NetworkResult<Project> {
        updates += project
        return updateResult(project)
    }
    override suspend fun delete(projectId: Long): NetworkResult<Unit> = NetworkResult.Success(Unit)

    var refreshCalls = 0
    var refreshResult: NetworkResult<Unit> = NetworkResult.Success(Unit)

    override suspend fun refreshAll(): NetworkResult<Unit> {
        refreshCalls++
        return refreshResult
    }
}

class FakeAttachmentRepository : AttachmentRepository {
    val attachments = MutableStateFlow<List<Attachment>>(emptyList())

    /** (task id, picked uri) of every upload. */
    val uploads = mutableListOf<Pair<Long, String>>()
    var uploadResult: (Long, String) -> NetworkResult<Attachment> = { _, _ -> NetworkResult.Error("not faked") }

    val downloads = mutableListOf<Long>()
    var downloadResult: (Attachment) -> NetworkResult<String> =
        { NetworkResult.Success("/cache/attachments/${it.id}/${it.fileName}") }

    /** (task id, attachment id) of every delete; [deleteGate] can hold the call until a test says so. */
    val deletes = mutableListOf<Pair<Long, Long>>()
    var deleteGate: CompletableDeferred<Unit>? = null
    var deleteResult: NetworkResult<Unit> = NetworkResult.Success(Unit)

    override fun getByTaskId(taskId: Long): Flow<List<Attachment>> =
        attachments.map { list -> list.filter { it.taskId == taskId } }

    override suspend fun maxUploadBytes(): Long = DEFAULT_MAX_UPLOAD_BYTES

    override suspend fun uploadPicked(taskId: Long, uriString: String): NetworkResult<Attachment> {
        uploads += taskId to uriString
        return uploadResult(taskId, uriString)
    }

    override suspend fun downloadToCache(attachment: Attachment): NetworkResult<String> {
        downloads += attachment.id
        return downloadResult(attachment)
    }

    override suspend fun delete(taskId: Long, attachmentId: Long): NetworkResult<Unit> {
        deletes += taskId to attachmentId
        deleteGate?.await()
        if (deleteResult is NetworkResult.Success) {
            attachments.value = attachments.value.filter { it.id != attachmentId }
        }
        return deleteResult
    }

    override suspend fun refreshForTask(taskId: Long): NetworkResult<Unit> = NetworkResult.Success(Unit)
}
