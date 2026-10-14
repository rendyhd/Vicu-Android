package com.rendyhd.vicu.data.local

import com.rendyhd.vicu.data.local.dao.TASK_SEARCH_SQL
import com.rendyhd.vicu.util.SqlLike
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Runs the search query Room compiles (TaskDao.search) against a real SQLite built from the
 * exported schema: what matches, in which order, and that `%`, `_` and the escape character in
 * the typed text match themselves (A-UI-16, June NEW-16).
 */
class TaskSearchSqlTest {

    private val connection: Connection = ExportedSchema.database(3)

    private fun insert(
        id: Long,
        title: String,
        description: String = "",
        done: Boolean = false,
        updated: String = "2026-10-01T00:00:00Z",
        isMetadata: Boolean = false,
    ) {
        connection.prepareStatement(
            "INSERT INTO tasks (id, title, description, done, doneAt, dueDate, priority, projectId, repeatAfter, repeatMode, " +
                "startDate, endDate, hexColor, percentDone, task_index, position, kanbanPosition, bucketId, created, updated, " +
                "createdById, createdByUsername, labelsJson, remindersJson, attachmentsJson, relatedTasksJson, isFavorite, isMetadata) " +
                "VALUES (?, ?, ?, ?, '', '', 0, 7, 0, 0, '', '', '', 0.0, 0, 0.0, 0.0, 0, '', ?, 0, '', '[]', '[]', '[]', '{}', 0, ?)",
        ).use {
            it.setLong(1, id)
            it.setString(2, title)
            it.setString(3, description)
            it.setInt(4, if (done) 1 else 0)
            it.setString(5, updated)
            it.setInt(6, if (isMetadata) 1 else 0)
            it.executeUpdate()
        }
    }

    /** The ids the query returns for the text a user typed, in order. */
    private fun search(typed: String): List<Long> {
        val sql = TASK_SEARCH_SQL.replace(":pattern", "?")
        return connection.prepareStatement(sql).use { statement ->
            val pattern = SqlLike.contains(typed)
            repeat(statement.parameterMetaData.parameterCount) { statement.setString(it + 1, pattern) }
            statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong("id")) } }
        }
    }

    @Test
    fun `the title and the description are searched, in any case`() {
        insert(1, "Buy MILK")
        insert(2, "Call mum", description = "<p>ask about the milk</p>")
        insert(3, "Unrelated")

        assertEquals(setOf(1L, 2L), search("milk").toSet())
    }

    @Test
    fun `completed tasks are found, after the open ones`() {
        insert(1, "Milk run", done = true, updated = "2026-10-05T00:00:00Z")
        insert(2, "Milk bottle", updated = "2026-10-01T00:00:00Z")
        insert(3, "Milk shake", updated = "2026-10-03T00:00:00Z")

        assertEquals(listOf(3L, 2L, 1L), search("milk"), "open first, newest change first, completed last")
    }

    @Test
    fun `percent matches a percent sign and nothing else`() {
        insert(1, "Save 100% of it")
        insert(2, "Save 1000 of it")
        insert(3, "100 percent")

        assertEquals(listOf(1L), search("100%"))
        assertEquals(emptyList(), search("%%"), "a typed percent is not a wildcard")
    }

    @Test
    fun `underscore matches an underscore and not any character`() {
        insert(1, "file a_b.txt")
        insert(2, "file axb.txt")

        assertEquals(listOf(1L), search("a_b"))
    }

    @Test
    fun `a backslash matches a backslash`() {
        insert(1, "C:\\temp\\notes")
        insert(2, "C:/temp/notes")

        assertEquals(listOf(1L), search("C:\\temp"))
        assertEquals(listOf(1L), search("\\notes"))
    }

    @Test
    fun `quotes and brackets are plain text`() {
        insert(1, "it's [done] (really)")

        assertEquals(listOf(1L), search("it's [done]"))
        assertEquals(listOf(1L), search("(really)"))
    }

    @Test
    fun `sync metadata tasks are never found`() {
        insert(1, "Vitamin D", description = "<!-- vicu-routine:v1:abc -->", done = true, isMetadata = true)
        insert(2, "Vitamin shop")

        assertEquals(listOf(2L), search("vitamin"))
    }

    @Test
    fun `a one letter search is capped`() {
        for (id in 1L..350L) insert(id, "Task $id")

        assertEquals(300, search("t").size)
    }
}
