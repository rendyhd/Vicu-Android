package com.rendyhd.vicu.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The start-date audit (cross-app semantics 21.3): a lone start date (set in Vikunja's web app, or
 * by Pro's `starts monday`) is never a block, so it puts no task on Today, the Plan timeline or the
 * daily summary, and never moves a default reminder. In two zones, as the desktop's tests.
 */
class StartDateAuditTest {

    private val zones = listOf(TimeZone.of("Pacific/Auckland"), TimeZone.of("America/New_York"))
    private val today = LocalDate(2026, 10, 6)
    private val tomorrow = LocalDate(2026, 10, 7)

    private fun iso(local: String, zone: TimeZone): String = LocalDateTime.parse(local).toInstant(zone).toString()
    private fun dateOnly(date: LocalDate, zone: TimeZone): String = DueDates.dateOnlyDue(date, zone).toString()

    @Test
    fun aLoneStartDateIsNotOnToday() = zones.forEach { zone ->
        val start = iso("2026-10-06T00:00:00", zone)
        assertFalse(TimeBlocks.isOnToday("", start, "", today, zone))
        assertFalse(TimeBlocks.isOnToday("", start, HiddenTasks.NULL_DATE, today, zone))
        // A block starting today is.
        assertTrue(TimeBlocks.isOnToday("", iso("2026-10-06T09:00:00", zone), iso("2026-10-06T09:30:00", zone), today, zone))
    }

    @Test
    fun theDayPlannerKeepsBlocksAndDueTasksOnly() = zones.forEach { zone ->
        fun inRange(due: String, start: String, end: String, done: Boolean = false, first: LocalDate = tomorrow, last: LocalDate = tomorrow) =
            PlannerTimeline.inPlanRange(due, start, end, done, first, last, today, zone)

        val loneStart = iso("2026-10-07T00:00:00", zone)
        assertFalse(inRange("", loneStart, ""))
        assertFalse(inRange("", loneStart, HiddenTasks.NULL_DATE, done = true))
        // Due in the range, a block in the range (done ones too), overdue only while today is shown.
        assertTrue(inRange(dateOnly(tomorrow, zone), loneStart, ""))
        assertTrue(inRange(dateOnly(tomorrow, zone), iso("2026-10-07T09:00:00", zone), iso("2026-10-07T09:30:00", zone), done = true))
        assertTrue(inRange(dateOnly(LocalDate(2026, 10, 1), zone), "", "", first = today, last = tomorrow))
        assertFalse(inRange(dateOnly(LocalDate(2026, 10, 1), zone), "", ""))
        assertFalse(inRange(dateOnly(tomorrow, zone), "", "", done = true))
    }

    @Test
    fun aLoneStartDateNeverMovesTheDefaultReminder() {
        val due = "2026-10-07T10:59:59Z"
        val alone = DefaultReminder.build(due, -1, "due_date", blockStart = "2026-10-06T11:00:00Z", blockEnd = "")
        assertEquals("due_date", alone?.relativeTo)
        assertEquals(due, alone?.reminder)
        val nullEnd = DefaultReminder.build(due, -1, "due_date", blockStart = "2026-10-06T11:00:00Z", blockEnd = HiddenTasks.NULL_DATE)
        assertEquals("due_date", nullEnd?.relativeTo)
        // A block does.
        val block = DefaultReminder.build(due, -1, "due_date", blockStart = "2026-10-06T20:00:00Z", blockEnd = "2026-10-06T20:30:00Z")
        assertEquals("start_date", block?.relativeTo)
    }
}
