package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomListSortTest {

    private fun task(
        id: Long,
        due: String = "",
        priority: Int = 0,
        title: String = "t",
        updated: String = "",
        doneAt: String = "",
        position: Double = 0.0,
    ) = Task(id = id, title = title, dueDate = due, priority = priority, updated = updated, doneAt = doneAt, position = position)

    private val noDate = "0001-01-01T00:00:00Z"

    @Test
    fun `due_date asc puts null dates last`() {
        val tasks = listOf(
            task(1, due = "0001-01-01T00:00:00Z"),
            task(2, due = "2026-06-12T00:00:00Z"),
            task(3, due = "2026-06-10T00:00:00Z"),
        )
        val sorted = CustomListFilterBuilder.sortTasks(tasks, "due_date", "asc")
        assertEquals(listOf(3L, 2L, 1L), sorted.map { it.id })
    }

    @Test
    fun `due_date desc puts the latest first and null dates still last`() {
        val tasks = listOf(
            task(1, due = noDate),
            task(2, due = "2026-06-12T00:00:00Z"),
            task(3, due = "2026-06-10T00:00:00Z"),
            task(4, due = ""),
        )
        val sorted = CustomListFilterBuilder.sortTasks(tasks, "due_date", "desc")
        assertEquals(listOf(2L, 3L, 1L, 4L), sorted.map { it.id })
    }

    @Test
    fun `due dates compare as instants, not as strings`() {
        val tasks = listOf(
            task(1, due = "2026-06-10T22:00:00.500Z"),
            task(2, due = "2026-06-10T22:00:00Z"),
            task(3, due = "2026-06-10T23:59:59+01:00"),
        )
        // 22:00:00Z, 22:00:00.5Z, 22:59:59Z
        assertEquals(listOf(2L, 1L, 3L), CustomListFilterBuilder.sortTasks(tasks, "due_date", "asc").map { it.id })
        assertEquals(listOf(3L, 1L, 2L), CustomListFilterBuilder.sortTasks(tasks, "due_date", "desc").map { it.id })
    }

    @Test
    fun `done_at puts tasks that are not done last in both directions`() {
        val tasks = listOf(
            task(1, doneAt = noDate),
            task(2, doneAt = "2026-06-12T08:00:00Z"),
            task(3, doneAt = "2026-06-10T08:00:00Z"),
        )
        assertEquals(listOf(3L, 2L, 1L), CustomListFilterBuilder.sortTasks(tasks, "done_at", "asc").map { it.id })
        assertEquals(listOf(2L, 3L, 1L), CustomListFilterBuilder.sortTasks(tasks, "done_at", "desc").map { it.id })
    }

    @Test
    fun `position sorts both ways`() {
        val tasks = listOf(task(1, position = 30.0), task(2, position = 10.0), task(3, position = 20.0))
        assertEquals(listOf(2L, 3L, 1L), CustomListFilterBuilder.sortTasks(tasks, "position", "asc").map { it.id })
        assertEquals(listOf(1L, 3L, 2L), CustomListFilterBuilder.sortTasks(tasks, "position", "desc").map { it.id })
    }

    @Test
    fun `title desc is case-insensitive`() {
        val tasks = listOf(task(1, title = "banana"), task(2, title = "Apple"), task(3, title = "cherry"))
        val sorted = CustomListFilterBuilder.sortTasks(tasks, "title", "desc")
        assertEquals(listOf(3L, 1L, 2L), sorted.map { it.id })
    }

    @Test
    fun `equal keys keep their order in both directions`() {
        val tasks = listOf(task(1, priority = 2), task(2, priority = 2), task(3, priority = 4))
        assertEquals(listOf(1L, 2L, 3L), CustomListFilterBuilder.sortTasks(tasks, "priority", "asc").map { it.id })
        assertEquals(listOf(3L, 1L, 2L), CustomListFilterBuilder.sortTasks(tasks, "priority", "desc").map { it.id })
    }

    @Test
    fun `priority desc puts urgent first`() {
        val tasks = listOf(task(1, priority = 1), task(2, priority = 4), task(3, priority = 0))
        val sorted = CustomListFilterBuilder.sortTasks(tasks, "priority", "desc")
        assertEquals(listOf(2L, 1L, 3L), sorted.map { it.id })
    }

    @Test
    fun `title asc is case-insensitive`() {
        val tasks = listOf(task(1, title = "banana"), task(2, title = "Apple"))
        val sorted = CustomListFilterBuilder.sortTasks(tasks, "title", "asc")
        assertEquals(listOf(2L, 1L), sorted.map { it.id })
    }

    @Test
    fun `unknown sort key falls back to updated desc`() {
        val tasks = listOf(task(1, updated = "2026-01-01T00:00:00Z"), task(2, updated = "2026-06-01T00:00:00Z"))
        val sorted = CustomListFilterBuilder.sortTasks(tasks, "bogus", "whatever")
        assertEquals(listOf(2L, 1L), sorted.map { it.id })
    }
}
