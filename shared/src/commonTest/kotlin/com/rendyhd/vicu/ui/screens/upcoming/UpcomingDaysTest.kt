package com.rendyhd.vicu.ui.screens.upcoming

import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.DueDates
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class UpcomingDaysTest {

    private val amsterdam = TimeZone.of("Europe/Amsterdam")
    private val projects = listOf(Project(id = 1, title = "Home"), Project(id = 2, title = "Work"))
    private val today = LocalDate(2026, 10, 6)

    private fun task(id: Long, due: String, position: Double = 0.0, projectId: Long = 1) =
        Task(id = id, title = "T$id", dueDate = due, position = position, projectId = projectId)

    private fun dateOnly(date: LocalDate, zone: TimeZone) = DueDates.dateOnlyDue(date, zone).toString()

    private fun ids(days: List<UpcomingDay>) = days.map { d -> d.date.toString() to d.tasks.map { it.id } }

    @Test
    fun `tasks are grouped by local day in date order and empty days are skipped`() {
        val tasks = listOf(
            task(1, dateOnly(LocalDate(2026, 10, 9), amsterdam)),
            task(2, dateOnly(LocalDate(2026, 10, 7), amsterdam)),
            task(3, dateOnly(LocalDate(2026, 10, 9), amsterdam)),
            task(4, dateOnly(LocalDate(2026, 10, 20), amsterdam)),
        )
        val days = buildUpcomingDays(tasks, projects, today, amsterdam)
        assertEquals(
            listOf("2026-10-07" to listOf(2L), "2026-10-09" to listOf(1L, 3L), "2026-10-20" to listOf(4L)),
            ids(days),
        )
    }

    @Test
    fun `within a day tasks go by due time, then position, then id`() {
        val day = LocalDate(2026, 10, 8)
        val tasks = listOf(
            task(1, dateOnly(day, amsterdam), position = 1.0),
            task(2, "2026-10-08T07:30:00Z", position = 9.0), // 09:30 local
            task(3, "2026-10-08T06:00:00Z", position = 5.0), // 08:00 local
            task(4, "2026-10-08T06:00:00Z", position = 2.0), // same time, earlier position
            task(5, dateOnly(day, amsterdam), position = 1.0), // ties task 1: lower id first
        )
        val days = buildUpcomingDays(tasks, projects, today, amsterdam)
        assertEquals(listOf(4L, 3L, 2L, 1L, 5L), days.single().tasks.map { it.id })
    }

    @Test
    fun `the day is the local day, not the UTC day, across midnight`() {
        // 23:30 local on Oct 8 is 21:30Z the same day; 00:30 local on Oct 9 is 22:30Z on Oct 8.
        val tasks = listOf(
            task(1, "2026-10-08T21:30:00Z"),
            task(2, "2026-10-08T22:30:00Z"),
        )
        val days = buildUpcomingDays(tasks, projects, today, amsterdam)
        assertEquals(listOf("2026-10-08" to listOf(1L), "2026-10-09" to listOf(2L)), ids(days))
    }

    @Test
    fun `a zone west of UTC moves a late UTC time to the earlier local day`() {
        val newYork = TimeZone.of("America/New_York")
        val tasks = listOf(task(1, "2026-10-09T02:00:00Z")) // 22:00 on Oct 8 in New York (EDT)
        val days = buildUpcomingDays(tasks, projects, today, newYork)
        assertEquals(listOf("2026-10-08" to listOf(1L)), ids(days))
    }

    @Test
    fun `days around the end of daylight saving time stay separate and ordered`() {
        // Europe/Amsterdam leaves summer time on 2026-10-25 (03:00 local becomes 02:00).
        val before = LocalDate(2026, 10, 24)
        val change = LocalDate(2026, 10, 25)
        val after = LocalDate(2026, 10, 26)
        val tasks = listOf(
            task(1, dateOnly(after, amsterdam)),
            task(2, dateOnly(change, amsterdam)),
            task(3, dateOnly(before, amsterdam)),
            // 00:30 local on the 26th is 23:30Z on the 25th, after the 24-hour-day shift.
            task(4, "2026-10-25T23:30:00Z"),
        )
        val days = buildUpcomingDays(tasks, projects, today, amsterdam)
        assertEquals(
            listOf("2026-10-24" to listOf(3L), "2026-10-25" to listOf(2L), "2026-10-26" to listOf(4L, 1L)),
            ids(days),
        )
    }

    @Test
    fun `today, overdue, undated and unknown-project tasks are not listed`() {
        val tasks = listOf(
            task(1, dateOnly(today, amsterdam)),
            task(2, dateOnly(LocalDate(2026, 10, 5), amsterdam)),
            task(3, ""),
            task(4, "0001-01-01T00:00:00Z"),
            task(5, dateOnly(LocalDate(2026, 10, 7), amsterdam), projectId = 99),
            task(6, dateOnly(LocalDate(2026, 10, 7), amsterdam), projectId = 2),
        )
        assertEquals(
            listOf("2026-10-07" to listOf(6L)),
            ids(buildUpcomingDays(tasks, projects, today, amsterdam)),
        )
    }

    @Test
    fun `the days follow the passed day`() {
        val tasks = listOf(task(1, dateOnly(LocalDate(2026, 10, 7), amsterdam)))
        assertEquals(1, buildUpcomingDays(tasks, projects, today, amsterdam).size)
        // A day later the same task is due today and leaves the list.
        assertEquals(emptyList(), buildUpcomingDays(tasks, projects, LocalDate(2026, 10, 7), amsterdam))
    }

    @Test
    fun `labels read Tomorrow, then weekday names for a week, then the full date`() {
        assertEquals("Tomorrow", upcomingDayLabel(LocalDate(2026, 10, 7), today))
        assertEquals("Thursday", upcomingDayLabel(LocalDate(2026, 10, 8), today))
        assertEquals("Monday", upcomingDayLabel(LocalDate(2026, 10, 12), today))
        assertEquals("Wednesday, October 14", upcomingDayLabel(LocalDate(2026, 10, 14), today))
        assertEquals("Friday, January 1", upcomingDayLabel(LocalDate(2027, 1, 1), today))
    }

    @Test
    fun `instants with different offsets order by the moment, not the text`() {
        val tasks = listOf(
            task(1, "2026-10-08T10:00:00+02:00"), // 08:00Z
            task(2, "2026-10-08T09:00:00Z"),
        )
        val days = buildUpcomingDays(tasks, projects, today, amsterdam)
        assertEquals(listOf(1L, 2L), days.single().tasks.map { it.id })
        assertEquals(Instant.parse("2026-10-08T08:00:00Z"), Instant.parse("2026-10-08T10:00:00+02:00"))
    }
}
