package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The one-step "Move up" and "Move down" a screen reader offers in place of a drag. */
class ReorderMoveTest {

    private fun task(id: Long, due: String = "") = Task(id = id, title = "T$id", dueDate = due)

    private val dated = "2026-10-08T21:59:59Z"

    @Test
    fun `a task moves into the slot of its neighbour above or below`() {
        val tasks = listOf(task(1), task(2), task(3))

        assertEquals(1L, neighbourForMove(tasks, taskId = 2, offset = -1))
        assertEquals(3L, neighbourForMove(tasks, taskId = 2, offset = 1))
        assertEquals(listOf(2L, 1L, 3L), moveTaskInList(tasks, 2, neighbourForMove(tasks, 2, -1)!!)!!.map { it.id })
    }

    @Test
    fun `the first task cannot move up and the last cannot move down`() {
        val tasks = listOf(task(1), task(2), task(3))

        assertNull(neighbourForMove(tasks, 1, -1))
        assertNull(neighbourForMove(tasks, 3, 1))
    }

    @Test
    fun `a task that is not listed cannot move`() {
        assertNull(neighbourForMove(listOf(task(1), task(2)), 99, -1))
        assertNull(neighbourForMove(emptyList(), 1, 1))
    }

    @Test
    fun `a dated task does not move, and nothing moves past it`() {
        // Dated tasks are listed by due date: a move across them would snap back.
        val tasks = listOf(task(1, due = dated), task(2), task(3))

        assertNull(neighbourForMove(tasks, 1, 1), "the dated task itself")
        assertNull(neighbourForMove(tasks, 2, -1), "up into the dated block")
        assertEquals(3L, neighbourForMove(tasks, 2, 1))
    }

    @Test
    fun `the options of every row agree with what a move would do`() {
        val tasks = listOf(task(1, due = dated), task(2), task(3), task(4), task(5, due = dated))

        val options = moveOptions(tasks)

        assertEquals(tasks.map { it.id }.toSet(), options.keys)
        for (t in tasks) {
            assertEquals(neighbourForMove(tasks, t.id, -1) != null, options.getValue(t.id).up, "up of ${t.id}")
            assertEquals(neighbourForMove(tasks, t.id, 1) != null, options.getValue(t.id).down, "down of ${t.id}")
        }
        assertEquals(MoveOptions(up = false, down = true), options[2L])
        assertEquals(MoveOptions(up = true, down = true), options[3L])
        assertEquals(MoveOptions(up = true, down = false), options[4L])
    }

    @Test
    fun `an id moves one place in a list of ids`() {
        val ids = listOf(10L, 20L, 30L)

        assertEquals(listOf(20L, 10L, 30L), moveIdBy(ids, 20L, -1))
        assertEquals(listOf(10L, 30L, 20L), moveIdBy(ids, 20L, 1))
        assertNull(moveIdBy(ids, 10L, -1))
        assertNull(moveIdBy(ids, 30L, 1))
        assertNull(moveIdBy(ids, 99L, 1))
    }
}
