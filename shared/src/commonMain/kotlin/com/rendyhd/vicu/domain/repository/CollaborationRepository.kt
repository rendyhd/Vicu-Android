package com.rendyhd.vicu.domain.repository

import com.rendyhd.vicu.domain.model.CollabState
import com.rendyhd.vicu.domain.model.ProjectShares
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskComment
import com.rendyhd.vicu.domain.model.TaskSignal
import com.rendyhd.vicu.domain.model.User
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.flow.Flow

/**
 * Assignees, comments and project sharing (docs/cross-app-semantics-v1.md section 9). Room first:
 * assignee and comment changes show at once and go through the task write gate, so offline they
 * are queued and replayed in order. Sharing, read state and subscriptions need the server.
 */
interface CollaborationRepository {

    /** You and the access of every active project; recomputed as members and projects change. */
    val state: Flow<CollabState>

    /**
     * Comment counts and unread marks of the open tasks in shared projects, by task id, plus those
     * of the done tasks a custom list asked for ([refreshSignalsFor]).
     */
    val signals: Flow<Map<Long, TaskSignal>>

    /** Reads the members of every active project, at most every ten minutes unless [force]. */
    suspend fun refreshMembers(force: Boolean = false): NetworkResult<Unit>

    /** Reads the comment counts and unread marks (and assignees) of the open tasks in shared projects. */
    suspend fun refreshSignals(): NetworkResult<Unit>

    /**
     * Reads the comment counts and unread marks of the shared-project tasks a custom list fetches
     * with the server [filter], done ones included, and keeps them beside the others. A list that
     * shows done tasks and looks at comments (cross-app semantics 3.3) calls it after its fetch.
     */
    suspend fun refreshSignalsFor(filter: String): NetworkResult<Unit>

    /** [refreshMembers], then [refreshSignals]; what a screen or sync refresh calls. */
    suspend fun refresh(force: Boolean = false): NetworkResult<Unit>

    // --- Assignees -----------------------------------------------------------------------------

    suspend fun setAssigned(task: Task, person: User, assigned: Boolean): NetworkResult<Unit>

    /**
     * The permission of each member of [projectId] for the "Can view only" note (section 9.1), by
     * user id; null for a member whose permission is not known. Reads the shares of the project and
     * its parents; a share list that cannot be read counts as unknown.
     */
    suspend fun memberPermissions(projectId: Long): Map<Long, Int?>

    // --- Comments ------------------------------------------------------------------------------

    /** The thread of a task, oldest first, with what waits in the queue. */
    fun comments(taskId: Long): Flow<List<TaskComment>>

    /** Reads the newest page of a thread; true when older pages exist. */
    suspend fun refreshComments(taskId: Long): NetworkResult<Boolean>

    /** Reads an older page ([page] counts from 1, newest first); true when still older pages exist. */
    suspend fun loadOlderComments(taskId: Long, page: Int): NetworkResult<Boolean>

    suspend fun postComment(taskId: Long, html: String): NetworkResult<Unit>
    suspend fun editComment(taskId: Long, commentId: Long, html: String): NetworkResult<Unit>
    suspend fun deleteComment(taskId: Long, commentId: Long): NetworkResult<Unit>

    /** Clears the unread mark here at once and on the server when it can be reached. */
    suspend fun markRead(taskId: Long)

    suspend fun isSubscribed(taskId: Long): NetworkResult<Boolean>
    suspend fun setSubscribed(taskId: Long, subscribed: Boolean): NetworkResult<Unit>

    // --- Sharing (admins only, online only) -----------------------------------------------------

    suspend fun shares(projectId: Long): NetworkResult<ProjectShares>

    /** The person with exactly this username, or null when there is none. */
    suspend fun findUser(username: String): NetworkResult<User?>

    suspend fun share(projectId: Long, username: String, permission: Int): NetworkResult<Unit>
    suspend fun updateShare(projectId: Long, username: String, permission: Int): NetworkResult<Unit>
    suspend fun removeShare(projectId: Long, username: String): NetworkResult<Unit>
}
