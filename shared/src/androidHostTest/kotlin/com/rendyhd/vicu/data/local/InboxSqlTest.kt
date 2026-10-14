package com.rendyhd.vicu.data.local

import com.rendyhd.vicu.data.local.dao.INBOX_TASKS_SQL
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Inbox query Room compiles (TaskDao.getInboxTasks), run against a real SQLite: position order, ties oldest first. */
class InboxSqlTest {

    private val connection: Connection = ExportedSchema.database(3)

    private fun insert(
        id: Long,
        position: Double,
        created: String = "2026-10-01T00:00:00Z",
        projectId: Long = 5,
        done: Boolean = false,
        dueDate: String = "",
        isMetadata: Boolean = false,
    ) {
        connection.prepareStatement(
            "INSERT INTO tasks (id, title, description, done, doneAt, dueDate, priority, projectId, repeatAfter, repeatMode, " +
                "startDate, endDate, hexColor, percentDone, task_index, position, kanbanPosition, bucketId, created, updated, " +
                "createdById, createdByUsername, labelsJson, remindersJson, attachmentsJson, relatedTasksJson, isFavorite, isMetadata) " +
                "VALUES (?, 'T', '', ?, '', ?, 0, ?, 0, 0, '', '', '', 0.0, 0, ?, 0.0, 0, ?, '', 0, '', '[]', '[]', '[]', '{}', 0, ?)",
        ).use {
            it.setLong(1, id)
            it.setInt(2, if (done) 1 else 0)
            it.setString(3, dueDate)
            it.setLong(4, projectId)
            it.setDouble(5, position)
            it.setString(6, created)
            it.setInt(7, if (isMetadata) 1 else 0)
            it.executeUpdate()
        }
    }

    private fun inbox(includeDated: Boolean = true): List<Long> {
        // The query binds :inboxProjectId first and :includeDated second.
        val sql = INBOX_TASKS_SQL.replace(":inboxProjectId", "?").replace(":includeDated", "?")
        return connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, 5)
            statement.setInt(2, if (includeDated) 1 else 0)
            statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong("id")) } }
        }
    }

    @Test
    fun `tasks come in position order, not newest first`() {
        insert(1, position = 300.0, created = "2026-10-03T00:00:00Z")
        insert(2, position = 100.0, created = "2026-10-01T00:00:00Z")
        insert(3, position = 200.0, created = "2026-10-02T00:00:00Z")

        assertEquals(listOf(2L, 3L, 1L), inbox())
    }

    @Test
    fun `tasks without a known position are oldest first`() {
        insert(1, position = 0.0, created = "2026-10-03T00:00:00Z")
        insert(2, position = 0.0, created = "2026-10-01T00:00:00Z")
        insert(3, position = 0.0, created = "2026-10-02T00:00:00Z")

        assertEquals(listOf(2L, 3L, 1L), inbox())
    }

    @Test
    fun `done, other projects and metadata tasks are left out`() {
        insert(1, position = 1.0)
        insert(2, position = 2.0, done = true)
        insert(3, position = 3.0, projectId = 6)
        insert(4, position = 4.0, isMetadata = true)

        assertEquals(listOf(1L), inbox())
    }

    @Test
    fun `dated tasks can be left out`() {
        insert(1, position = 1.0)
        insert(2, position = 2.0, dueDate = "2026-10-08T21:59:59Z")
        insert(3, position = 3.0, dueDate = "0001-01-01T00:00:00Z")

        assertEquals(listOf(1L, 3L), inbox(includeDated = false))
        assertEquals(listOf(1L, 2L, 3L), inbox(includeDated = true))
    }
}
