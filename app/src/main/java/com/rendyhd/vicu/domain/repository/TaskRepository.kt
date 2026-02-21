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
    fun searchByTitle(query: String): Flow<List<Task>>
    fun getAllOpenTasks(): Flow<List<Task>>

    suspend fun create(task: Task): NetworkResult<Task>
    suspend fun update(task: Task): NetworkResult<Task>
    suspend fun delete(taskId: Long): NetworkResult<Unit>
    suspend fun toggleDone(task: Task): NetworkResult<Task>
    suspend fun createSubtask(parentTaskId: Long, subtask: Task): NetworkResult<Task>
    suspend fun deleteRelation(taskId: Long, relationKind: String, otherTaskId: Long): NetworkResult<Unit>
    suspend fun deleteLocalByIds(ids: Set<Long>)
    suspend fun refreshAll(filters: Map<String, String> = emptyMap()): NetworkResult<Unit>
}
