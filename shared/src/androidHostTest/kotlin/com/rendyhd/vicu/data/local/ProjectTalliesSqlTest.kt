package com.rendyhd.vicu.data.local

import com.rendyhd.vicu.data.local.dao.PROJECT_TALLIES_SQL
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Runs the tally query Room compiles (TaskDao.observeProjectTallies) against a real SQLite built
 * from the exported schema: open and done tasks per project, hidden carrier tasks left out.
 */
class ProjectTalliesSqlTest {

    private val connection: Connection = ExportedSchema.database(3)

    private fun insert(id: Long, project: Long, done: Boolean = false, isMetadata: Boolean = false) {
        connection.prepareStatement(
            "INSERT INTO tasks (id, title, description, done, doneAt, dueDate, priority, projectId, repeatAfter, repeatMode, " +
                "startDate, endDate, hexColor, percentDone, task_index, position, kanbanPosition, bucketId, created, updated, " +
                "createdById, createdByUsername, labelsJson, remindersJson, attachmentsJson, relatedTasksJson, isFavorite, isMetadata) " +
                "VALUES (?, 'T', '', ?, '', '', 0, ?, 0, 0, '', '', '', 0.0, 0, 0.0, 0.0, 0, '', '', 0, '', '[]', '[]', '[]', '{}', 0, ?)",
        ).use {
            it.setLong(1, id)
            it.setInt(2, if (done) 1 else 0)
            it.setLong(3, project)
            it.setInt(4, if (isMetadata) 1 else 0)
            it.executeUpdate()
        }
    }

    /** project id to (open, done on the phone). */
    private fun tallies(): Map<Long, Pair<Int, Int>> =
        connection.createStatement().use { statement ->
            statement.executeQuery(PROJECT_TALLIES_SQL).use { rs ->
                buildMap { while (rs.next()) put(rs.getLong("projectId"), rs.getInt("open") to rs.getInt("doneOnPhone")) }
            }
        }

    @Test
    fun `open and done tasks are counted per project`() {
        insert(1, project = 7)
        insert(2, project = 7)
        insert(3, project = 7, done = true)
        insert(4, project = 8, done = true)

        assertEquals(mapOf(7L to (2 to 1), 8L to (0 to 1)), tallies())
    }

    @Test
    fun `hidden carrier and archive tasks are not the user's tasks`() {
        insert(1, project = 7)
        insert(2, project = 7, done = true, isMetadata = true)
        insert(3, project = 9, done = true, isMetadata = true)

        assertEquals(mapOf(7L to (1 to 0)), tallies(), "a project that holds only a hidden task has no row")
    }

    @Test
    fun `no tasks, no rows`() {
        assertEquals(emptyMap(), tallies())
    }
}
