package com.rendyhd.vicu.ui.screens.taskdetail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The current values the detail screen shows next to its icon buttons. */
class TaskDetailValuesTest {

    private fun values(
        due: String? = null,
        overdue: Boolean = false,
        project: String? = "Work",
        priority: Int = 0,
        recurrence: String = "",
        reminders: String = "",
    ) = taskDetailValues(due, overdue, project, priority, recurrence, reminders)

    @Test
    fun `the project is always shown, even when there is nothing else to say`() {
        assertEquals(
            listOf(DetailValue(DetailField.PROJECT, "Project", "Work")),
            values(),
        )
    }

    @Test
    fun `a task without a project says so`() {
        assertEquals("No project", values(project = null).single().text)
    }

    @Test
    fun `due date, project, priority, repeat and reminders appear in that order`() {
        val shown = values(due = "Tomorrow", priority = 3, recurrence = "Every week", reminders = "At 9:00")

        assertEquals(
            listOf(
                DetailField.DUE_DATE,
                DetailField.PROJECT,
                DetailField.PRIORITY,
                DetailField.RECURRENCE,
                DetailField.REMINDERS,
            ),
            shown.map { it.field },
        )
        assertEquals(
            listOf("Tomorrow", "Work", "High", "Every week", "At 9:00"),
            shown.map { it.text },
        )
    }

    @Test
    fun `an overdue date is flagged`() {
        val due = values(due = "Mon", overdue = true).first { it.field == DetailField.DUE_DATE }
        assertTrue(due.emphasis)
        assertEquals(false, values(due = "Mon").first { it.field == DetailField.DUE_DATE }.emphasis)
    }

    @Test
    fun `a value reads as its label and its text for a screen reader`() {
        assertEquals("Priority: Urgent", values(priority = 4).first { it.field == DetailField.PRIORITY }.description)
    }

    @Test
    fun `priority names cover the whole range and nothing else`() {
        assertNull(priorityName(0))
        assertEquals("Low", priorityName(1))
        assertEquals("Medium", priorityName(2))
        assertEquals("High", priorityName(3))
        assertEquals("Urgent", priorityName(4))
        assertEquals("Do now", priorityName(5))
        assertNull(priorityName(6))
    }

    @Test
    fun `line breaks in a title become spaces`() {
        assertEquals("Buy milk", "Buy\nmilk".withoutLineBreaks())
        assertEquals("Buy milk", "Buy\r\nmilk".withoutLineBreaks())
        assertEquals("Buy milk", "Buy\rmilk".withoutLineBreaks())
        assertEquals("one two three ", "one\ntwo\r\nthree\n".withoutLineBreaks())
        assertEquals("unchanged", "unchanged".withoutLineBreaks())
    }
}
