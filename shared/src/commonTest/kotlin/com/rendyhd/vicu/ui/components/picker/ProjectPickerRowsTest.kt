package com.rendyhd.vicu.ui.components.picker

import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.util.ProjectCategories
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    fun `without a query the projects are a tree - the inbox first - archived ones left out`() {
        val rows = projectPickerRows(all, inboxProjectId = 1, query = "")
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids(rows))
        assertEquals(listOf(0, 0, 1, 0, 1), rows.map { it.depth })
        assertTrue(rows.all { it.parentTitle == null }, "the tree itself shows where a project sits")
    }

    @Test
    fun `projects that share a position are listed by title`() {
        val zoo = Project(id = 7, title = "Zoo", position = 1.0)
        val apple = Project(id = 8, title = "Apple", position = 1.0)
        assertEquals(listOf(1L, 8L, 7L), ids(projectPickerRows(listOf(inbox, zoo, apple), inboxProjectId = 1, query = "")))
    }

    @Test
    fun `a project whose parent is archived comes after the top level ones - with its own children below it`() {
        val surfaced = Project(id = 7, title = "Surfaced", parentProjectId = 6, position = 0.5)
        val below = Project(id = 8, title = "Below", parentProjectId = 7, position = 0.1)
        val rows = projectPickerRows(all + surfaced + below, inboxProjectId = 1, query = "")
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 7L, 8L), ids(rows))
        assertEquals(listOf(0, 0, 1, 0, 1, 0, 1), rows.map { it.depth })
    }

    @Test
    fun `with categories the inbox comes first - then the projects without one - then each category under its heading`() {
        val categories = ProjectCategories.state(
            mapOf(
                "category.c" to buildJsonObject { put("name", "Chores"); put("order", 1) },
                "category.e" to buildJsonObject { put("name", "Empty"); put("order", 2) },
                "category.project.2" to JsonPrimitive("c"),
            ),
        )
        val rows = projectPickerRows(all, inboxProjectId = 1, query = "", categories = categories)
        assertEquals(listOf(1L, 4L, 5L, 2L, 3L), ids(rows))
        assertEquals(listOf(null, null, null, "Chores", null), rows.map { it.categoryHeader?.name }, "an empty category has nothing to pick")
        assertTrue(projectPickerRows(all, 1, "sprint", categories).all { it.categoryHeader == null }, "a search stays flat")
    }

    @Test
    fun `a query lists the matches flat - each with the project it sits in`() {
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
