package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.serviceUnavailable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The repository's search reads Room only: it hands the DAO a pattern with the typed `%`, `_` and
 * backslash escaped (the real query is run against SQLite in TaskSearchSqlTest), leaves out
 * blank searches and sync metadata, and does not hide nested subtasks.
 */
class TaskRepositorySearchTest {

    private val dao = FakeTaskDao(
        listOf(
            TaskEntity(id = 1, title = "Save 100% of it", projectId = 7),
            TaskEntity(id = 2, title = "Save 1000 of it", projectId = 7),
            TaskEntity(id = 3, title = "Milk run", done = true, projectId = 7),
            TaskEntity(id = 4, title = "Vitamin D", description = "<!-- vicu-routine:v1:e30 -->", done = true, projectId = 7),
            TaskEntity(id = 5, title = "Call mum", description = "<p>about the milk</p>", projectId = 7),
        ),
    )

    private fun harness() = TaskRepositoryHarness(taskDao = dao) { serviceUnavailable() }

    @Test
    fun `the typed text becomes an escaped pattern`() = runTest {
        val h = harness()

        h.repository.searchTasks("100%").first()
        h.repository.searchTasks("a_b").first()
        h.repository.searchTasks("c:\\temp").first()

        assertEquals(listOf("%100\\%%", "%a\\_b%", "%c:\\\\temp%"), dao.searchPatterns)
    }

    @Test
    fun `a percent sign matches itself and not everything`() = runTest {
        val h = harness()

        assertEquals(listOf(1L), h.repository.searchTasks("100%").first().map { it.id })
    }

    @Test
    fun `title and description match, completed tasks are included, open ones first`() = runTest {
        val h = harness()

        assertEquals(listOf(5L, 3L), h.repository.searchTasks("milk").first().map { it.id })
    }

    @Test
    fun `sync metadata tasks never match`() = runTest {
        val h = harness()

        assertEquals(emptyList(), h.repository.searchTasks("vitamin").first())
    }

    @Test
    fun `a blank search is empty and does not touch the database`() = runTest {
        val h = harness()

        assertEquals(emptyList(), h.repository.searchTasks("   ").first())
        assertEquals(emptyList(), dao.searchPatterns)
    }

    @Test
    fun `surrounding spaces are not part of the search`() = runTest {
        val h = harness()

        h.repository.searchTasks("  milk ").first()

        assertEquals(listOf("%milk%"), dao.searchPatterns)
    }
}
