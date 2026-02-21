package com.rendyhd.vicu.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.rendyhd.vicu.data.local.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Query("SELECT * FROM tasks WHERE done = 0 AND projectId = :inboxProjectId ORDER BY created DESC")
    fun getInboxTasks(inboxProjectId: Long): Flow<List<TaskEntity>>

    @Query(
        """
        SELECT * FROM tasks
        WHERE done = 0
        AND dueDate <= :endOfToday
        AND dueDate != '0001-01-01T00:00:00Z'
        AND dueDate != ''
        ORDER BY dueDate ASC
        """
    )
    fun getTodayTasks(endOfToday: String): Flow<List<TaskEntity>>

    @Query(
        """
        SELECT * FROM tasks
        WHERE done = 0
        AND dueDate > :endOfToday
        AND dueDate != '0001-01-01T00:00:00Z'
        AND dueDate != ''
        ORDER BY dueDate ASC
        """
    )
    fun getUpcomingTasks(endOfToday: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE done = 0 AND projectId != :inboxProjectId ORDER BY updated DESC")
    fun getAnytimeTasks(inboxProjectId: Long): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE done = 1 ORDER BY doneAt DESC")
    fun getLogbookTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE projectId = :projectId ORDER BY position ASC")
    fun getByProjectId(projectId: Long): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun getById(id: Long): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE title LIKE '%' || :query || '%' AND done = 0")
    fun searchByTitle(query: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE done = 0 ORDER BY updated DESC")
    fun getAllOpenTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getByIdSync(id: Long): TaskEntity?

    @Query(
        """
        SELECT COUNT(*) FROM tasks
        WHERE done = 0
        AND dueDate < :startOfToday
        AND dueDate != '0001-01-01T00:00:00Z'
        AND dueDate != ''
        """
    )
    suspend fun countOverdue(startOfToday: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM tasks
        WHERE done = 0
        AND dueDate >= :startOfToday
        AND dueDate <= :endOfToday
        AND dueDate != '0001-01-01T00:00:00Z'
        AND dueDate != ''
        """
    )
    suspend fun countDueToday(startOfToday: String, endOfToday: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM tasks
        WHERE done = 0
        AND dueDate > :endOfToday
        AND dueDate != '0001-01-01T00:00:00Z'
        AND dueDate != ''
        """
    )
    suspend fun countUpcoming(endOfToday: String): Int

    @Query(
        """
        SELECT * FROM tasks
        WHERE done = 0
        AND dueDate <= :endOfToday
        AND dueDate != '0001-01-01T00:00:00Z'
        AND dueDate != ''
        ORDER BY dueDate ASC
        LIMIT :limit
        """
    )
    suspend fun getTodayTasksSync(endOfToday: String, limit: Int): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE done = 0 AND projectId = :inboxProjectId ORDER BY created DESC LIMIT :limit")
    suspend fun getInboxTasksSync(inboxProjectId: Long, limit: Int): List<TaskEntity>

    @Query(
        """
        SELECT * FROM tasks
        WHERE done = 0
        AND dueDate > :endOfToday
        AND dueDate != '0001-01-01T00:00:00Z'
        AND dueDate != ''
        ORDER BY dueDate ASC
        LIMIT :limit
        """
    )
    suspend fun getUpcomingTasksSync(endOfToday: String, limit: Int): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE done = 0 AND projectId != :inboxProjectId ORDER BY updated DESC LIMIT :limit")
    suspend fun getAnytimeTasksSync(inboxProjectId: Long, limit: Int): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE done = 0 AND projectId = :projectId ORDER BY position ASC LIMIT :limit")
    suspend fun getByProjectIdSync(projectId: Long, limit: Int): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE done = 0 ORDER BY updated DESC LIMIT :limit")
    suspend fun getAllOpenTasksSync(limit: Int): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE remindersJson != '[]' AND done = 0")
    suspend fun getAllWithReminders(): List<TaskEntity>

    @Upsert
    suspend fun upsert(task: TaskEntity)

    @Upsert
    suspend fun upsertAll(tasks: List<TaskEntity>)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM tasks")
    suspend fun deleteAll()
}
