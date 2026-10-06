package com.rendyhd.vicu.util

import com.rendyhd.vicu.util.CrossAppFixture.date
import com.rendyhd.vicu.util.CrossAppFixture.local
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Before
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How a due date is shown: the relative date, the year outside the current year, and the time only
 * when it is explicit (not for 23:59:59 or the legacy 00:00:00).
 */
class DueDateLabelTest {

    private val zone = TimeZone.of("Europe/Amsterdam")
    private val today = date("2026-10-06") // a Tuesday
    private var savedLocale: Locale = Locale.getDefault()

    @Before
    fun fixLocale() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    private fun label(local: String, is24Hour: Boolean = false, now: String = "2026-10-06") =
        DateUtils.formatDueDate(local(local, zone).toString(), date(now), is24Hour, zone)

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
        assertEquals("Today 3:30 PM", label("2026-10-06T15:30:00", is24Hour = false))
        assertEquals("Today 15:30", label("2026-10-06T15:30:00", is24Hour = true))
        assertEquals("Tomorrow 9:00 AM", label("2026-10-07T09:00:00", is24Hour = false))
        assertEquals("Tomorrow 09:00", label("2026-10-07T09:00:00", is24Hour = true))
    }

    @Test
    fun `23_59 is a time and 12_00 is a time`() {
        assertEquals("Today 11:59 PM", label("2026-10-06T23:59:00"))
        assertEquals("Today 12:00 PM", label("2026-10-06T12:00:00"))
    }

    @Test
    fun `a weekday within the coming week and a plain date after that`() {
        assertEquals("Fri", label("2026-10-09T23:59:59"))
        assertEquals("Fri 10:00 AM", label("2026-10-09T10:00:00"))
        assertEquals("Oct 20", label("2026-10-20T23:59:59"))
    }

    @Test
    fun `the year is shown when the date is not in the current year`() {
        assertEquals("Jan 15, 2027", label("2027-01-15T23:59:59"))
        assertEquals("Jan 15, 2027 2:00 PM", label("2027-01-15T14:00:00"))
        assertEquals("Dec 25, 2025", label("2025-12-25T23:59:59"))
        // The current year stays without one.
        assertEquals("Dec 25", label("2026-12-25T23:59:59"))
    }

    @Test
    fun `the label follows the local date and time, not UTC`() {
        // 21:59:59Z on the 6th is 23:59:59 in Amsterdam (date-only, today) ...
        val dueDate = Instant.parse("2026-10-06T21:59:59Z").toString()
        assertEquals("Today", DateUtils.formatDueDate(dueDate, today, false, zone))
        // ... but 17:59:59 in New York: an explicit time.
        assertEquals(
            "Today 5:59 PM",
            DateUtils.formatDueDate(dueDate, today, false, TimeZone.of("America/New_York")),
        )
    }

    @Test
    fun `no due date gives no label`() {
        assertEquals("", DateUtils.formatDueDate(null, today, false, zone))
        assertEquals("", DateUtils.formatDueDate(Constants.NULL_DATE_STRING, today, false, zone))
        assertEquals("", DateUtils.formatRelativeDate("", today, zone))
    }

    @Test
    fun `formatClockTime follows the 24 hour flag`() {
        assertEquals("7:05 AM", DateUtils.formatClockTime(LocalTime(7, 5), false))
        assertEquals("07:05", DateUtils.formatClockTime(LocalTime(7, 5), true))
        assertEquals("12:00 AM", DateUtils.formatClockTime(LocalTime(0, 0), false))
    }

    @Test
    fun `overdue and due-today flags use the local date`() {
        val dueEarlyToday = local("2026-10-06T08:00:00", zone).toString()
        assertEquals(false, DateUtils.isOverdue(dueEarlyToday, today, zone))
        assertEquals(true, DateUtils.isToday(dueEarlyToday, today, zone))
        assertEquals(true, DateUtils.isOverdue(dueEarlyToday, date("2026-10-07"), zone))
    }
}
