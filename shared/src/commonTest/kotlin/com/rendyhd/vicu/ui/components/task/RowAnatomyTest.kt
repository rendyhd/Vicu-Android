package com.rendyhd.vicu.ui.components.task

import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.ui.components.section.ProjectMeta
import com.rendyhd.vicu.util.DateContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a row leaves off its meta line because the view around it already says it (the desktop `row-anatomy.ts`). */
class RowAnatomyTest {

    private val home = ProjectMeta("Home", "#3366ff")

    @Test
    fun `a view that says nothing leaves the project of a headerless group on the row`() {
        assertEquals(home, rowProject(taskProjectId = 7, view = RowView(), projectMeta = home))
    }

    @Test
    fun `no project is named inside its own project`() {
        assertNull(rowProject(taskProjectId = 7, view = RowView(projectId = 7), projectMeta = home))
    }

    @Test
    fun `another project is still named in a project view`() {
        assertEquals(home, rowProject(taskProjectId = 8, view = RowView(projectId = 7), projectMeta = home))
    }

    @Test
    fun `a row whose group has a header names no project`() {
        assertNull(rowProject(taskProjectId = 7, view = RowView(), projectMeta = null))
    }

    @Test
    fun `a tag view does not repeat its own tag`() {
        val labels = listOf(Label(id = 1, title = "errand"), Label(id = 2, title = "phone"))
        assertEquals(listOf(labels[1]), rowLabels(labels, RowView(labelId = 1)))
    }

    @Test
    fun `without a tag view every label shows`() {
        val labels = listOf(Label(id = 1, title = "errand"), Label(id = 2, title = "phone"))
        assertEquals(labels, rowLabels(labels, RowView()))
    }

    @Test
    fun `the checklist reads as one of three`() {
        assertEquals("1 of 3", checklistLabel(1, 3))
    }

    @Test
    fun `rows keep the plain date context unless a view says otherwise`() {
        assertEquals(DateContext.ROW, RowView().dateContext)
        assertEquals(DateContext.ROW_IN_TODAY, RowView(dateContext = DateContext.ROW_IN_TODAY).dateContext)
    }
}
