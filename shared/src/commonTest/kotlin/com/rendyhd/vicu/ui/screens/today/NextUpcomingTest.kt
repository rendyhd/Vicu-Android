package com.rendyhd.vicu.ui.screens.today

import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.DueDates
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What an emptied Today offers (card 4.11b): the first task of the first upcoming day. */
class NextUpcomingTest {
    private val zone = TimeZone.of("Europe/Amsterdam")
    private val projects = listOf(Project(id = 1, title = "Home"))
    private val today = LocalDate(2026, 10, 6)

    private fun task(id: Long, due: String, position: Double = 0.0, projectId: Long = 1) =
        Task(id = id, title = "T$id", dueDate = due, position = position, projectId = projectId)

    private fun dateOnly(date: LocalDate) = DueDates.dateOnlyDue(date, zone).toString()

    @Test
    fun `it is the first task of the nearest upcoming day`() {
        val tasks = listOf(
            task(1, dateOnly(LocalDate(2026, 10, 9))),
            task(2, dateOnly(LocalDate(2026, 10, 7)), position = 2.0),
            task(3, dateOnly(LocalDate(2026, 10, 7)), position = 1.0),
        )
        assertEquals(3L, nextUpcomingTask(tasks, projects, today, zone)?.id)
    }

    @Test
    fun `nothing due today or earlier is offered`() {
        val tasks = listOf(
            task(1, dateOnly(today)),
            task(2, dateOnly(LocalDate(2026, 10, 5))),
            task(3, "2026-10-06T08:00:00Z"),
        )
        assertNull(nextUpcomingTask(tasks, projects, today, zone))
    }

    @Test
    fun `without upcoming tasks there is nothing to offer`() {
        assertNull(nextUpcomingTask(emptyList(), projects, today, zone))
    }

    @Test
    fun `tomorrow is upcoming across local midnight`() {
        // 22:30Z on Oct 6 is 00:30 on Oct 7 in Amsterdam: tomorrow, although it is still Oct 6 in UTC.
        val tasks = listOf(task(1, "2026-10-06T22:30:00Z"))
        assertEquals(1L, nextUpcomingTask(tasks, projects, today, zone)?.id)
    }
}
