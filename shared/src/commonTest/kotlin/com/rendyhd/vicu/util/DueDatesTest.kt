package com.rendyhd.vicu.util

import com.rendyhd.vicu.util.CrossAppFixture.date
import com.rendyhd.vicu.util.CrossAppFixture.local
import com.rendyhd.vicu.util.CrossAppFixture.wall
import com.rendyhd.vicu.util.CrossAppFixture.zones
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Edge cases of the due-date policy beyond the shared vectors. */
class DueDatesTest {

    private val amsterdam = TimeZone.of("Europe/Amsterdam")
    private val newYork = TimeZone.of("America/New_York")
    private val auckland = TimeZone.of("Pacific/Auckland")

    @Test
    fun `a date-only due date is local 23_59_59 with zero milliseconds`() {
        // 2026-10-06 in Amsterdam is CEST (UTC+2).
        assertEquals("2026-10-06T21:59:59Z", DueDates.dateOnlyDue(date("2026-10-06"), amsterdam).toString())
        // New York is EDT (UTC-4): the UTC date is already the next day.
        assertEquals("2026-10-07T03:59:59Z", DueDates.dateOnlyDue(date("2026-10-06"), newYork).toString())
        // Auckland is NZDT (UTC+13).
        assertEquals("2026-10-06T10:59:59Z", DueDates.dateOnlyDue(date("2026-10-06"), auckland).toString())
    }

    @Test
    fun `date-only is still local 23_59_59 on a DST change day`() {
        // Europe ends DST on 2026-10-25, a 25-hour day.
        val due = DueDates.dateOnlyDue(date("2026-10-25"), amsterdam)
        assertEquals("2026-10-25T23:59:59", wall(due, amsterdam))
        assertEquals("2026-10-25T22:59:59Z", due.toString())
    }

    @Test
    fun `milliseconds do not matter when detecting a date-only value`() {
        val base = local("2026-10-06T23:59:59", amsterdam)
        val withMillis = Instant.fromEpochMilliseconds(base.toEpochMilliseconds() + 500)
        assertTrue(DueDates.isDateOnly(withMillis, amsterdam))
        val legacy = local("2026-10-06T00:00:00", amsterdam)
        assertTrue(DueDates.isDateOnly(Instant.fromEpochMilliseconds(legacy.toEpochMilliseconds() + 250), amsterdam))
    }

    @Test
    fun `an explicit time next to the date-only times is not date-only`() {
        for (zone in zones) {
            assertFalse(DueDates.isDateOnly(local("2026-10-06T23:59:00", zone), zone))
            assertFalse(DueDates.isDateOnly(local("2026-10-06T00:00:01", zone), zone))
            assertFalse(DueDates.isDateOnly(local("2026-10-06T12:00:00", zone), zone))
        }
    }

    @Test
    fun `date-only is judged in the given zone, not UTC`() {
        // 21:59:59Z is 23:59:59 in Amsterdam but 17:59:59 in New York.
        val instant = Instant.parse("2026-10-06T21:59:59Z")
        assertTrue(DueDates.isDateOnly(instant, amsterdam))
        assertFalse(DueDates.isDateOnly(instant, newYork))
    }

    @Test
    fun `the no-due-date strings classify as none`() {
        for (value in listOf(null, "", "   ", Constants.NULL_DATE_STRING, "not a date")) {
            assertEquals(DueDates.Bucket.NONE, DueDates.bucket(value, date("2026-10-06"), amsterdam), "'$value'")
            assertNull(DueDates.localDateOf(value, amsterdam), "'$value'")
            assertFalse(DueDates.isDateOnly(value, amsterdam), "'$value'")
        }
    }

    @Test
    fun `a task due at 08_00 is still due today at 10_00 and overdue the next day`() {
        val due = local("2026-10-06T08:00:00", amsterdam).toString()
        assertTrue(DueDates.isDueToday(due, date("2026-10-06"), amsterdam))
        assertFalse(DueDates.isOverdue(due, date("2026-10-06"), amsterdam))
        assertTrue(DueDates.isOverdue(due, date("2026-10-07"), amsterdam))
        assertFalse(DueDates.isUpcoming(due, date("2026-10-06"), amsterdam))
    }

    @Test
    fun `due strings with an offset classify by the local date`() {
        // 23:59:59+02:00 on the 6th is 21:59:59Z on the 6th.
        assertEquals(
            DueDates.Bucket.TODAY,
            DueDates.bucket("2026-10-06T23:59:59+02:00", date("2026-10-06"), amsterdam),
        )
        // The same instant is still the 6th in New York (17:59:59).
        assertEquals(
            DueDates.Bucket.TODAY,
            DueDates.bucket("2026-10-06T21:59:59Z", date("2026-10-06"), newYork),
        )
        // 03:59:59Z on the 7th is the evening of the 6th in New York.
        assertEquals(
            DueDates.Bucket.TODAY,
            DueDates.bucket("2026-10-07T03:59:59Z", date("2026-10-06"), newYork),
        )
    }

    @Test
    fun `next week is strictly after today and starts on Monday`() {
        // Mon 2026-10-12 -> Mon 2026-10-19; Sun 2026-10-11 -> Mon 2026-10-12; Sat -> Mon.
        assertEquals(date("2026-10-19"), DueDates.nextWeekStart(date("2026-10-12")))
        assertEquals(date("2026-10-12"), DueDates.nextWeekStart(date("2026-10-11")))
        assertEquals(date("2026-10-12"), DueDates.nextWeekStart(date("2026-10-10")))
        assertEquals(date("2027-01-04"), DueDates.nextWeekStart(date("2026-12-28")))
    }

