package com.rendyhd.vicu.data.local

import com.rendyhd.vicu.data.local.dao.CollaborationDao
import com.rendyhd.vicu.data.local.entity.ProjectMemberEntity
import com.rendyhd.vicu.data.local.entity.TaskCommentEntity
import com.rendyhd.vicu.data.local.entity.TaskSignalEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** An in-memory [CollaborationDao]; the transactions are the interface's own default bodies. */
class FakeCollaborationDao : CollaborationDao {
    val members = MutableStateFlow<List<ProjectMemberEntity>>(emptyList())
    val signals = MutableStateFlow<Map<Long, TaskSignalEntity>>(emptyMap())
    val comments = MutableStateFlow<Map<Long, TaskCommentEntity>>(emptyMap())

    override fun observeMembers(): Flow<List<ProjectMemberEntity>> =
        members.map { list -> list.sortedWith(compareBy({ it.projectId }, { it.position })) }

    override suspend fun getMembers(projectId: Long): List<ProjectMemberEntity> =
        members.value.filter { it.projectId == projectId }.sortedBy { it.position }

    override suspend fun deleteMembers(projectId: Long) {
        members.value = members.value.filter { it.projectId != projectId }
    }

    override suspend fun upsertMembers(members: List<ProjectMemberEntity>) {
        val keys = members.map { it.projectId to it.userId }.toSet()
        this.members.value = this.members.value.filter { (it.projectId to it.userId) !in keys } + members
    }

    override suspend fun deleteMembersExcept(projectIds: List<Long>) {
        members.value = members.value.filter { it.projectId in projectIds }
    }

    override fun observeSignals(): Flow<List<TaskSignalEntity>> = signals.map { it.values.toList() }

    override suspend fun getSignal(taskId: Long): TaskSignalEntity? = signals.value[taskId]

    override suspend fun upsertSignals(signals: List<TaskSignalEntity>) {
        this.signals.value = this.signals.value + signals.associateBy { it.taskId }
    }

    override suspend fun deleteAllSignals() {
        signals.value = emptyMap()
    }

    override suspend fun markRead(taskId: Long) {
        signals.value[taskId]?.let { upsertSignals(listOf(it.copy(isUnread = false))) }
    }

    override suspend fun remapSignalTask(tempId: Long, realId: Long) {
        val row = signals.value[tempId] ?: return
        signals.value = signals.value - tempId + (realId to row.copy(taskId = realId))
    }

    override fun observeComments(taskId: Long): Flow<List<TaskCommentEntity>> =
        comments.map { all -> all.values.filter { it.taskId == taskId }.sortedWith(compareBy({ it.created }, { it.id })) }

    override suspend fun getComment(id: Long): TaskCommentEntity? = comments.value[id]

    override suspend fun upsertComments(comments: List<TaskCommentEntity>) {
        this.comments.value = this.comments.value + comments.associateBy { it.id }
    }

    override suspend fun deleteComment(id: Long) {
        comments.value = comments.value - id
    }

    override suspend fun deleteSentComments(taskId: Long) {
        comments.value = comments.value.filterValues { it.taskId != taskId || it.pending }
    }

    override suspend fun deleteSentCommentsFrom(taskId: Long, from: String) {
        comments.value = comments.value.filterValues { it.taskId != taskId || it.pending || it.created < from }
    }

    override suspend fun remapCommentTask(tempId: Long, realId: Long) {
        comments.value = comments.value.mapValues { (_, c) -> if (c.taskId == tempId) c.copy(taskId = realId) else c }
    }
}
