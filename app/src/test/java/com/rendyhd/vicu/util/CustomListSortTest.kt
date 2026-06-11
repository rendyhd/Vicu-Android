package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomListSortTest {

    private fun task(id: Long, due: String = "", priority: Int = 0, title: String = "t", updated: String = "") =
        Task(id = id, title = title, dueDate = due, priority = priority, updated = updated)

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
