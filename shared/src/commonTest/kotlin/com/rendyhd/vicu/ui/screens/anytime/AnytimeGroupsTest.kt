package com.rendyhd.vicu.ui.screens.anytime

import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The grouping behind the Anytime screen: every project at any depth, collapsed by project id. */
class AnytimeGroupsTest {

    private fun project(id: Long, title: String = "P$id", parent: Long = 0) =
        Project(id = id, title = title, parentProjectId = parent)

    private fun task(id: Long, projectId: Long) = Task(id = id, title = "Task $id", projectId = projectId)

    @Test
    fun `tasks in a project four levels deep are listed under their ancestors`() {
        val projects = listOf(
            project(1, "Root"),
            project(2, "Level one", parent = 1),
            project(3, "Level two", parent = 2),
            project(4, "Level three", parent = 3),
        )

        val groups = buildAnytimeGroups(projects, listOf(task(10, 4)), collapsedProjectIds = emptySet())

        assertEquals(listOf(1L), groups.map { it.project.id })
        val one = groups.single().sections.single()
        val two = one.children.single()
        val three = two.children.single()
        assertEquals(listOf(2L, 3L, 4L), listOf(one, two, three).map { it.project.id })
        assertEquals(listOf(10L), three.tasks.map { it.id })
        assertTrue(groups.single().unsectionedTasks.isEmpty())
    }

    @Test
    fun `branches without tasks are left out`() {
        val projects = listOf(
            project(1, "Root"),
            project(2, "Empty branch", parent = 1),
            project(3, "Empty leaf", parent = 2),
            project(4, "Busy branch", parent = 1),
            project(5, "Unrelated"),
        )

        val groups = buildAnytimeGroups(projects, listOf(task(10, 4)), emptySet())

        assertEquals(listOf(1L), groups.map { it.project.id })
        assertEquals(listOf(4L), groups.single().sections.map { it.project.id })
    }

    @Test
    fun `groups and sections are ordered by title regardless of the order they arrive in`() {
        val projects = listOf(
            project(1, "zebra"),
            project(2, "Apple"),
            project(3, "b-child", parent = 2),
            project(4, "A-child", parent = 2),
        )
        val tasks = listOf(task(1, 1), task(2, 3), task(3, 4))

        val groups = buildAnytimeGroups(projects, tasks, emptySet())

        assertEquals(listOf("Apple", "zebra"), groups.map { it.project.title })
        assertEquals(listOf("A-child", "b-child"), groups.first().sections.map { it.project.title })
    }

    @Test
    fun `a project whose parent is not known becomes a group of its own`() {
        val groups = buildAnytimeGroups(
            projects = listOf(project(5, "Orphan", parent = 999)),
            tasks = listOf(task(1, 5)),
            collapsedProjectIds = emptySet(),
        )

        assertEquals(listOf(5L), groups.map { it.project.id })
    }

    @Test
    fun `cyclic parent data neither loops nor hides the tasks`() {
        val projects = listOf(project(1, "A", parent = 2), project(2, "B", parent = 1))

        val groups = buildAnytimeGroups(projects, listOf(task(10, 1), task(11, 2)), emptySet())

        val shown = flattenAnytimeGroups(groups).filterIsInstance<AnytimeRow.TaskRow>().map { it.task.id }
        assertEquals(setOf(10L, 11L), shown.toSet())
    }

    @Test
    fun `tasks of a project the app does not know are not shown`() {
        val groups = buildAnytimeGroups(listOf(project(1)), listOf(task(10, 77)), emptySet())

        assertTrue(groups.isEmpty())
    }

    @Test
    fun `a collapsed project is found by its id at any depth`() {
        val projects = listOf(project(1), project(2, parent = 1), project(3, parent = 2))
        val tasks = listOf(task(10, 3))

        val groups = buildAnytimeGroups(projects, tasks, collapsedProjectIds = setOf(2L))

        val root = groups.single()
        val two = root.sections.single()
        assertTrue(root.isExpanded)
        assertTrue(!two.isExpanded)
        assertTrue(two.children.single().isExpanded)
    }

    @Test
    fun `rows list headers and tasks depth first with their indentation`() {
        val projects = listOf(project(1, "Root"), project(2, "Child", parent = 1), project(3, "Grandchild", parent = 2))
        val tasks = listOf(task(10, 1), task(11, 2), task(12, 3))

        val rows = flattenAnytimeGroups(buildAnytimeGroups(projects, tasks, emptySet()))

        val summary = rows.map {
            when (it) {
                is AnytimeRow.Header -> "H${it.project.id}@${it.depth}"
                is AnytimeRow.TaskRow -> "T${it.task.id}@${it.depth}"
            }
        }
        // The grandchild holds a single task, so it gets no header (its project goes on the row).
        assertEquals(listOf("H1@0", "T10@0", "H2@1", "T11@1", "T12@2"), summary)
        assertEquals("Grandchild", assertIs<AnytimeRow.TaskRow>(rows.last()).projectMeta?.title)
        assertEquals(null, assertIs<AnytimeRow.TaskRow>(rows[1]).projectMeta)
    }

    @Test
    fun `a project with one task has no header and names itself on the row`() {
        val rows = flattenAnytimeGroups(buildAnytimeGroups(listOf(project(1, "Solo")), listOf(task(10, 1)), emptySet()))

        val row = assertIs<AnytimeRow.TaskRow>(rows.single())
        assertEquals("Solo", row.projectMeta?.title)
    }

    @Test
    fun `a header counts open tasks only, not the ones completed on screen`() {
        val projects = listOf(project(1, "Root"))
        val tasks = listOf(task(10, 1), task(11, 1), task(12, 1))

        val rows = flattenAnytimeGroups(buildAnytimeGroups(projects, tasks, emptySet()), completedIds = setOf(11L))

        assertEquals(2, assertIs<AnytimeRow.Header>(rows.first()).taskCount)
        // The completed row stays in place: the group still has its header.
        assertEquals(4, rows.size)
    }

    @Test
    fun `a collapsed project hides everything below it but keeps its task count`() {
        val projects = listOf(project(1, "Root"), project(2, "Child", parent = 1))
        val tasks = listOf(task(10, 1), task(11, 2), task(12, 2))

        val rows = flattenAnytimeGroups(buildAnytimeGroups(projects, tasks, collapsedProjectIds = setOf(1L)))

        val header = assertIs<AnytimeRow.Header>(rows.single())
        assertEquals(3, header.taskCount)
        assertTrue(!header.isExpanded)
    }

    @Test
    fun `row keys are unique`() {
        val projects = listOf(project(1), project(2, parent = 1))
        val tasks = listOf(task(1, 1), task(2, 2))

        val keys = flattenAnytimeGroups(buildAnytimeGroups(projects, tasks, emptySet())).map { it.key }

        assertEquals(keys.size, keys.toSet().size)
    }
}
