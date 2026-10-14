package com.rendyhd.vicu.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.rendyhd.vicu.data.local.entity.ProjectMemberEntity
import com.rendyhd.vicu.data.local.entity.TaskCommentEntity
import com.rendyhd.vicu.data.local.entity.TaskSignalEntity
import kotlinx.coroutines.flow.Flow

/** Project members, comment signals and comment threads (cross-app semantics section 9). */
@Dao
interface CollaborationDao {

    // --- Members -------------------------------------------------------------------------------

    @Query("SELECT * FROM project_members ORDER BY projectId ASC, position ASC")
    fun observeMembers(): Flow<List<ProjectMemberEntity>>

    @Query("SELECT * FROM project_members WHERE projectId = :projectId ORDER BY position ASC")
    suspend fun getMembers(projectId: Long): List<ProjectMemberEntity>

    @Query("DELETE FROM project_members WHERE projectId = :projectId")
    suspend fun deleteMembers(projectId: Long)

    @Upsert
    suspend fun upsertMembers(members: List<ProjectMemberEntity>)

    /** The members of one project, as the server listed them. */
    @Transaction
    suspend fun replaceMembers(projectId: Long, members: List<ProjectMemberEntity>) {
        deleteMembers(projectId)
        if (members.isNotEmpty()) upsertMembers(members)
    }

    /** Members of projects that are gone (deleted, or no longer shared with you). */
    @Query("DELETE FROM project_members WHERE projectId NOT IN (:projectIds)")
    suspend fun deleteMembersExcept(projectIds: List<Long>)

    // --- Comment signals -----------------------------------------------------------------------

    @Query("SELECT * FROM task_signals")
    fun observeSignals(): Flow<List<TaskSignalEntity>>

    @Query("SELECT * FROM task_signals WHERE taskId = :taskId")
    suspend fun getSignal(taskId: Long): TaskSignalEntity?

    @Upsert
    suspend fun upsertSignals(signals: List<TaskSignalEntity>)

    @Query("DELETE FROM task_signals")
    suspend fun deleteAllSignals()

    /** The signals of the open tasks of the shared projects, as the server counted them; every other row goes. */
    @Transaction
    suspend fun replaceSignals(signals: List<TaskSignalEntity>) {
        deleteAllSignals()
        if (signals.isNotEmpty()) upsertSignals(signals)
    }

    @Query("UPDATE task_signals SET isUnread = 0 WHERE taskId = :taskId")
    suspend fun markRead(taskId: Long)

    /** Adds [delta] to a task's comment count (an optimistic create or delete). */
    @Transaction
    suspend fun changeCommentCount(taskId: Long, delta: Int) {
        val current = getSignal(taskId) ?: TaskSignalEntity(taskId)
        upsertSignals(listOf(current.copy(commentCount = (current.commentCount + delta).coerceAtLeast(0))))
    }

    @Query("UPDATE OR REPLACE task_signals SET taskId = :realId WHERE taskId = :tempId")
    suspend fun remapSignalTask(tempId: Long, realId: Long)

    // --- Comments ------------------------------------------------------------------------------

    @Query("SELECT * FROM task_comments WHERE taskId = :taskId ORDER BY created ASC, id ASC")
    fun observeComments(taskId: Long): Flow<List<TaskCommentEntity>>

    @Query("SELECT * FROM task_comments WHERE id = :id")
    suspend fun getComment(id: Long): TaskCommentEntity?

    @Upsert
    suspend fun upsertComments(comments: List<TaskCommentEntity>)

    @Query("DELETE FROM task_comments WHERE id = :id")
    suspend fun deleteComment(id: Long)

    @Query("DELETE FROM task_comments WHERE taskId = :taskId AND pending = 0")
    suspend fun deleteSentComments(taskId: Long)

    @Query("DELETE FROM task_comments WHERE taskId = :taskId AND pending = 0 AND created >= :from")
    suspend fun deleteSentCommentsFrom(taskId: Long, from: String)

    /**
     * The newest comments of a thread as the server sent them. With [wholeThread] every sent comment
     * not in the answer goes (deleted elsewhere); otherwise only those in the span the answer covers,
     * so older pages loaded by scrolling stay. Comments still waiting in the queue ([keepIds]) keep
     * their rows: the server does not have them yet.
     */
    @Transaction
    suspend fun replaceComments(taskId: Long, comments: List<TaskCommentEntity>, keepIds: Set<Long>, wholeThread: Boolean) {
        val kept = keepIds.mapNotNull { getComment(it) }
        val oldest = comments.minOfOrNull { it.created }
        when {
            wholeThread || oldest == null -> deleteSentComments(taskId)
            else -> deleteSentCommentsFrom(taskId, oldest)
        }
        upsertComments(comments.filter { it.id !in keepIds } + kept)
    }

    /** A comment written offline reached the server: it takes its real id. */
    @Transaction
    suspend fun adoptComment(tempId: Long, created: TaskCommentEntity) {
        deleteComment(tempId)
        upsertComments(listOf(created))
    }

    @Query("UPDATE task_comments SET taskId = :realId WHERE taskId = :tempId")
    suspend fun remapCommentTask(tempId: Long, realId: Long)
}
