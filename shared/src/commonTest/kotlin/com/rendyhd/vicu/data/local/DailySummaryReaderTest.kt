package com.rendyhd.vicu.data.local

import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.repository.FakeTaskDao
import com.rendyhd.vicu.util.ClockDay
import com.rendyhd.vicu.util.DailySummary
import com.rendyhd.vicu.util.DueDates
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The summary counts the same buckets as the desktop app: overdue, today and tomorrow (not every
 * later task), by local calendar day. The day of the autumn change in Amsterdam (25 October 2026)
 * has 25 hours, which is where fixed 24 hour boundaries go wrong.
 */
class DailySummaryReaderTest {

    private val zone = TimeZone.of("Europe/Amsterdam")
    private val today = LocalDate(2026, 10, 25)
    private val day = ClockDay(today, zone)

    /** A date-only due date: local 23:59:59 of [date]. */
    private fun dateOnly(date: String): String = DueDates.dateOnlyDue(LocalDate.parse(date), zone).toString()

    private fun task(id: Long, dueDate: String, title: String = "Task $id", done: Boolean = false) =
        TaskEntity(id = id, title = title, dueDate = dueDate, done = done)

    private fun reader(vararg tasks: TaskEntity) = DailySummaryReader(FakeTaskDao(tasks.toList()))

    private suspend fun DailySummaryReader.all() =
        read(day, includeOverdue = true, includeDueToday = true, includeTomorrow = true)

    @Test
    fun `it counts overdue, today and tomorrow only`() = runTest {
        val content = reader(
            task(1, dateOnly("2026-10-24")), // yesterday: overdue
            task(2, dateOnly("2026-10-20")), // days ago: overdue
            task(3, dateOnly("2026-10-25")), // today (date only)
            task(4, dateOnly("2026-10-26")), // tomorrow
            task(5, dateOnly("2026-10-27")), // later: not in the summary
            task(6, dateOnly("2026-12-01")), // much later: not in the summary
        ).all()

        assertEquals(DailySummary.Counts(overdue = 2, dueToday = 1, dueTomorrow = 1), content.counts)
    }

    @Test
    fun `done tasks and tasks without a due date are not counted`() = runTest {
        val content = reader(
            task(1, dateOnly("2026-10-24"), done = true),
            task(2, ""),
            task(3, "0001-01-01T00:00:00Z"),
            task(4, dateOnly("2026-10-25")),
        ).all()

        assertEquals(DailySummary.Counts(0, 1, 0), content.counts)
    }

    @Test
    fun `a category the user switched off is not counted`() = runTest {
        val tasks = arrayOf(
            task(1, dateOnly("2026-10-24")),
            task(2, dateOnly("2026-10-25")),
            task(3, dateOnly("2026-10-26")),
        )

        assertEquals(
            DailySummary.Counts(0, 1, 1),
            reader(*tasks).read(day, includeOverdue = false, includeDueToday = true, includeTomorrow = true).counts,
        )
        assertEquals(
            DailySummary.Counts(1, 0, 0),
            reader(*tasks).read(day, includeOverdue = true, includeDueToday = false, includeTomorrow = false).counts,
        )
    }

    @Test
    fun `the local day decides, not the UTC date, on the 25 hour autumn change day`() = runTest {
        val content = reader(
            task(1, "2026-10-24T22:30:00Z"), // 00:30 CEST on the 25th: today
            task(2, "2026-10-25T22:59:59Z"), // 23:59:59 CET on the 25th: today
            task(3, "2026-10-25T23:00:00Z"), // 00:00 CET on the 26th: tomorrow, though its UTC date is the 25th
            task(4, "2026-10-26T22:59:59Z"), // 23:59:59 on the 26th: tomorrow
            task(5, "2026-10-26T23:00:00Z"), // 00:00 on the 27th: later
            task(6, "2026-10-24T21:59:59Z"), // 23:59:59 CEST on the 24th: overdue
        ).all()

        assertEquals(DailySummary.Counts(overdue = 1, dueToday = 2, dueTomorrow = 2), content.counts)
    }

    @Test
    fun `it lists up to three titles due today, earliest first, without overdue ones`() = runTest {
        val content = reader(
            task(1, "2026-10-25T09:00:00Z", title = "Late"),
            task(2, "2026-10-25T07:00:00Z", title = "Early"),
            task(3, "2026-10-25T08:00:00Z", title = "Middle"),
            task(4, "2026-10-25T10:00:00Z", title = "Fourth"),
            task(5, dateOnly("2026-10-24"), title = "Overdue one"),
        ).all()

        assertEquals(listOf("Early", "Middle", "Late"), content.dueTodayTitles)
        assertEquals(4, content.counts.dueToday)
    }

    @Test
    fun `nothing to report gives zero counts and no titles`() = runTest {
        val content = reader().all()

        assertEquals(0, content.counts.total)
        assertEquals(emptyList(), content.dueTodayTitles)
    }
}
