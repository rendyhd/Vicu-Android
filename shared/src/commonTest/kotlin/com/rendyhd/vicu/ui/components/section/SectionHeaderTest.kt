package com.rendyhd.vicu.ui.components.section

import com.rendyhd.vicu.domain.model.Task
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a screen reader hears for a section header, and what a header counts. */
class SectionHeaderTest {

    private fun task(id: Long, done: Boolean = false) = Task(id = id, title = "T$id", done = done)

    @Test
    fun `a header is announced by its name and the open tasks it holds`() {
        assertEquals("Work, 4 tasks", sectionDescription("Work", 4))
        assertEquals("Work, 1 task", sectionDescription("Work", 1))
    }

    @Test
    fun `an empty or uncounted header is announced by its name alone`() {
        assertEquals("Work", sectionDescription("Work", 0))
        assertEquals("Completed", sectionDescription("Completed", null))
    }

    @Test
    fun `the state is expanded or collapsed`() {
        assertEquals("Expanded", sectionStateDescription(true))
        assertEquals("Collapsed", sectionStateDescription(false))
    }

    @Test
    fun `the count is the open tasks and drops when one is completed`() {
        val group = listOf(task(1), task(2), task(3))
        assertEquals(3, openCount(group))
        // A row completed on this screen stays in the list for a moment; it no longer counts.
        assertEquals(2, openCount(group, completedIds = setOf(2L)))
        assertEquals(2, openCount(listOf(task(1), task(2), task(3, done = true))))
    }

    @Test
    fun `a group of one task gets no header, however many of its tasks are finished`() {
        assertFalse(showsGroupHeader(0))
        assertFalse(showsGroupHeader(1))
        assertTrue(showsGroupHeader(2))
        // The size includes finished tasks, so completing one of two never removes the header.
        val group = listOf(task(1), task(2))
        assertTrue(showsGroupHeader(group.size))
        assertEquals(1, openCount(group, completedIds = setOf(1L)))
    }
}
