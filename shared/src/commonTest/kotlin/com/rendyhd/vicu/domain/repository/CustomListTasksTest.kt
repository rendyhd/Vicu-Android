package com.rendyhd.vicu.domain.repository

import com.rendyhd.vicu.domain.model.CustomListFilter
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.RelationKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The tasks of a custom list come from one place, for the screen and for the home screen widget:
 * every task in the repository (not a capped sample), done ones only when the list shows them,
 * through the one evaluator (docs/cross-app-semantics-v1.md, sections 2 and 3).
 */
class CustomListTasksTest {

    private val zone = TimeZone.of("Europe/Amsterdam")
    private val today = LocalDate(2026, 10, 6)
    private val dueToday = DueDates.dateOnlyDue(today, zone).toString()
    private val allProjects = setOf(1L, 2L)

    private fun task(
        id: Long,
        due: String = "",
        done: Boolean = false,
        projectId: Long = 1L,
        parent: Task? = null,
    ) = Task(
        id = id,
        title = "Task $id",
        dueDate = due,
        done = done,
        projectId = projectId,
        relatedTasks = if (parent == null) emptyMap() else mapOf(RelationKind.PARENTTASK to listOf(parent)),
    )

    private suspend fun TaskRepository.shown(
        filter: CustomListFilter,
        activeProjects: Set<Long> = allProjects,
    ): List<Long> = customListTasks(filter, today, zone, activeProjects).map { it.id }

    @Test
    fun `a list reads every open task, not a capped sample`() = runTest {
        val tasks = FakeTaskRepository().apply {
            // 250 undated tasks come first, so a reader that stops at 200 never sees the due ones.
            for (id in 1L..250L) put(task(id))
            for (id in 251L..300L) put(task(id, due = dueToday))
        }

        val shown = tasks.shown(CustomListFilter(dueDateFilter = "today"))

        assertEquals((251L..300L).toList(), shown)
    }

    @Test
    fun `done tasks are read only when the list includes them`() = runTest {
        val tasks = FakeTaskRepository().apply {
            put(task(1, due = dueToday))
            put(task(2, due = dueToday, done = true))
        }

        assertEquals(listOf(1L), tasks.shown(CustomListFilter(dueDateFilter = "today", includeDone = false)))
        assertEquals(
            listOf(1L, 2L),
            tasks.shown(CustomListFilter(dueDateFilter = "today", includeDone = true)).sorted(),
        )
    }

    @Test
    fun `the source follows include_done`() = runTest {
        val tasks = FakeTaskRepository().apply {
            put(task(1))
            put(task(2, done = true))
        }

        assertEquals(listOf(1L), tasks.customListSource(CustomListFilter(includeDone = false)).first().map { it.id })
        assertEquals(listOf(1L, 2L), tasks.customListSource(CustomListFilter(includeDone = true)).first().map { it.id })
    }

    @Test
    fun `tasks of projects that are gone or archived are left out`() = runTest {
        val tasks = FakeTaskRepository().apply {
            put(task(1, projectId = 1))
            put(task(2, projectId = 99))
        }

        assertEquals(listOf(1L), tasks.shown(CustomListFilter()))
    }

    @Test
    fun `a matching subtask shows even when its parent does not match, and nests under a matching one`() = runTest {
        val parent = task(1)
        val matchingParent = task(3, due = dueToday)
        val tasks = FakeTaskRepository().apply {
            put(parent)
            put(task(2, due = dueToday, parent = parent))
            put(matchingParent)
            put(task(4, due = dueToday, parent = matchingParent))
        }

        assertEquals(listOf(2L, 3L), tasks.shown(CustomListFilter(dueDateFilter = "today")))
    }

    @Test
    fun `the list is sorted as configured, with undated tasks last`() = runTest {
        val tasks = FakeTaskRepository().apply {
            put(task(1))
            put(task(2, due = DueDates.dateOnlyDue(LocalDate(2026, 10, 8), zone).toString()))
            put(task(3, due = DueDates.dateOnlyDue(LocalDate(2026, 10, 7), zone).toString()))
        }

        assertEquals(listOf(3L, 2L, 1L), tasks.shown(CustomListFilter(sortBy = "due_date", orderBy = "asc")))
        assertEquals(listOf(2L, 3L, 1L), tasks.shown(CustomListFilter(sortBy = "due_date", orderBy = "desc")))
    }
}
