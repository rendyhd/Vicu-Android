package com.rendyhd.vicu.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.rendyhd.vicu.data.local.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    /** The Inbox in its list view's order (see [INBOX_TASKS_SQL]); the screen puts dated tasks first. */
    @Query(INBOX_TASKS_SQL)
    fun getInboxTasks(inboxProjectId: Long, includeDated: Boolean): Flow<List<TaskEntity>>

    @Query(
        """
        SELECT * FROM tasks
        WHERE done = 0
        AND isMetadata = 0
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
        AND isMetadata = 0
        AND dueDate >= :startOfTomorrow
        AND dueDate != '0001-01-01T00:00:00Z'
        AND dueDate != ''
        ORDER BY dueDate ASC
        """
    )
    fun getUpcomingTasks(startOfTomorrow: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE done = 0 AND isMetadata = 0 AND projectId != :inboxProjectId ORDER BY updated DESC")
    fun getAnytimeTasks(inboxProjectId: Long): Flow<List<TaskEntity>>

    @Query(
        """
        SELECT * FROM tasks
        WHERE done = 1
        AND isMetadata = 0
        AND (:cutoff = '' OR doneAt = '' OR doneAt = '0001-01-01T00:00:00Z' OR doneAt >= :cutoff)
        ORDER BY doneAt DESC
        """
    )
    fun getLogbookTasks(cutoff: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE isMetadata = 0 AND projectId = :projectId ORDER BY position ASC")
    fun getByProjectId(projectId: Long): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun getById(id: Long): Flow<TaskEntity?>

    /** Open and completed tasks whose title or description matches [pattern] (a LIKE pattern, see [com.rendyhd.vicu.util.SqlLike]). */
    @Query(TASK_SEARCH_SQL)
    fun search(pattern: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE done = 0 AND isMetadata = 0 ORDER BY updated DESC")
    fun getAllOpenTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE isMetadata = 0")
    fun getAllTasksFlow(): Flow<List<TaskEntity>>

    /** Open and done task counts per project, for the drawer's progress rings (see [PROJECT_TALLIES_SQL]). */
    @Query(PROJECT_TALLIES_SQL)
    fun observeProjectTallies(): Flow<List<ProjectTallyRow>>

    /** The hidden done tasks (carriers, archive parts) the phone holds: not the user's tasks, so not counted in a progress ring. */
    @Query("SELECT id, projectId FROM tasks WHERE isMetadata = 1 AND done = 1")
    suspend fun getDoneMetadataRefs(): List<MetadataTaskRef>

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
        AND isMetadata = 0
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
        AND isMetadata = 0
        AND dueDate >= :startOfToday
        AND dueDate < :startOfTomorrow
        AND dueDate != '0001-01-01T00:00:00Z'
        AND dueDate != ''
        """
    )
    suspend fun countDueToday(startOfToday: String, startOfTomorrow: String): Int

    /** Tomorrow only (not every later task): the third category of the daily summary. */
    @Query(
        """
        SELECT COUNT(*) FROM tasks
        WHERE done = 0
        AND isMetadata = 0
        AND dueDate >= :startOfTomorrow
        AND dueDate < :startOfDayAfterTomorrow
        AND dueDate != '0001-01-01T00:00:00Z'
        AND dueDate != ''
        """
    )
    suspend fun countDueTomorrow(startOfTomorrow: String, startOfDayAfterTomorrow: String): Int

    @Query(
        """
        SELECT * FROM tasks
        WHERE done = 0
        AND isMetadata = 0
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
        AND isMetadata = 0
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
        WHERE done = 0 AND isMetadata = 0 AND projectId = :inboxProjectId
        AND (:includeDated = 1 OR dueDate = '' OR dueDate = '0001-01-01T00:00:00Z')
        ORDER BY created DESC LIMIT :limit
        """
    )
    suspend fun getInboxTasksSync(inboxProjectId: Long, limit: Int, includeDated: Boolean): List<TaskEntity>

    @Query(
        """
        SELECT * FROM tasks
        WHERE done = 0
        AND isMetadata = 0
        AND dueDate >= :startOfTomorrow
        AND dueDate != '0001-01-01T00:00:00Z'
        AND dueDate != ''
        ORDER BY dueDate ASC
        LIMIT :limit
        """
    )
    suspend fun getUpcomingTasksSync(startOfTomorrow: String, limit: Int): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE done = 0 AND isMetadata = 0 AND projectId != :inboxProjectId ORDER BY updated DESC LIMIT :limit")
    suspend fun getAnytimeTasksSync(inboxProjectId: Long, limit: Int): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE done = 0 AND isMetadata = 0 AND projectId = :projectId ORDER BY position ASC LIMIT :limit")
    suspend fun getByProjectIdSync(projectId: Long, limit: Int): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE remindersJson != '[]' AND done = 0")
    suspend fun getAllWithReminders(): List<TaskEntity>

    /** Writes the rows exactly as given. Callers use [upsert] and [upsertAll], which keep stored positions. */
    @Upsert
    suspend fun upsertRows(tasks: List<TaskEntity>)

    /** The non-zero stored positions of at most [MAX_SQL_ID_PARAMS] of the given tasks. */
    @Query("SELECT id, position FROM tasks WHERE position != 0 AND id IN (:ids)")
    suspend fun getStoredPositionsChunk(ids: List<Long>): List<StoredPosition>

    /**
     * Writes the rows, except that a position of 0 never replaces a stored one. The server only
     * sends a task's position when it is fetched through a list view; every other answer (the task
     * lists, the reply to an update) says 0 for "not told", and writing that would throw away the
     * order the Inbox was just given. Positions are changed on purpose through [updatePosition] and
     * [updatePositions].
     */
    @Transaction
    suspend fun upsertAll(tasks: List<TaskEntity>) {
        val unplaced = tasks.filter { it.position == 0.0 }.map { it.id }
        val stored = if (unplaced.isEmpty()) {
            emptyMap()
        } else {
            unplaced.sqlIdChunks().flatMap { getStoredPositionsChunk(it) }.associate { it.id to it.position }
        }
        upsertRows(keepStoredPositions(tasks, stored))
    }

    suspend fun upsert(task: TaskEntity) = upsertAll(listOf(task))

    @Query("SELECT * FROM tasks")
    suspend fun getAllSync(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE description LIKE '%<!-- vicu-routine:%'")
    suspend fun getRoutineCarriersSync(): List<TaskEntity>

    /** Ids of the rows a full reconcile may delete: open tasks and rows that only exist on this device. */
    @Query("SELECT id FROM tasks WHERE done = 0 OR id < 0")
    suspend fun getOpenOrLocalOnlyIds(): List<Long>

    /** Completed tasks of the server (positive ids) finished after [doneAt], for the Logbook's page-one reconcile. */
    @Query(
        """
        SELECT * FROM tasks
        WHERE done = 1 AND id > 0
        AND doneAt > :doneAt AND doneAt != '' AND doneAt != '0001-01-01T00:00:00Z'
        """
    )
    suspend fun getCompletedAfter(doneAt: String): List<TaskEntity>

    /** Tasks whose cached labels contain [pattern] (a LIKE pattern); a pre-filter, callers check exactly. */
    @Query("SELECT * FROM tasks WHERE labelsJson LIKE :pattern")
    suspend fun getByLabelsJsonLike(pattern: String): List<TaskEntity>

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

    /** Sets several positions in one transaction: the order of a list view, as the server holds it. */
    @Transaction
    suspend fun updatePositions(positions: Map<Long, Double>) {
        positions.forEach { (taskId, position) -> updatePosition(taskId, position) }
    }

    @Query("DELETE FROM tasks")
    suspend fun deleteAll()
}
