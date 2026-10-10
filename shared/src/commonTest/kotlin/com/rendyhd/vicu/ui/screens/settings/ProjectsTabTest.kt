package com.rendyhd.vicu.ui.screens.settings

import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.ui.components.shared.startingParentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure parts of the Projects tab: menus, keys, summaries and the "Add subproject" preselection. */
class ProjectsTabTest {

    private val work = Project(id = 6, title = "Work", parentProjectId = 2)

    private fun menuLabels(isInbox: Boolean) = projectMenuItems(
        project = work,
        isInbox = isInbox,
        onEdit = {},
        onAddSubproject = {},
        onSetInbox = {},
        onArchive = {},
        onDelete = {},
    ).map { it.label to it.destructive }

    @Test
    fun `a project's menu offers edit, a subproject, the inbox, archive and a destructive delete`() {
        assertEquals(
            listOf(
                "Edit" to false,
                "Add subproject" to false,
                "Set as Inbox" to false,
                "Archive" to false,
                "Delete" to true,
            ),
            menuLabels(isInbox = false),
        )
    }

    @Test
    fun `the inbox's menu only edits it or adds a subproject`() {
        assertEquals(listOf("Edit" to false, "Add subproject" to false), menuLabels(isInbox = true))
    }

    @Test
    fun `the menu entries run their own action`() {
        val ran = mutableListOf<String>()
        projectMenuItems(
            project = work,
            isInbox = false,
            onEdit = { ran += "edit" },
            onAddSubproject = { ran += "sub" },
            onSetInbox = { ran += "inbox" },
            onArchive = { ran += "archive" },
            onDelete = { ran += "delete" },
        ).forEach { it.onClick() }
        assertEquals(listOf("edit", "sub", "inbox", "archive", "delete"), ran)
    }

    @Test
    fun `add subproject starts with the project as the parent, an edit with the project's own`() {
        assertEquals(6L, startingParentId(project = null, initialParentId = 6))
        assertEquals(0L, startingParentId(project = null, initialParentId = 0))
        assertEquals(2L, startingParentId(project = work, initialParentId = 9))
    }

    @Test
    fun `only project rows can be dragged`() {
        assertEquals(6L, settingsProjectId(settingsProjectKey(6)))
        assertNull(settingsProjectId("label_6"))
        assertNull(settingsProjectId("archived_project_6"))
        assertNull(settingsProjectId(6L))
        assertNull(settingsProjectId("settings_project/x"))
    }

    @Test
    fun `the excluded row names the one project, or counts several`() {
        assertEquals("Someday", excludedFromReviewSummary(listOf(Project(1, "Someday"))))
        assertEquals("3 projects", excludedFromReviewSummary(listOf(Project(1, "a"), Project(2, "b"), Project(3, "c"))))
    }

    @Test
    fun `more options names the row`() {
        assertEquals("More options for Work", moreOptionsDescription("Work"))
        assertTrue(settingsProjectKey(6).startsWith("settings_project/"))
    }
}
