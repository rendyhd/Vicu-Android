package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class ReorderLogicTest {

    private fun t(id: Long, dueDate: String = "", position: Double = 0.0): Task =
        Task(id = id, title = "t$id", dueDate = dueDate, position = position)

    // ─── moveTaskInList ───────────────────────────────────────

    @Test
    fun `moves undated task down the list`() {
        val list = listOf(t(1), t(2), t(3))
        val moved = moveTaskInList(list, fromId = 1, toId = 3)
        assertEquals(3, moved!!.size)
        assertEquals(listOf(2L, 3L, 1L), moved.map { it.id })
    }

    @Test
    fun `moves undated task up the list`() {
        val list = listOf(t(1), t(2), t(3))
        val moved = moveTaskInList(list, fromId = 3, toId = 1)
        assertEquals(3, moved!!.size)
        assertEquals(listOf(3L, 1L, 2L), moved.map { it.id })
    }

    @Test
    fun `vetoes moving a dated task`() {
        val list = listOf(t(1, dueDate = "2026-06-12T00:00:00Z"), t(2), t(3))
        assertNull(moveTaskInList(list, fromId = 1, toId = 3))
    }

    @Test
    fun `vetoes moving onto a dated task`() {
        val list = listOf(t(1, dueDate = "2026-06-12T00:00:00Z"), t(2), t(3))
        assertNull(moveTaskInList(list, fromId = 3, toId = 1))
    }

    @Test
    fun `vetoes ids missing from the list`() {
        val list = listOf(t(1), t(2))
        assertNull(moveTaskInList(list, fromId = 1, toId = 99))
        assertNull(moveTaskInList(list, fromId = 99, toId = 1))
    }

    @Test
    fun `null-date sentinel counts as undated`() {
        val list = listOf(t(1, dueDate = "0001-01-01T00:00:00Z"), t(2))
        val moved = moveTaskInList(list, fromId = 1, toId = 2)
        assertEquals(listOf(2L, 1L), moved!!.map { it.id })
    }
}
