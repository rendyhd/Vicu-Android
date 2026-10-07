package com.rendyhd.vicu.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A time source that moves with the test scheduler's virtual time, so waiting for midnight in a
 * test and the clock reading midnight are the same thing. [jump] models the device clock being
 * changed (or the device sleeping through the timer) without virtual time passing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulerTimeSource(
    private val scheduler: TestCoroutineScheduler,
    start: Instant,
    var zone: TimeZone,
) : TimeSource {
    private var offsetMs = 0L
    private val startMs = start.toEpochMilliseconds()

    fun jump(by: kotlin.time.Duration) {
        offsetMs += by.inWholeMilliseconds
    }

    override fun now(): Instant = Instant.fromEpochMilliseconds(startMs + scheduler.currentTime + offsetMs)
    override fun zone(): TimeZone = zone
}

@OptIn(ExperimentalCoroutinesApi::class)
class DayClockTest {
    private val utc = TimeZone.UTC

    private fun TestScope.clock(
        start: String,
        zone: TimeZone = utc,
    ): Pair<DayClock, SchedulerTimeSource> {
        val time = SchedulerTimeSource(testScheduler, Instant.parse(start), zone)
        return DayClock(backgroundScope, time) to time
    }

    private fun TestScope.collectDates(clock: DayClock): MutableList<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        backgroundScope.launch { clock.today.collect { dates += it } }
        runCurrent()
        return dates
    }

    @Test
    fun `starts on the current local day`() = runTest {
        val (clock, _) = clock("2026-10-06T21:30:00Z", zone = TimeZone.of("Europe/Amsterdam"))

        // 21:30Z is 23:30 in Amsterdam (CEST): still the 6th there.
        assertEquals(LocalDate(2026, 10, 6), clock.day.value.date)
    }

    @Test
    fun `switches to the next day just after local midnight`() = runTest {
        val (clock, _) = clock("2026-10-06T23:30:00Z")
        val dates = collectDates(clock)
        assertEquals(listOf(LocalDate(2026, 10, 6)), dates)

        advanceTimeBy(30.minutes - 1.milliseconds)
        runCurrent()
        assertEquals(1, dates.size, "midnight has not passed yet")

        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(listOf(LocalDate(2026, 10, 6), LocalDate(2026, 10, 7)), dates)
    }

    @Test
    fun `keeps ticking over several midnights`() = runTest {
        val (clock, _) = clock("2026-10-06T12:00:00Z")
        val dates = collectDates(clock)

        advanceTimeBy(61.hours)
        runCurrent()

        assertEquals(
            listOf(LocalDate(2026, 10, 6), LocalDate(2026, 10, 7), LocalDate(2026, 10, 8), LocalDate(2026, 10, 9)),
            dates,
        )
    }

    @Test
    fun `a refresh picks up a date the timer slept through`() = runTest {
        val (clock, time) = clock("2026-10-06T10:00:00Z")
        val dates = collectDates(clock)

        // Deep sleep: the device clock reached the next morning but the timer has not fired.
        time.jump(20.hours)
        assertEquals(1, dates.size)
        clock.refresh()
        runCurrent()

        assertEquals(LocalDate(2026, 10, 7), dates.last())
    }

    @Test
    fun `a refresh restarts the midnight timer from the new time`() = runTest {
        val (clock, time) = clock("2026-10-06T10:00:00Z")
        val dates = collectDates(clock)

        time.jump(20.hours) // now 06:00 on the 7th
        clock.refresh()
        runCurrent()
        assertEquals(2, dates.size)

        // The old timer would have fired at the end of the 6th; the next change is the end of the 7th.
        advanceTimeBy(5.hours)
        runCurrent()
        assertEquals(2, dates.size)
        advanceTimeBy(19.hours)
        runCurrent()
        assertEquals(LocalDate(2026, 10, 8), dates.last())
    }

    @Test
    fun `a refresh within the same day emits nothing`() = runTest {
        val (clock, _) = clock("2026-10-06T10:00:00Z")
        val dates = collectDates(clock)

        clock.refresh()
        clock.refresh()
        runCurrent()

        assertEquals(1, dates.size)
    }

    @Test
    fun `a time zone change moves the date and the zone`() = runTest {
        val (clock, time) = clock("2026-10-06T23:00:00Z", zone = TimeZone.of("America/New_York"))
        val dates = collectDates(clock)
        assertEquals(LocalDate(2026, 10, 6), clock.day.value.date)

        time.zone = TimeZone.of("Pacific/Auckland") // 23:00Z is noon on the 7th there
        clock.refresh()
        runCurrent()

        assertEquals(LocalDate(2026, 10, 7), clock.day.value.date)
        assertEquals(TimeZone.of("Pacific/Auckland"), clock.day.value.zone)
        assertEquals(LocalDate(2026, 10, 7), dates.last())
    }

    @Test
    fun `a zone change that keeps the date still changes the day state`() = runTest {
        val (clock, time) = clock("2026-10-06T12:00:00Z", zone = TimeZone.UTC)
        val days = mutableListOf<ClockDay>()
        backgroundScope.launch { clock.day.collect { days += it } }
        runCurrent()

        time.zone = TimeZone.of("Europe/Amsterdam")
        clock.refresh()
        runCurrent()

        // Same calendar date, but "end of today" moved by two hours: followers must re-query.
        assertEquals(2, days.size)
        assertEquals(days[0].date, days[1].date)
    }

    @Test
    fun `a clock that is not ticking never moves on its own`() = runTest {
        val time = SchedulerTimeSource(testScheduler, Instant.parse("2026-10-06T23:59:00Z"), utc)
        val clock = DayClock(backgroundScope, time, ticking = false)
        val dates = collectDates(clock)

        advanceTimeBy(3.hours)
        runCurrent()
        assertEquals(1, dates.size)

        clock.refresh()
        runCurrent()
        assertEquals(LocalDate(2026, 10, 7), dates.last())
    }
}
