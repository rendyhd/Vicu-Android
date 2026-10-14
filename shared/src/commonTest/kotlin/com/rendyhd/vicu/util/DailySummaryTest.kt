package com.rendyhd.vicu.util

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/** The next-occurrence rule of the daily summary, and what its notification says. */
class DailySummaryTest {

    private val amsterdam = TimeZone.of("Europe/Amsterdam")

    private fun next(after: String, hour: Int, minute: Int = 0, zone: TimeZone = amsterdam): Instant =
        DailySummary.nextOccurrence(Instant.parse(after), zone, hour, minute)

    // --- next occurrence -------------------------------------------------------------------

    @Test
    fun `before the time today the next occurrence is today`() {
        // 07:00 local (CEST) -> 08:00 local the same day
        assertEquals(Instant.parse("2026-10-06T06:00:00Z"), next("2026-10-06T05:00:00Z", 8))
    }

    @Test
    fun `at or after the time today the next occurrence is tomorrow, never the same instant again`() {
        assertEquals(Instant.parse("2026-10-07T06:00:00Z"), next("2026-10-06T06:00:00Z", 8))
        assertEquals(Instant.parse("2026-10-07T06:00:00Z"), next("2026-10-06T06:00:01Z", 8))
    }

    @Test
    fun `a summary scheduled across the autumn change stays at the same local time (25 hour day)`() {
        // Sat 24 Oct 08:00:01 CEST (UTC+2) -> Sun 25 Oct 08:00 CET (UTC+1) is 25 hours later.
        val after = Instant.parse("2026-10-24T06:00:01Z")

        val result = next("2026-10-24T06:00:01Z", 8)

        assertEquals(Instant.parse("2026-10-25T07:00:00Z"), result)
        assertEquals(25 * 60 * 60 - 1, (result - after).inWholeSeconds, "a fixed 24 hour interval would be an hour early")
    }

    @Test
    fun `after the autumn change it is 24 hours again`() {
        assertEquals(Instant.parse("2026-10-26T07:00:00Z"), next("2026-10-25T07:00:01Z", 8))
    }

    @Test
    fun `a summary scheduled across the spring change stays at the same local time (23 hour day)`() {
        // Sat 28 Mar 08:00:01 CET (UTC+1) -> Sun 29 Mar 08:00 CEST (UTC+2) is 23 hours later.
        assertEquals(Instant.parse("2026-03-29T06:00:00Z"), next("2026-03-28T07:00:01Z", 8))
    }

    @Test
    fun `a time that does not exist on the spring change day moves to just after the gap`() {
        // 02:30 does not exist on 29 Mar 2026 in Amsterdam (02:00 jumps to 03:00): it fires at 03:30.
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), next("2026-03-28T01:31:00Z", 2, 30))
    }

    @Test
    fun `a time that exists twice on the autumn change day fires once, at its first occurrence`() {
        // 02:30 happens at 00:30Z (CEST) and again at 01:30Z (CET) on 25 Oct 2026.
        val first = next("2026-10-24T20:00:00Z", 2, 30)
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), first)

        // Asked again right after it fired, the next one is the following day, not the second 02:30.
        assertEquals(Instant.parse("2026-10-26T01:30:00Z"), next("2026-10-25T00:30:01Z", 2, 30))
    }

    @Test
    fun `the time zone passed in decides the local time`() {
        val utc = TimeZone.UTC
        assertEquals(Instant.parse("2026-10-06T08:00:00Z"), next("2026-10-06T05:00:00Z", 8, zone = utc))
        assertEquals(
            Instant.parse("2026-10-06T12:00:00Z"),
            next("2026-10-06T05:00:00Z", 8, zone = TimeZone.of("America/New_York")),
        )
    }

    @Test
    fun `an out of range stored time is clamped instead of failing`() {
        // 30:99 -> 23:59 local (CEST) = 21:59Z
        assertEquals(Instant.parse("2026-10-06T21:59:00Z"), next("2026-10-06T05:00:00Z", 30, 99))
    }

    // --- boundaries and text ---------------------------------------------------------------

    @Test
    fun `the windows are local midnights, so the autumn change day is 25 hours long`() {
        val b = DailySummary.boundaries(LocalDate(2026, 10, 25), amsterdam)

        assertEquals("2026-10-24T22:00:00Z", b.startOfToday)
        assertEquals("2026-10-25T23:00:00Z", b.startOfTomorrow)
        assertEquals("2026-10-26T23:00:00Z", b.startOfDayAfterTomorrow)
    }

    @Test
    fun `the headline names tomorrow and leaves out empty categories`() {
        assertEquals("2 overdue, 3 today, 1 tomorrow", DailySummary.headline(DailySummary.Counts(2, 3, 1)))
        assertEquals("1 tomorrow", DailySummary.headline(DailySummary.Counts(0, 0, 1)))
        assertEquals("4 overdue", DailySummary.headline(DailySummary.Counts(4, 0, 0)))
        assertEquals("", DailySummary.headline(DailySummary.Counts(0, 0, 0)))
    }
}