    @Test
    fun `month ends handle leap years and december`() {
        assertEquals(date("2028-02-29"), DueDates.endOfMonth(date("2028-02-10")))
        assertEquals(date("2027-02-28"), DueDates.endOfMonth(date("2027-02-01")))
        assertEquals(date("2026-12-31"), DueDates.endOfMonth(date("2026-12-31")))
    }

    @Test
    fun `postponing keeps an explicit time and turns legacy midnight into date-only`() {
        val today = date("2026-10-06")
        assertEquals(
            "2026-10-09T15:30:00",
            wall(DueDates.postponeDays(local("2026-10-06T15:30:00", amsterdam), 3, today, amsterdam), amsterdam),
        )
        assertEquals(
            "2026-10-13T23:59:59",
            wall(DueDates.postponeDays(local("2026-10-06T00:00:00", amsterdam), 7, today, amsterdam), amsterdam),
        )
    }

    @Test
    fun `postponing across a DST change keeps the wall-clock time`() {
        val due = local("2026-10-24T09:30:00", amsterdam)
        val moved = DueDates.postponeDays(due, 2, date("2026-10-24"), amsterdam)
        assertEquals("2026-10-26T09:30:00", wall(moved, amsterdam))
    }

    @Test
    fun `postponing a task without a due date counts from today`() {
        val today = date("2026-10-06")
        val moved = DueDates.postponeDays(null as Instant?, 2, today, amsterdam)
        assertEquals("2026-10-08T23:59:59", wall(moved, amsterdam))
    }

    @Test
    fun `local day boundaries are local midnights converted to UTC`() {
        assertEquals("2026-10-06T22:00:00Z", DueDates.startOfDay(date("2026-10-07"), amsterdam).toString())
        assertEquals("2026-10-07T04:00:00Z", DueDates.startOfDay(date("2026-10-07"), newYork).toString())
        assertEquals("2026-10-06T11:00:00Z", DueDates.startOfDay(date("2026-10-07"), auckland).toString())
        assertEquals(
            "2026-10-06T22:00:00Z",
            DueDates.startOfTomorrow(date("2026-10-06"), amsterdam).toString(),
        )
    }

    @Test
    fun `a task due at exactly local midnight tomorrow belongs to upcoming, not today`() {
        for (zone in zones) {
            val today = date("2026-10-06")
            val due = DueDates.startOfTomorrow(today, zone)
            assertEquals(DueDates.Bucket.UPCOMING, DueDates.bucket(due, today, zone), "$zone")
            assertEquals(DueDates.localDateOf(due, zone), date("2026-10-07"))
        }
    }

    @Test
    fun `the date picker exchanges calendar dates, never instants`() {
        for (zone in zones) {
            val due = DueDates.dateOnlyDue(date("2026-10-06"), zone)
            // Pre-select: a 23:59:59 local due date opens on its own calendar day...
            val millis = DueDates.datePickerMillis(due.toString(), zone)!!
            // ...which the Material picker reads as a UTC day.
            assertEquals(date("2026-10-06"), DueDates.dateFromDatePickerMillis(millis), "pre-select in $zone")
            // Confirm: the picked day becomes that local day at 23:59:59.
            val picked = DueDates.pickDate(DueDates.dateFromDatePickerMillis(millis), zone)
            assertEquals("2026-10-06T23:59:59", wall(picked, zone), "confirm in $zone")
        }
    }

    @Test
    fun `the picker keeps the chosen day in time zones far from UTC`() {
        // Material hands back midnight UTC of the picked day.
        val pickedMillis = Instant.parse("2026-10-20T00:00:00Z").toEpochMilliseconds()
        for (zone in listOf(TimeZone.of("Pacific/Auckland"), TimeZone.of("Pacific/Kiritimati"), TimeZone.of("Pacific/Pago_Pago"))) {
            val picked = DueDates.pickDate(DueDates.dateFromDatePickerMillis(pickedMillis), zone)
            assertEquals("2026-10-20T23:59:59", wall(picked, zone), "$zone")
        }
    }

    @Test
    fun `the picker has no pre-selection without a due date`() {
        assertNull(DueDates.datePickerMillis(null, amsterdam))
        assertNull(DueDates.datePickerMillis(Constants.NULL_DATE_STRING, amsterdam))
    }

    @Test
    fun `a parsed date without a time becomes date-only and with a time keeps it to the minute`() {
        val dateOnly = DueDates.fromParsed(LocalDateTime(2026, 10, 7, 12, 0, 0, 0), hasTime = false, zone = amsterdam)
        assertEquals("2026-10-07T23:59:59", wall(dateOnly, amsterdam))

        val withTime = DueDates.fromParsed(LocalDateTime(2026, 10, 7, 15, 0, 7, 0), hasTime = true, zone = amsterdam)
        assertEquals("2026-10-07T15:00:00", wall(withTime, amsterdam))
        assertEquals(withTime, LocalDateTime(2026, 10, 7, 15, 0).toInstant(amsterdam))
    }

    @Test
    fun `an explicit 23_59 time is kept and not mistaken for date-only`() {
        val due = DueDates.fromParsed(LocalDateTime(2026, 10, 7, 23, 59), hasTime = true, zone = amsterdam)
        assertFalse(DueDates.isDateOnly(due, amsterdam))
    }

    @Test
    fun `local date of an instant is the local calendar day`() {
        val instant = Instant.parse("2026-10-07T03:59:59Z")
        assertEquals(LocalDate(2026, 10, 7), DueDates.localDateOf(instant, amsterdam))
        assertEquals(LocalDate(2026, 10, 6), DueDates.localDateOf(instant, newYork))
    }
}
