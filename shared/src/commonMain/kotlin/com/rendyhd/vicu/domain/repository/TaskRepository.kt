package com.rendyhd.vicu.domain.repository

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.flow.Flow

interface TaskRepository {
    fun getInboxTasks(inboxProjectId: Long): Flow<List<Task>>
    fun getTodayTasks(): Flow<List<Task>>
    fun getUpcomingTasks(): Flow<List<Task>>
    fun getAnytimeTasks(inboxProjectId: Long): Flow<List<Task>>
    fun getLogbookTasks(): Flow<List<Task>>
    fun getByProjectId(projectId: Long): Flow<List<Task>>
    fun getById(id: Long): Flow<Task?>
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
    suspend fun update(task: Task): NetworkResult<Task>
    /**
     * Applies the user's configured "schedule" action (set due today / set urgent) to a task.
     * The task is re-read from Room by id so a caller holding an old copy cannot write stale
     * fields back, and only the field the action sets is patched.
     */
    suspend fun applyScheduleAction(taskId: Long): NetworkResult<Task>
    suspend fun moveToProject(taskId: Long, newProjectId: Long): NetworkResult<Unit>
    /**
     * Moves every descendant of [taskId] (subtasks, their subtasks, and so on) into
     * [newProjectId]; Vikunja does not cascade a project move. Descendants already there are
     * skipped. Returns how many were moved, or an error naming how many could not be.
     */
    suspend fun moveDescendantsToProject(taskId: Long, newProjectId: Long): NetworkResult<Int>
    /** Manual reorder: optimistic local position write + best-effort remote view-position POST. */
    suspend fun updatePosition(taskId: Long, projectId: Long, newPosition: Double)
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

/** What a [TaskRepository.loadLogbookPage] call found: whether a later page exists. */
data class LogbookPage(val page: Int, val hasMore: Boolean)
