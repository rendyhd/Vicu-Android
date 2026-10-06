package com.rendyhd.vicu.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.rendyhd.vicu.data.local.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Query(
        """
        SELECT * FROM tasks
        WHERE done = 0 AND projectId = :inboxProjectId
        AND (:includeDated = 1 OR dueDate = '' OR dueDate = '0001-01-01T00:00:00Z')
        ORDER BY created DESC
        """
    )
    fun getInboxTasks(inboxProjectId: Long, includeDated: Boolean): Flow<List<TaskEntity>>

    @Query(
        """
        SELECT * FROM tasks
        WHERE done = 0
        AND dueDate < :startOfTomorrow
        AND dueDate != '0001-01-01T00:00:00Z'
        AND dueDate != ''
        ORDER BY dueDate ASC
        """
    )
    fun getTodayTasks(startOfTomorrow: String): Flow<List<TaskEntity>>

    @Query(
        """
        SELECT * FROM tasks
        WHERE done = 0
        AND dueDate >= :startOfTomorrow
        AND dueDate != '0001-01-01T00:00:00Z'
        AND dueDate != ''
        ORDER BY dueDate ASC
        """
    )
    fun getUpcomingTasks(startOfTomorrow: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE done = 0 AND projectId != :inboxProjectId ORDER BY updated DESC")
    fun getAnytimeTasks(inboxProjectId: Long): Flow<List<TaskEntity>>

    @Query(
        """
        SELECT * FROM tasks
        WHERE done = 1
        AND (:cutoff = '' OR doneAt = '' OR doneAt = '0001-01-01T00:00:00Z' OR doneAt >= :cutoff)
        ORDER BY doneAt DESC
        """
    )
    fun getLogbookTasks(cutoff: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE projectId = :projectId ORDER BY position ASC")
    fun getByProjectId(projectId: Long): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun getById(id: Long): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE title LIKE '%' || :query || '%' AND done = 0")
    fun searchByTitle(query: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE title LIKE '%' || :query || '%'")
    fun searchByTitleIncludingDone(query: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE done = 0 ORDER BY updated DESC")
    fun getAllOpenTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks")
    fun getAllTasksFlow(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE description LIKE '%<!-- vicu-routine:%'")
    fun getRoutineCarriersFlow(): Flow<List<TaskEntity>>

    /** At most [MAX_SQL_ID_PARAMS] ids; callers use [getByIds]. */
    @Query("SELECT * FROM tasks WHERE id IN (:ids)")
    suspend fun getByIdsChunk(ids: List<Long>): List<TaskEntity>

    /** Any number of ids; runs one query per [MAX_SQL_ID_PARAMS]. */
    suspend fun getByIds(ids: List<Long>): List<TaskEntity> =
        ids.sqlIdChunks().flatMap { getByIdsChunk(it) }

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
        AND dueDate < :startOfTomorrow
        AND dueDate != '0001-01-01T00:00:00Z'
        AND dueDate != ''
        """
    )
    suspend fun countDueToday(startOfToday: String, startOfTomorrow: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM tasks
        WHERE done = 0
        AND dueDate >= :startOfTomorrow
        AND dueDate != '0001-01-01T00:00:00Z'
        AND dueDate != ''
        """
    )
    suspend fun countUpcoming(startOfTomorrow: String): Int

    @Query(
        """
        SELECT * FROM tasks
        WHERE done = 0
        AND dueDate < :startOfTomorrow
        AND dueDate != '0001-01-01T00:00:00Z'
        AND dueDate != ''
        ORDER BY dueDate ASC
        LIMIT :limit
        """
    )
    suspend fun getTodayTasksSync(startOfTomorrow: String, limit: Int): List<TaskEntity>

    /** Tasks due strictly within today's window (excludes overdue) — for the daily summary list. */
    @Query(
        """
        SELECT * FROM tasks
        WHERE done = 0
        AND dueDate >= :startOfToday
        AND dueDate < :startOfTomorrow
        AND dueDate != '0001-01-01T00:00:00Z'
        AND dueDate != ''
        ORDER BY dueDate ASC
        LIMIT :limit
        """
    )
    suspend fun getDueTodaySync(startOfToday: String, startOfTomorrow: String, limit: Int): List<TaskEntity>

    @Query(
        """
        SELECT * FROM tasks
        WHERE done = 0 AND projectId = :inboxProjectId
        AND (:includeDated = 1 OR dueDate = '' OR dueDate = '0001-01-01T00:00:00Z')
        ORDER BY created DESC LIMIT :limit
        """
    )
    suspend fun getInboxTasksSync(inboxProjectId: Long, limit: Int, includeDated: Boolean): List<TaskEntity>

    @Query(
        """
        SELECT * FROM tasks
        WHERE done = 0
        AND dueDate >= :startOfTomorrow
        AND dueDate != '0001-01-01T00:00:00Z'
        AND dueDate != ''
        ORDER BY dueDate ASC
        LIMIT :limit
        """
    )
    suspend fun getUpcomingTasksSync(startOfTomorrow: String, limit: Int): List<TaskEntity>

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

    @Query("SELECT * FROM tasks")
    suspend fun getAllSync(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE description LIKE '%<!-- vicu-routine:%'")
    suspend fun getRoutineCarriersSync(): List<TaskEntity>

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** At most [MAX_SQL_ID_PARAMS] ids; callers use [deleteByIds]. */
    @Query("DELETE FROM tasks WHERE id IN (:ids)")
    suspend fun deleteByIdsChunk(ids: List<Long>)

    /** Any number of ids; runs one statement per [MAX_SQL_ID_PARAMS], atomically. */
    @Transaction
    suspend fun deleteByIds(ids: List<Long>) {
        ids.sqlIdChunks().forEach { deleteByIdsChunk(it) }
    }

    @Query("UPDATE tasks SET position = :position WHERE id = :taskId")
    suspend fun updatePosition(taskId: Long, position: Double)

    @Query("DELETE FROM tasks")
    suspend fun deleteAll()
}
