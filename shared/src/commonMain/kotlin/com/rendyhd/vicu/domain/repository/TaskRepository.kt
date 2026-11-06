package com.rendyhd.vicu.domain.repository

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.PositionUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate

interface TaskRepository {
    fun getInboxTasks(inboxProjectId: Long): Flow<List<Task>>
    fun getTodayTasks(): Flow<List<Task>>
    fun getUpcomingTasks(): Flow<List<Task>>
    fun getAnytimeTasks(inboxProjectId: Long): Flow<List<Task>>
    fun getLogbookTasks(): Flow<List<Task>>
    fun getByProjectId(projectId: Long): Flow<List<Task>>
    fun getById(id: Long): Flow<Task?>

    /**
     * The day planner's tasks of [firstDay] to [lastDay] from the cache (contract section 16): a block
     * starting in them, done ones included, an open task due in them, and overdue tasks while today is
     * among them. Every task is a row, because a subtask can have a block of its own.
     */
    fun getPlanTasks(firstDay: LocalDate, lastDay: LocalDate): Flow<List<Task>> = flowOf(emptyList())

    /**
     * Asks the server for [day]'s tasks and merges them into the cache: one request, the only way the
     * done blocks of a day reach the phone (completed history is otherwise loaded in pages).
     */
    suspend fun refreshPlanDay(day: LocalDate): NetworkResult<Unit> = NetworkResult.Success(Unit)

    suspend fun getByIds(ids: Set<Long>): List<Task>

    /**
     * The cached tasks, open and completed, whose title or description contains [query]. Open
     * tasks come first, the most recently changed first; sync metadata tasks never show. Nested
     * subtasks are not hidden: the caller decides how to nest what matched. Reads Room only; ask
     * the server with [refreshAll] and a `q` filter, and the cache (and this flow) follows.
     */
    fun searchTasks(query: String): Flow<List<Task>>

    /**
     * Every open task, or every task, with nested subtasks not hidden: every task is a row. For
     * views that apply their own conditions first (Tag, custom lists) and hide nested subtasks
     * only among the tasks that match, so a matching subtask shows even when its parent does not
     * match. Hide them with `withoutNestedSubtasks(hideChildrenOfCompletedParents = false)`.
     */
    fun getAllOpenTasksFlat(): Flow<List<Task>>
    fun getAllTasksFlat(): Flow<List<Task>>

    suspend fun create(task: Task): NetworkResult<Task>

    /**
     * Creates the task on this device at once (a temporary id) and queues it for the server, without
     * waiting for the network first: for callers that must answer quickly, like a voice command.
     */
    suspend fun createQueued(task: Task): NetworkResult<Task> = create(task)

    suspend fun update(task: Task): NetworkResult<Task>
    /**
     * Sets the priority of a task (a swipe set to "Urgent"). The task is re-read from Room by id so
     * a caller holding an old copy cannot write stale fields back, and only the priority is patched.
     */
    suspend fun setPriority(taskId: Long, priority: Int): NetworkResult<Task>

    /**
     * The "Today" / "Tomorrow" quick pick: sets only the due date of the stored task to the end
     * of that local day. Like [setPriority] the task is re-read by id, so the patch holds
     * just the due date whatever copy the caller saw.
     */
    suspend fun scheduleDue(taskId: Long, due: QuickDue): NetworkResult<Task>
    suspend fun moveToProject(taskId: Long, newProjectId: Long): NetworkResult<Unit>
    /**
     * Moves every descendant of [taskId] (subtasks, their subtasks, and so on) into
     * [newProjectId]; Vikunja does not cascade a project move. Descendants already there are
     * skipped. Returns how many were moved, or an error naming how many could not be.
     */
    suspend fun moveDescendantsToProject(taskId: Long, newProjectId: Long): NetworkResult<Int>
    /** Manual reorder of one task: [applyPositions] with a single update. */
    suspend fun updatePosition(taskId: Long, projectId: Long, newPosition: Double): NetworkResult<Unit>

    /**
     * Applies a manual reorder of [projectId]'s list view. The positions are stored on the cached
     * rows at once, so the list shows the new order; then they are sent to the server, one request
     * after the other in the order given, stopping at the first one that fails (an error, and the
     * local order stays until [refreshListPositions] brings the server's back). A task that only
     * exists on this device is not sent: the sync puts it at the end when it creates it.
     */
    suspend fun applyPositions(projectId: Long, updates: List<PositionUpdate>): NetworkResult<Unit>

    /**
     * Reads the order of [projectId]'s list view from the server and stores it on the cached open
     * tasks. The task lists do not carry positions (the server only states them for a list view),
     * so this is how a list that is drawn in manual order learns it. Only the position of tasks
     * already cached is written; a project without a list view has nothing to read.
     */
    suspend fun refreshListPositions(projectId: Long): NetworkResult<Unit>
    /** Deletes a task. Descendants are deleted by default so they cannot be silently promoted. */
    suspend fun delete(taskId: Long, deleteSubtasks: Boolean = true): NetworkResult<Unit>
    suspend fun toggleDone(task: Task): NetworkResult<Task>
    /**
     * Idempotent completion: brings the stored task to [done] and does nothing when it is
     * already there. Unlike [toggleDone] it can never reopen a task, so it is the right call for
     * actions that may run twice or against data that changed since the user last looked
     * (notification buttons).
     */
    suspend fun setDone(taskId: Long, done: Boolean): NetworkResult<Task>

    /**
     * [setDone] for a caller that can be stopped at any moment (a notification button, a widget
     * tap): the change is stored on the device and queued, and nothing is sent from the caller, so
     * it survives the process being killed; the sync sends it.
     */
    suspend fun setDoneInBackground(taskId: Long, done: Boolean): NetworkResult<Task> = setDone(taskId, done)
    suspend fun createSubtask(parentTaskId: Long, subtask: Task): NetworkResult<Task>
    suspend fun toggleSubtaskDone(parentTaskId: Long, subtask: Task): NetworkResult<Task>
    suspend fun deleteRelation(taskId: Long, relationKind: String, otherTaskId: Long): NetworkResult<Unit>
    suspend fun createRelation(taskId: Long, otherTaskId: Long, relationKind: String): NetworkResult<Unit>
    suspend fun deleteLocalByIds(ids: Set<Long>)

    /**
     * Brings the cache up to date. Without [filters] only what changed since the last refresh is
     * fetched; [full] (pull to refresh) or a due daily reconcile also removes tasks deleted on the
     * server. With [filters] (a search, a custom list) the tasks they match are merged and nothing
     * is deleted. Completed history is not downloaded here: see [loadLogbookPage].
     */
    suspend fun refreshAll(filters: Map<String, String> = emptyMap(), full: Boolean = false): NetworkResult<Unit>

    /**
     * Fetches one page (1-based) of completed tasks, newest first, into the cache, within the
     * Logbook retention window. Page 1 also drops cached completed tasks that are gone on the server.
     */
    suspend fun loadLogbookPage(page: Int): NetworkResult<LogbookPage>
}

/** The days offered as quick due-date picks. */
enum class QuickDue { TODAY, TOMORROW }

/** What a [TaskRepository.loadLogbookPage] call found: whether a later page exists. */
data class LogbookPage(val page: Int, val hasMore: Boolean)
