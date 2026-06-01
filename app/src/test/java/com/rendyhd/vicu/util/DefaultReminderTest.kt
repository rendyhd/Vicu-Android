package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.TaskReminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultReminderTest {
    @Test
    fun `offset zero returns null`() {
        assertNull(DefaultReminder.build("2026-06-01T09:00:00Z", 0, "due_date"))
    }

    @Test
    fun `null or sentinel due returns null`() {
        assertNull(DefaultReminder.build(null, 3600, "due_date"))
        assertNull(DefaultReminder.build("", 3600, "due_date"))
        assertNull(DefaultReminder.build("0001-01-01T00:00:00Z", 3600, "due_date"))
    }

    @Test
    fun `at due time uses zero relative period`() {
        val r: TaskReminder? = DefaultReminder.build("2026-06-01T09:00:00Z", -1, "due_date")
        assertEquals("2026-06-01T09:00:00Z", r?.reminder)
        assertEquals(0L, r?.relativePeriod)
        assertEquals("due_date", r?.relativeTo)
    }

    @Test
    fun `one hour before subtracts and negates`() {
        val r: TaskReminder? = DefaultReminder.build("2026-06-01T09:00:00Z", 3600, "due_date")
        assertEquals(-3600L, r?.relativePeriod)
        // reminder instant is one hour earlier
        assertEquals("2026-06-01T08:00:00Z", r?.reminder)
    }
}
