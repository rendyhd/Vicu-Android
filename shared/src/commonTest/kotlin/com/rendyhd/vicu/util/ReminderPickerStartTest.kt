package com.rendyhd.vicu.util

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/** Where the reminder date and time pickers open: on the reminder being edited, else soon. */
class ReminderPickerStartTest {

    private val amsterdam = TimeZone.of("Europe/Amsterdam")
    private val newYork = TimeZone.of("America/New_York")

    /** The Material date picker names a day by the UTC midnight of it. */
    private fun millisOf(date: String): Long = Instant.parse("${date}T00:00:00Z").toEpochMilliseconds()

    @Test
    fun `editing opens on the date and time of the reminder in the local zone`() {
        // 21:30Z is 23:30 in Amsterdam (CEST) on the same day.
        val start = ReminderPickerStart.of(
            existing = "2026-10-06T21:30:00Z",
            now = Instant.parse("2026-10-01T08:00:00Z"),
            zone = amsterdam,
        )
        assertEquals(millisOf("2026-10-06"), start.dateMillis)
        assertEquals(23, start.hour)
        assertEquals(30, start.minute)
    }

    @Test
    fun `a reminder after local midnight opens on its local day, not the UTC day`() {
        // 22:30Z on the 6th is 00:30 on the 7th in Amsterdam.
        val start = ReminderPickerStart.of("2026-10-06T22:30:00Z", Instant.parse("2026-10-01T08:00:00Z"), amsterdam)
        assertEquals(millisOf("2026-10-07"), start.dateMillis)
        assertEquals(0, start.hour)
        assertEquals(30, start.minute)
        // And the picker hands the same day back: it reports UTC midnight of what is shown.
        assertEquals(LocalDate.parse("2026-10-07"), DueDates.dateFromDatePickerMillis(start.dateMillis))
    }

    @Test
    fun `a new reminder opens on the next whole hour`() {
        val start = ReminderPickerStart.of(
            existing = null,
            now = Instant.parse("2026-10-06T12:35:00Z"), // 14:35 in Amsterdam
            zone = amsterdam,
        )
        assertEquals(millisOf("2026-10-06"), start.dateMillis)
        assertEquals(15, start.hour)
        assertEquals(0, start.minute)
    }

    @Test
    fun `a new reminder late in the evening opens on tomorrow, not on a time already past`() {
        val start = ReminderPickerStart.of(
            existing = "",
            now = Instant.parse("2026-10-06T21:40:00Z"), // 23:40 in Amsterdam
            zone = amsterdam,
        )
        assertEquals(millisOf("2026-10-07"), start.dateMillis, "00:00 today would be in the past")
        assertEquals(0, start.hour)
        assertEquals(0, start.minute)
    }

    @Test
    fun `the null date and unreadable text count as no reminder`() {
        val now = Instant.parse("2026-10-06T09:10:00Z") // 05:10 in New York
        val expected = ReminderPickerStart.of(null, now, newYork)
        assertEquals(millisOf("2026-10-06"), expected.dateMillis)
        assertEquals(6, expected.hour)
        assertEquals(expected, ReminderPickerStart.of("0001-01-01T00:00:00Z", now, newYork))
        assertEquals(expected, ReminderPickerStart.of("not a date", now, newYork))
    }
}
