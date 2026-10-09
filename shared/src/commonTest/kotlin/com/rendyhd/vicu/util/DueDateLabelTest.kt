package com.rendyhd.vicu.util

import com.rendyhd.vicu.util.CrossAppFixture.date
import com.rendyhd.vicu.util.CrossAppFixture.local
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How a stored due date is shown in a task row: the relative day, the year outside the current year,
 * and the time only when it is explicit (not for 23:59:59 or the legacy 00:00:00). The exact strings
 * of every context are pinned by the `dateDisplay` vectors (DateDisplayFixtureTest); these cases
 * cover reading a stored instant.
 */
class DueDateLabelTest {

    private val zone = TimeZone.of("Europe/Amsterdam")
    private val today = date("2026-10-06") // a Tuesday
    private val us12 = DateDisplayFormat(Locale.US, hour12 = true)
    private val us24 = DateDisplayFormat(Locale.US, hour12 = false)

    private fun label(local: String, format: DateDisplayFormat = us12) =
        DateDisplay.formatDue(DateContext.ROW, local(local, zone).toString(), today, zone, format)

    @Test
    fun `a date-only value shows no time`() {
        assertEquals("Today", label("2026-10-06T23:59:59"))
        assertEquals("Tomorrow", label("2026-10-07T23:59:59"))
        assertEquals("Yesterday", label("2026-10-05T23:59:59"))
    }

    @Test
    fun `a legacy midnight value shows no time`() {
        assertEquals("Today", label("2026-10-06T00:00:00"))
        assertEquals("Oct 20", label("2026-10-20T00:00:00"))
    }

    @Test
    fun `an explicit time is shown in the device's 12 or 24 hour style`() {
        assertEquals("Today, 3:30 PM", label("2026-10-06T15:30:00", us12))
        assertEquals("Today, 15:30", label("2026-10-06T15:30:00", us24))
        assertEquals("Tomorrow, 9:00 AM", label("2026-10-07T09:00:00", us12))
        assertEquals("Tomorrow, 09:00", label("2026-10-07T09:00:00", us24))
        assertEquals("Today, 12:00 AM", label("2026-10-06T00:00:01", us12))
    }

    @Test
    fun `23_59 is a time and 12_00 is a time`() {
        assertEquals("Today, 11:59 PM", label("2026-10-06T23:59:00"))
        assertEquals("Today, 12:00 PM", label("2026-10-06T12:00:00"))
    }

    @Test
    fun `the label follows the local date and time, not UTC`() {
        // 21:59:59Z on the 6th is 23:59:59 in Amsterdam (date-only, today) ...
        val dueDate = Instant.parse("2026-10-06T21:59:59Z").toString()
        assertEquals("Today", DateDisplay.formatDue(DateContext.ROW, dueDate, today, zone, us12))
        // ... but 17:59:59 in New York: an explicit time.
        assertEquals(
            "Today, 5:59 PM",
            DateDisplay.formatDue(DateContext.ROW, dueDate, today, TimeZone.of("America/New_York"), us12),
        )
    }

    @Test
    fun `no due date gives no label`() {
        assertEquals("", DateDisplay.formatDue(DateContext.ROW, null, today, zone, us12))
        assertEquals("", DateDisplay.formatDue(DateContext.ROW, Constants.NULL_DATE_STRING, today, zone, us12))
        assertEquals("", DateDisplay.formatDue(DateContext.ROW, "", today, zone, us12))
    }

    @Test
    fun `overdue and due-today flags use the local date`() {
        val dueEarlyToday = local("2026-10-06T08:00:00", zone).toString()
        assertEquals(false, DateUtils.isOverdue(dueEarlyToday, today, zone))
        assertEquals(true, DateUtils.isToday(dueEarlyToday, today, zone))
        assertEquals(true, DateUtils.isOverdue(dueEarlyToday, date("2026-10-07"), zone))
    }
}
