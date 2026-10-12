package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskOrder
import com.rendyhd.vicu.domain.model.TaskOrderSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The task orders a project can be given, which rows each lets the user drag, and how a project's order is resolved. */
class TaskOrderSortTest {

    private fun t(
        id: Long,
        title: String = "t$id",
        due: String = "",
        position: Double = 0.0,
        priority: Int = 0,
        created: String = "",
    ) = Task(id = id, title = title, dueDate = due, position = position, priority = priority, created = created)

    private val tasks = listOf(
        t(1, title = "banana", position = 30.0, priority = 1, created = "2026-01-03T10:00:00Z"),
        t(2, title = "Apple", due = "2026-05-12T21:59:59Z", position = 10.0, priority = 3, created = "2026-01-01T10:00:00Z"),
        t(3, title = "cherry", position = 20.0, priority = 3, created = "2026-01-02T10:00:00Z"),
        t(4, title = "date", due = "2026-05-10T21:59:59Z", position = 40.0, created = "2026-01-04T10:00:00Z"),
    )

    private fun ids(order: TaskOrder) = sortProjectTasks(tasks, order).map { it.id }

    @Test
    fun `the default order is dated by due date then undated by position`() {
        assertEquals(listOf(4L, 2L, 3L, 1L), ids(TaskOrder.DUE_DATE_FIRST))
        assertEquals(ids(TaskOrder.DUE_DATE_FIRST), sortProjectTasks(tasks).map { it.id })
    }

    @Test
    fun `due date then priority lists the undated ones by priority`() {
        assertEquals(listOf(4L, 2L, 3L, 1L), ids(TaskOrder.DUE_DATE_THEN_PRIORITY))
        val undated = listOf(
            t(1, position = 10.0, priority = 1),
            t(2, position = 20.0, priority = 5),
            t(3, position = 30.0),
            t(4, position = 40.0, priority = 5),
            t(5, due = "2026-05-10T21:59:59Z", position = 50.0, priority = 1),
            t(6, due = "2026-05-10T21:59:59Z", position = 60.0, priority = 4),
        )
        assertEquals(
            listOf(6L, 5L, 2L, 4L, 1L, 3L),
            sortProjectTasks(undated, TaskOrder.DUE_DATE_THEN_PRIORITY).map { it.id },
        )
    }

    @Test
    fun `manual order is position only`() {
        assertEquals(listOf(2L, 3L, 1L, 4L), ids(TaskOrder.MANUAL))
    }

    @Test
    fun `priority puts the highest first, then dated before undated`() {
        assertEquals(listOf(2L, 3L, 1L, 4L), ids(TaskOrder.PRIORITY))
    }

    @Test
    fun `title order ignores case`() {
        assertEquals(listOf(2L, 1L, 3L, 4L), ids(TaskOrder.TITLE))
    }

    @Test
    fun `created orders run both ways`() {
        assertEquals(listOf(4L, 1L, 3L, 2L), ids(TaskOrder.NEWEST))
        assertEquals(listOf(2L, 3L, 1L, 4L), ids(TaskOrder.OLDEST))
    }

    @Test
    fun `only manual orders let rows be dragged`() {
        val dated = tasks[1]
        val undated = tasks[0]
        assertTrue(isManuallyOrdered(undated, TaskOrder.DUE_DATE_FIRST))
        assertFalse(isManuallyOrdered(dated, TaskOrder.DUE_DATE_FIRST))
        assertTrue(isManuallyOrdered(dated, TaskOrder.MANUAL))
        for (order in listOf(TaskOrder.DUE_DATE_THEN_PRIORITY, TaskOrder.PRIORITY, TaskOrder.TITLE, TaskOrder.NEWEST, TaskOrder.OLDEST)) {
            assertFalse(isManuallyOrdered(undated, order), "$order")
            assertNull(moveTaskInList(sortProjectTasks(tasks, order), 1L, 3L, order), "$order")
            assertTrue(moveOptions(tasks, order).values.none { it.up || it.down }, "$order")
        }
    }

    @Test
    fun `manual order lets a dated task move among the others`() {
        val shown = sortProjectTasks(tasks, TaskOrder.MANUAL) // 2, 3, 1, 4
        val moved = assertNotNull(moveTaskInList(shown, 4L, 2L, TaskOrder.MANUAL))
        assertEquals(listOf(4L, 2L, 3L, 1L), moved.map { it.id })
        // The dated neighbour's position counts in the manual order.
        assertEquals(5.0, dropPositionFor(moved, 4L, TaskOrder.MANUAL))
        val plan = assertNotNull(planDrop(moved, 4L, TaskOrder.MANUAL))
        assertEquals(4L, plan.moved.id)
        assertEquals(5.0, plan.moved.position)
        // The default order still refuses it.
        assertNull(moveTaskInList(sortProjectTasks(tasks), 4L, 2L))
    }

    private fun project(id: Long, parent: Long = 0) = Project(id = id, title = "p$id", parentProjectId = parent)

    @Test
    fun `a project follows its own choice, then its nearest parent's, then the global one`() {
        val projects = listOf(project(1), project(2, parent = 1), project(3, parent = 2), project(4))
        val settings = TaskOrderSettings(
            global = TaskOrder.TITLE,
            projectOverrides = mapOf(1L to TaskOrder.PRIORITY, 3L to TaskOrder.MANUAL),
        )
        assertEquals(TaskOrder.PRIORITY, settings.orderFor(1L, projects))
        assertEquals(TaskOrder.PRIORITY, settings.orderFor(2L, projects))
        assertEquals(TaskOrder.MANUAL, settings.orderFor(3L, projects))
        assertEquals(TaskOrder.PRIORITY, settings.inheritedOrderFor(3L, projects))
        assertEquals(TaskOrder.TITLE, settings.orderFor(4L, projects))
        assertEquals(TaskOrder.TITLE, settings.inheritedOrderFor(1L, projects))
        // An unknown project uses the global order.
        assertEquals(TaskOrder.TITLE, settings.orderFor(99L, projects))
    }

    @Test
    fun `a cycle in the parents ends the walk`() {
        val projects = listOf(project(1, parent = 2), project(2, parent = 1))
        val settings = TaskOrderSettings(global = TaskOrder.NEWEST, projectOverrides = mapOf(5L to TaskOrder.MANUAL))
        assertEquals(TaskOrder.NEWEST, settings.orderFor(1L, projects))
    }
}
