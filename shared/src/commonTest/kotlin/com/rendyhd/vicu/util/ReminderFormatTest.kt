package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.TaskReminder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A relative reminder is labelled with the date it counts from. "At due time" for one that counts
 * from the start or end date told the user the wrong thing (A-ALARM-3 follow-up).
 */
class ReminderFormatTest {

    private fun relative(seconds: Long, to: String) = TaskReminder(relativePeriod = seconds, relativeTo = to)

    @Test
    fun `reminders relative to the due date keep their labels`() {
        assertEquals("At due time", ReminderFormat.format(relative(0, "due_date")))
        assertEquals("5 minutes before", ReminderFormat.format(relative(-300, "due_date")))
        assertEquals("15 minutes before", ReminderFormat.format(relative(-900, "due_date")))
        assertEquals("1 hour before", ReminderFormat.format(relative(-3600, "due_date")))
        assertEquals("1 day before", ReminderFormat.format(relative(-86_400, "due_date")))
    }

    @Test
    fun `a relative reminder without a date still means the due date`() {
        assertEquals("At due time", ReminderFormat.format(relative(0, "")))
        assertEquals("1 hour before", ReminderFormat.format(relative(-3600, "")))
    }

    @Test
    fun `a reminder relative to the start date names it`() {
        assertEquals("At start", ReminderFormat.format(relative(0, "start_date")))
        assertEquals("15 minutes before start", ReminderFormat.format(relative(-900, "start_date")))
        assertEquals("1 hour after start", ReminderFormat.format(relative(3600, "start_date")))
    }

    @Test
    fun `a reminder relative to the end date names it`() {
        assertEquals("At end", ReminderFormat.format(relative(0, "end_date")))
        assertEquals("1 day before end", ReminderFormat.format(relative(-86_400, "end_date")))
        assertEquals("30 minutes after end", ReminderFormat.format(relative(1_800, "end_date")))
    }

    @Test
    fun `other offsets are spelled out in the largest whole unit`() {
        assertEquals("2 hours before", ReminderFormat.format(relative(-7_200, "due_date")))
        assertEquals("90 minutes before", ReminderFormat.format(relative(-5_400, "due_date")))
        assertEquals("2 days before", ReminderFormat.format(relative(-172_800, "due_date")))
        assertEquals("30 minutes after", ReminderFormat.format(relative(1_800, "due_date")))
        assertEquals("1 minute before", ReminderFormat.format(relative(-60, "due_date")))
    }

    @Test
    fun `a date this version does not know is shown as it is, never as the due time`() {
        assertEquals("At custom_date", ReminderFormat.format(relative(0, "custom_date")))
        assertEquals("1 hour before custom_date", ReminderFormat.format(relative(-3600, "custom_date")))
    }

    @Test
    fun `an absolute time wins over the relative fields the server keeps next to it`() {
        val text = ReminderFormat.format(TaskReminder(reminder = "2030-01-01T09:00:00Z", relativePeriod = -300, relativeTo = "start_date"))

        assertNotEquals("5 minutes before start", text)
        assertTrue("2030" in text || "2029" in text, text)
    }

    @Test
    fun `the summary of one reminder is its label and of several is a count`() {
        assertEquals("", ReminderFormat.summary(emptyList()))
        assertEquals("At start", ReminderFormat.summary(listOf(relative(0, "start_date"))))
        assertEquals("2 reminders", ReminderFormat.summary(listOf(relative(0, "start_date"), relative(0, "end_date"))))
    }
}
