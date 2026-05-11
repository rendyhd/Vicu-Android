package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskSortTest {

    private fun t(id: Long, dueDate: String = "", position: Double = 0.0): Task =
        Task(id = id, title = "t$id", dueDate = dueDate, position = position)

    @Test
    fun `empty input returns empty`() {
        assertEquals(emptyList<Task>(), sortProjectTasks(emptyList()))
    }

    @Test
    fun `all undated preserves position order`() {
        val input = listOf(
            t(1, position = 30.0),
            t(2, position = 10.0),
            t(3, position = 20.0),
        )
        val sorted = sortProjectTasks(input)
        assertEquals(listOf(2L, 3L, 1L), sorted.map { it.id })
    }

    @Test
    fun `dated tasks come before undated regardless of position`() {
        val input = listOf(
            t(1, position = 100.0),                    // undated
            t(2, dueDate = "2026-05-12T00:00:00Z"),    // dated
            t(3, position = 5.0),                      // undated
        )
        val sorted = sortProjectTasks(input)
        assertEquals(listOf(2L, 3L, 1L), sorted.map { it.id })
    }

    @Test
    fun `dated tasks sort by due date ascending`() {
        val input = listOf(
            t(1, dueDate = "2026-05-15T00:00:00Z"),
            t(2, dueDate = "2026-05-10T00:00:00Z"),
            t(3, dueDate = "2026-05-12T00:00:00Z"),
        )
        val sorted = sortProjectTasks(input)
        assertEquals(listOf(2L, 3L, 1L), sorted.map { it.id })
    }

    @Test
    fun `null-date sentinel is treated as undated`() {
        val input = listOf(
            t(1, dueDate = "0001-01-01T00:00:00Z", position = 50.0),
            t(2, dueDate = "2026-05-10T00:00:00Z"),
            t(3, dueDate = "", position = 10.0),
        )
        val sorted = sortProjectTasks(input)
        // 2 is the only really dated task, then 3 (position 10) and 1 (position 50)
        assertEquals(listOf(2L, 3L, 1L), sorted.map { it.id })
    }

    @Test
    fun `ties in due date fall back to position`() {
        val input = listOf(
            t(1, dueDate = "2026-05-10T00:00:00Z", position = 20.0),
            t(2, dueDate = "2026-05-10T00:00:00Z", position = 5.0),
        )
        val sorted = sortProjectTasks(input)
        assertEquals(listOf(2L, 1L), sorted.map { it.id })
    }
}
