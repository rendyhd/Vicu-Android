package com.rendyhd.vicu.ui.components.task

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a screen reader and a colour-blind user get from a task row. */
class TaskRowSemanticsTest {

    @Test
    fun `an open task can be completed and scheduled for today or tomorrow`() {
        assertEquals(
            listOf(TaskRowAction.COMPLETE, TaskRowAction.DUE_TODAY, TaskRowAction.DUE_TOMORROW),
            taskRowActions(done = false, canSchedule = true),
        )
    }

    @Test
    fun `a done task can only be reopened`() {
        assertEquals(listOf(TaskRowAction.REOPEN), taskRowActions(done = true, canSchedule = true))
    }

    @Test
    fun `without a scheduler only the completion action is offered`() {
        assertEquals(listOf(TaskRowAction.COMPLETE), taskRowActions(done = false, canSchedule = false))
    }

    @Test
    fun `the action labels say what they do`() {
        assertEquals("Complete", TaskRowAction.COMPLETE.label)
        assertEquals("Mark as not done", TaskRowAction.REOPEN.label)
        assertEquals("Due today", TaskRowAction.DUE_TODAY.label)
        assertEquals("Due tomorrow", TaskRowAction.DUE_TOMORROW.label)
    }

    @Test
    fun `priority is text, one mark per level, and nothing for no priority`() {
        assertNull(priorityMarkText(0))
        assertEquals("!", priorityMarkText(1))
        assertEquals("!!!", priorityMarkText(3))
        assertEquals("!!!!", priorityMarkText(4))
        assertEquals("!!!!!", priorityMarkText(5), "Vikunja's 'do now' is not invisible")
        assertNull(priorityMarkText(6))
        assertNull(priorityMarkText(-1))
    }

    @Test
    fun `every priority that is marked is also described`() {
        for (priority in 0..7) {
            assertEquals(priorityMarkText(priority) == null, priorityDescription(priority) == null, "priority $priority")
        }
        assertEquals("Urgent priority", priorityDescription(4))
        assertEquals("Do now priority", priorityDescription(5))
    }

    @Test
    fun `priority colours differ between levels on both surfaces`() {
        for (dark in listOf(true, false)) {
            val colours = (1..4).map { priorityMarkColor(it, dark) }
            assertEquals(4, colours.toSet().size, "dark=$dark")
            assertNotEquals(null, priorityMarkColor(5, dark))
        }
        assertNull(priorityMarkColor(0, dark = false))
    }

    @Test
    fun `a tapped target is at least 48 dp`() {
        assertTrue(MIN_TOUCH_TARGET >= 48.dp)
    }
}
