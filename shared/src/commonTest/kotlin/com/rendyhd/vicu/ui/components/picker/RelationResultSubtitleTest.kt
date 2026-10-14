package com.rendyhd.vicu.ui.components.picker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The line under a task in the relation picker, which tells same-named tasks apart. */
class RelationResultSubtitleTest {

    @Test
    fun `an open task shows its project`() {
        assertEquals("Work", relationResultSubtitle(projectTitle = "Work", done = false))
    }

    @Test
    fun `a completed task says so`() {
        assertEquals("Work · Done", relationResultSubtitle("Work", done = true))
        assertEquals("Done", relationResultSubtitle(projectTitle = null, done = true))
    }

    @Test
    fun `a task in an unknown project and still open has no second line`() {
        assertNull(relationResultSubtitle(projectTitle = null, done = false))
        assertNull(relationResultSubtitle(projectTitle = "  ", done = false))
    }
}
