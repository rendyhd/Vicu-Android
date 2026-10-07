package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where a dragged task gets its position, and when the others have to be renumbered (desktop's planMove). */
class ReorderPlanTest {

    private fun task(id: Long, position: Double, due: String = "") =
        Task(id = id, title = "T$id", position = position, dueDate = due)

    private val step = POSITION_STEP

    @Test
    fun `a drop between two tasks lands in the middle and moves nothing else`() {
        // task 2 was dragged between 1 and 3
        val reordered = listOf(task(1, 100.0), task(2, 0.0), task(3, 300.0))

        val plan = planDrop(reordered, taskId = 2)!!

        assertEquals(200.0, plan.moved.position)
        assertEquals(2L, plan.moved.taskId)
        assertTrue(plan.renumbered.isEmpty())
        assertEquals(listOf(plan.moved), plan.updates)
    }

    @Test
    fun `a drop at the top takes half of the first position`() {
        val plan = planDrop(listOf(task(2, 0.0), task(1, 400.0), task(3, 800.0)), taskId = 2)!!

        assertEquals(200.0, plan.moved.position)
        assertTrue(plan.renumbered.isEmpty())
    }

    @Test
    fun `a drop at the end of tied tasks needs no renumbering`() {
        val plan = planDrop(listOf(task(1, 0.0), task(3, 0.0), task(2, 0.0)), taskId = 2)!!

        assertEquals(step, plan.moved.position)
        assertTrue(plan.renumbered.isEmpty())
    }

    @Test
    fun `a drop at the end goes one step past the last task`() {
        val plan = planDrop(listOf(task(1, 100.0), task(3, 300.0), task(2, 0.0)), taskId = 2)!!

        assertEquals(300.0 + step, plan.moved.position)
        assertTrue(plan.renumbered.isEmpty())
    }

    @Test
    fun `dated neighbours do not anchor a drop`() {
        val dated = task(9, 5_000_000.0, due = "2026-10-08T21:59:59Z")
        // dated rows come first; the dragged task is the first undated one
        val plan = planDrop(listOf(dated, task(2, 0.0), task(1, 400.0)), taskId = 2)!!

        assertEquals(200.0, plan.moved.position, "half of the first undated position, not of the dated row's")
    }

    @Test
    fun `tasks that share a position are spread apart in their new order`() {
        // Every task moved into a project has position 0: the middle of two zeros tells them apart not at all.
        val reordered = listOf(task(1, 0.0), task(2, 0.0), task(3, 0.0))

        val plan = planDrop(reordered, taskId = 2)!!

        assertEquals(2 * step, plan.moved.position)
        assertEquals(listOf(1L to step, 3L to 3 * step), plan.renumbered.map { it.taskId to it.position })
        assertEquals(plan.renumbered + plan.moved, plan.updates, "the others first, the dragged task last")
    }

    @Test
    fun `renumbering only lists the tasks whose position changes`() {
        val reordered = listOf(task(1, step), task(2, 0.0), task(3, 0.5), task(4, 3 * step))

        val plan = planDrop(reordered, taskId = 2)!!

        // 3 and 4 have no room between task 1 and 3: 2 -> 2 steps, 3 -> 3 steps, 4 -> 4 steps
        assertEquals(2 * step, plan.moved.position)
        assertEquals(listOf(3L to 3 * step, 4L to 4 * step), plan.renumbered.map { it.taskId to it.position })
    }

    @Test
    fun `renumbering skips dated tasks`() {
        val dated = task(9, 7.0, due = "2026-10-08T21:59:59Z")
        val plan = planDrop(listOf(dated, task(1, 0.0), task(2, 0.0)), taskId = 1)!!

        assertEquals(step, plan.moved.position)
        assertEquals(listOf(2L to 2 * step), plan.renumbered.map { it.taskId to it.position })
    }

    @Test
    fun `a dated or missing task cannot be dropped`() {
        assertNull(planDrop(listOf(task(1, 1.0, due = "2026-10-08T21:59:59Z")), taskId = 1))
        assertNull(planDrop(listOf(task(1, 1.0)), taskId = 99))
    }

    @Test
    fun `a lone task gets the first slot`() {
        val plan = planDrop(listOf(task(1, 0.0)), taskId = 1)!!

        assertEquals(step, plan.moved.position)
    }
}
