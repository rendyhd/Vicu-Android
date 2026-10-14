package com.rendyhd.vicu.ui.components.picker

import com.rendyhd.vicu.domain.model.Project
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectPickerRowsTest {

    private val inbox = Project(id = 1, title = "Inbox", position = 5.0)
    private val work = Project(id = 2, title = "Work", position = 1.0)
    private val sprint = Project(id = 3, title = "Sprint", parentProjectId = 2, position = 1.0)
    private val home = Project(id = 4, title = "Home", position = 2.0)
    private val homeSprint = Project(id = 5, title = "Sprint", parentProjectId = 4, position = 1.0)
    private val old = Project(id = 6, title = "Old work", isArchived = true)

    private val all = listOf(home, inbox, work, sprint, homeSprint, old)

    private fun ids(rows: List<PickerProjectRow>) = rows.map { it.project.id }

    @Test
    fun `without a query the projects are a tree, the inbox first, archived ones left out`() {
        val rows = projectPickerRows(all, inboxProjectId = 1, query = "")
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids(rows))
        assertEquals(listOf(0, 0, 1, 0, 1), rows.map { it.depth })
        assertTrue(rows.all { it.parentTitle == null }, "the tree itself shows where a project sits")
    }

    @Test
    fun `a query lists the matches flat, each with the project it sits in`() {
        val rows = projectPickerRows(all, inboxProjectId = 1, query = "sprint")
        assertEquals(listOf(3L, 5L), ids(rows))
        assertEquals(listOf(0, 0), rows.map { it.depth })
        assertEquals(listOf("Work", "Home"), rows.map { it.parentTitle }, "two Sprints can be told apart")
    }

    @Test
    fun `a query ignores case and surrounding blanks and never offers an archived project`() {
        assertEquals(listOf(2L), ids(projectPickerRows(all, 1, "  WORK ")))
        assertTrue(projectPickerRows(all, 1, "old").isEmpty())
    }

    @Test
    fun `a match whose parent is archived or missing has no parent line`() {
        val child = Project(id = 7, title = "Child", parentProjectId = 6)
        val rows = projectPickerRows(all + child, 1, "child")
        assertEquals(listOf(7L), ids(rows))
        assertEquals(null, rows.single().parentTitle)
    }
}
