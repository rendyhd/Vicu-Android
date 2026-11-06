package com.rendyhd.vicu.data.local

import com.rendyhd.vicu.data.local.dao.PLAN_TASKS_SQL
import com.rendyhd.vicu.data.local.dao.STARTING_TODAY_WITHOUT_DUE_TODAY_SQL
import com.rendyhd.vicu.data.local.dao.TODAY_TASKS_SQL
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals

/** The queries Room compiles for Today and the Plan screen, run against a real SQLite (contract 2 and 16). */
class PlannerSqlTest {

    private val connection: Connection = ExportedSchema.database(3)

    private val startOfToday = "2026-10-06T00:00:00Z"
    private val startOfTomorrow = "2026-10-07T00:00:00Z"

    private fun insert(
        id: Long,
        dueDate: String = "",
        startDate: String = "",
        endDate: String = "",
        done: Boolean = false,
        isMetadata: Boolean = false,
    ) {
        connection.prepareStatement(
            "INSERT INTO tasks (id, title, description, done, doneAt, dueDate, priority, projectId, repeatAfter, repeatMode, " +
                "startDate, endDate, hexColor, percentDone, task_index, position, kanbanPosition, bucketId, created, updated, " +
                "createdById, createdByUsername, labelsJson, remindersJson, attachmentsJson, relatedTasksJson, isFavorite, isMetadata) " +
                "VALUES (?, 'T', '', ?, '', ?, 0, 1, 0, 0, ?, ?, '', 0.0, 0, 0.0, 0.0, 0, '', '', 0, '', '[]', '[]', '[]', '{}', 0, ?)",
        ).use {
            it.setLong(1, id)
            it.setInt(2, if (done) 1 else 0)
            it.setString(3, dueDate)
            it.setString(4, startDate)
            it.setString(5, endDate)
            it.setInt(6, if (isMetadata) 1 else 0)
            it.executeUpdate()
        }
    }

    /** Binds `:name` parameters in the order they appear, as many times as they appear. */
    private fun run(sql: String, values: Map<String, Any>): List<Long> {
        val names = Regex(":([a-zA-Z]+)").findAll(sql).map { it.groupValues[1] }.toList()
        val bound = sql.replace(Regex(":[a-zA-Z]+"), "?")
        return connection.prepareStatement(bound).use { statement ->
            names.forEachIndexed { index, name ->
                when (val value = values.getValue(name)) {
                    is String -> statement.setString(index + 1, value)
                    is Boolean -> statement.setInt(index + 1, if (value) 1 else 0)
                    else -> error("cannot bind $value")
                }
            }
            statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong("id")) } }
        }.sorted()
    }

    private fun today(): List<Long> =
        run(TODAY_TASKS_SQL, mapOf("startOfToday" to startOfToday, "startOfTomorrow" to startOfTomorrow))

    private fun plan(from: String, to: String, withOverdue: Boolean): List<Long> = run(
        PLAN_TASKS_SQL,
        mapOf("fromInclusive" to from, "toExclusive" to to, "startOfToday" to startOfToday, "withOverdue" to withOverdue),
    )

    @Test
    fun `today holds what is due and what starts today, and no other start`() {
        insert(1, dueDate = "2026-10-06T10:00:00Z")
        insert(2, dueDate = "2026-10-01T10:00:00Z")
        insert(3, startDate = "2026-10-06T09:00:00Z", endDate = "2026-10-06T10:00:00Z")
        insert(4, dueDate = "2026-10-09T10:00:00Z", startDate = "2026-10-06T09:00:00Z", endDate = "2026-10-06T10:00:00Z")
        insert(5, startDate = "2026-10-07T09:00:00Z", endDate = "2026-10-07T10:00:00Z")
        insert(6, startDate = "2026-10-05T09:00:00Z", endDate = "2026-10-05T10:00:00Z")
        insert(7, dueDate = "2026-10-08T10:00:00Z")
        insert(8)
        insert(9, dueDate = "0001-01-01T00:00:00Z")

        assertEquals(listOf(1L, 2L, 3L, 4L), today())
    }

    @Test
    fun `done and hidden tasks are not on today`() {
        insert(1, startDate = "2026-10-06T09:00:00Z", endDate = "2026-10-06T10:00:00Z", done = true)
        insert(2, startDate = "2026-10-06T09:00:00Z", endDate = "2026-10-06T10:00:00Z", isMetadata = true)
        insert(3, dueDate = "2026-10-06T10:00:00Z", isMetadata = true)

        assertEquals(emptyList(), today())
    }

    @Test
    fun `the plan takes a block in range even when done, and only open tasks by their due date`() {
        insert(1, startDate = "2026-10-08T09:00:00Z", endDate = "2026-10-08T10:00:00Z")
        insert(2, startDate = "2026-10-08T09:00:00Z", endDate = "2026-10-08T10:00:00Z", done = true)
        insert(3, dueDate = "2026-10-08T10:00:00Z")
        insert(4, dueDate = "2026-10-08T10:00:00Z", done = true)
        insert(5, dueDate = "2026-10-09T10:00:00Z")
        insert(6, startDate = "2026-10-09T09:00:00Z", endDate = "2026-10-09T10:00:00Z")
        insert(7)
        insert(8, startDate = "2026-10-08T09:00:00Z", endDate = "2026-10-08T10:00:00Z", isMetadata = true)

        assertEquals(listOf(1L, 2L, 3L), plan("2026-10-08T00:00:00Z", "2026-10-09T00:00:00Z", withOverdue = false))
    }

    @Test
    fun `overdue is on the plan only while today is shown`() {
        insert(1, dueDate = "2026-10-01T10:00:00Z")
        insert(2, dueDate = "2026-10-01T10:00:00Z", done = true)
        insert(3, dueDate = "0001-01-01T00:00:00Z")

        assertEquals(listOf(1L), plan(startOfToday, startOfTomorrow, withOverdue = true))
        assertEquals(emptyList(), plan("2026-10-08T00:00:00Z", "2026-10-09T00:00:00Z", withOverdue = false))
    }

    @Test
    fun `what starts today without being due counts once in the summary`() {
        insert(1, startDate = "2026-10-06T09:00:00Z", endDate = "2026-10-06T10:00:00Z")
        insert(2, dueDate = "2026-10-06T10:00:00Z", startDate = "2026-10-06T09:00:00Z", endDate = "2026-10-06T10:00:00Z")
        insert(3, dueDate = "2026-10-09T10:00:00Z", startDate = "2026-10-06T09:00:00Z", endDate = "2026-10-06T10:00:00Z")

        assertEquals(
            listOf(1L, 3L),
            run(STARTING_TODAY_WITHOUT_DUE_TODAY_SQL, mapOf("startOfToday" to startOfToday, "startOfTomorrow" to startOfTomorrow)),
        )
    }
}
