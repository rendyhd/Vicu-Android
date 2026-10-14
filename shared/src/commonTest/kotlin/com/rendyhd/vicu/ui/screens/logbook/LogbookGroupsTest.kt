package com.rendyhd.vicu.ui.screens.logbook

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.DateDisplayFormat
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class LogbookGroupsTest {

    private val zone = TimeZone.UTC
    private val today = LocalDate(2026, 10, 7)
    private val gb24 = DateDisplayFormat(Locale.UK, hour12 = false)

    private fun done(id: Long, at: String) = Task(id = id, title = "t$id", done = true, doneAt = at)

    @Test
    fun `tasks are grouped by completion day with the day and the time phrased by the date rules`() {
        val groups = groupLogbookTasks(
            listOf(
                done(1, "2026-10-07T09:00:00Z"),
                done(2, "2026-10-07T00:10:00Z"),
                done(3, "2026-10-06T22:15:00Z"),
                done(4, "2026-10-05T10:00:00Z"),
            ),
            today, zone, gb24,
        )
        assertEquals(listOf("Today", "Yesterday", "Mon 5 Oct"), groups.map { it.title })
        assertEquals(listOf("09:00", "00:10"), groups[0].rows.map { it.time })
        assertEquals(listOf(3L), groups[1].rows.map { it.task.id })
    }

    @Test
    fun `a reopened task without a completion time stays in the group it sits in`() {
        val reopened = Task(id = 9, title = "back", done = false)
        val groups = groupLogbookTasks(
            listOf(done(1, "2026-10-07T09:00:00Z"), reopened, done(2, "2026-10-06T22:15:00Z")),
            today, zone, gb24,
        )
        assertEquals(listOf("Today", "Yesterday"), groups.map { it.title })
        assertEquals(listOf(1L, 9L), groups[0].rows.map { it.task.id })
        assertEquals("", groups[0].rows[1].time)
    }

    @Test
    fun `a reopened task at the very top forms a group without a heading`() {
        val groups = groupLogbookTasks(
            listOf(Task(id = 9, title = "back"), done(1, "2026-10-07T09:00:00Z")),
            today, zone, gb24,
        )
        assertEquals(listOf("", "Today"), groups.map { it.title })
    }

    @Test
    fun `nothing completed yet gives no groups`() {
        assertEquals(emptyList(), groupLogbookTasks(emptyList(), today, zone, gb24))
    }
}
