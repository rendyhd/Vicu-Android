package com.rendyhd.vicu.ui

import com.rendyhd.vicu.domain.model.Attachment
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.AttachmentRepository
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory [TaskRepository] for view model tests. It behaves like the real one where tests
 * depend on it: [update] writes the row optimistically, asks [updateHandler] for the outcome and
 * rolls the row back when that is an error. Methods a test does not need answer with an error.
 */
class FakeTaskRepository : TaskRepository {
    private val rows = HashMap<Long, MutableStateFlow<Task?>>()

    private fun rowFor(id: Long) = rows.getOrPut(id) { MutableStateFlow(null) }

    fun put(task: Task) {
        rowFor(task.id).value = task
    }

    fun current(id: Long): Task? = rowFor(id).value

    val updates = mutableListOf<Task>()
    var updateHandler: suspend (Task) -> NetworkResult<Task> = { NetworkResult.Success(it) }

    val moveDescendantsCalls = mutableListOf<Pair<Long, Long>>()
    var moveDescendantsResult: NetworkResult<Int> = NetworkResult.Success(0)

    val deleted = mutableListOf<Long>()

    override fun getInboxTasks(inboxProjectId: Long): Flow<List<Task>> = emptyFlow()
    override fun getTodayTasks(): Flow<List<Task>> = emptyFlow()
    override fun getUpcomingTasks(): Flow<List<Task>> = emptyFlow()
    override fun getAnytimeTasks(inboxProjectId: Long): Flow<List<Task>> = emptyFlow()
    override fun getLogbookTasks(): Flow<List<Task>> = emptyFlow()
    override fun getByProjectId(projectId: Long): Flow<List<Task>> = emptyFlow()
    override fun getById(id: Long): Flow<Task?> = rowFor(id)
    override suspend fun getByIds(ids: Set<Long>): List<Task> = ids.mapNotNull { rowFor(it).value }
    override fun searchByTitle(query: String): Flow<List<Task>> = flowOf(emptyList())
    override fun searchByTitleIncludingDone(query: String): Flow<List<Task>> = flowOf(emptyList())
    override fun getAllOpenTasks(): Flow<List<Task>> = emptyFlow()
    override fun getAllTasks(): Flow<List<Task>> = emptyFlow()

    override suspend fun create(task: Task): NetworkResult<Task> = NetworkResult.Error("not faked")

    override suspend fun update(task: Task): NetworkResult<Task> {
        updates += task
        val row = rowFor(task.id)
        val previous = row.value
        row.value = task
        val result = updateHandler(task)
        when (result) {
            is NetworkResult.Success -> row.value = result.data
            else -> row.value = previous
        }
        return result
    }

    override suspend fun applyScheduleAction(taskId: Long): NetworkResult<Task> = NetworkResult.Error("not faked")
    override suspend fun moveToProject(taskId: Long, newProjectId: Long): NetworkResult<Unit> =
        NetworkResult.Error("not faked")

    override suspend fun moveDescendantsToProject(taskId: Long, newProjectId: Long): NetworkResult<Int> {
        moveDescendantsCalls += taskId to newProjectId
        return moveDescendantsResult
    }

    override suspend fun updatePosition(taskId: Long, projectId: Long, newPosition: Double) = Unit

    override suspend fun delete(taskId: Long, deleteSubtasks: Boolean): NetworkResult<Unit> {
        deleted += taskId
        rowFor(taskId).value = null
        return NetworkResult.Success(Unit)
    }

    override suspend fun toggleDone(task: Task): NetworkResult<Task> = NetworkResult.Error("not faked")
    override suspend fun setDone(taskId: Long, done: Boolean): NetworkResult<Task> = NetworkResult.Error("not faked")
    override suspend fun createSubtask(parentTaskId: Long, subtask: Task): NetworkResult<Task> =
        NetworkResult.Error("not faked")

    override suspend fun toggleSubtaskDone(parentTaskId: Long, subtask: Task): NetworkResult<Task> =
        NetworkResult.Error("not faked")

    override suspend fun deleteRelation(taskId: Long, relationKind: String, otherTaskId: Long): NetworkResult<Unit> =
        NetworkResult.Error("not faked")

    override suspend fun createRelation(taskId: Long, otherTaskId: Long, relationKind: String): NetworkResult<Unit> =
        NetworkResult.Error("not faked")

    override suspend fun deleteLocalByIds(ids: Set<Long>) = Unit
    override suspend fun refreshAll(filters: Map<String, String>): NetworkResult<Unit> = NetworkResult.Success(Unit)
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
    override suspend fun refreshAll(): NetworkResult<Unit> = NetworkResult.Success(Unit)
}

class FakeProjectRepository(initial: List<Project> = emptyList()) : ProjectRepository {
    val projects = MutableStateFlow(initial)

    override fun getAll(): Flow<List<Project>> = projects
    override fun getAllIncludingArchived(): Flow<List<Project>> = projects
    override fun getById(id: Long): Flow<Project?> = flowOf(projects.value.firstOrNull { it.id == id })
    override fun getChildren(parentId: Long): Flow<List<Project>> =
        flowOf(projects.value.filter { it.parentProjectId == parentId })

    override suspend fun create(project: Project): NetworkResult<Project> = NetworkResult.Success(project)
    override suspend fun update(project: Project): NetworkResult<Project> = NetworkResult.Success(project)
    override suspend fun delete(projectId: Long): NetworkResult<Unit> = NetworkResult.Success(Unit)
    override suspend fun refreshAll(): NetworkResult<Unit> = NetworkResult.Success(Unit)
}

class FakeAttachmentRepository : AttachmentRepository {
    override fun getByTaskId(taskId: Long): Flow<List<Attachment>> = flowOf(emptyList())

    override suspend fun upload(taskId: Long, fileName: String, content: ByteArray): NetworkResult<Attachment> =
        NetworkResult.Error("not faked")

    override suspend fun download(taskId: Long, attachmentId: Long): NetworkResult<ByteArray> =
        NetworkResult.Error("not faked")

    override suspend fun delete(taskId: Long, attachmentId: Long): NetworkResult<Unit> = NetworkResult.Success(Unit)
    override suspend fun refreshForTask(taskId: Long): NetworkResult<Unit> = NetworkResult.Success(Unit)
}
