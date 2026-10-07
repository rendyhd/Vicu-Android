package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.jsonOk
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.SchedulerTimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/** Today and Upcoming re-query when the day changes, with the boundary of the new day. */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskRepositoryDayBoundaryTest {

    private val amsterdam = TimeZone.of("Europe/Amsterdam")

    @Test
    fun `today and upcoming switch to the next day's boundary at local midnight`() = runTest {
        // 21:30Z is 23:30 in Amsterdam (CEST) on 6 October.
        val time = SchedulerTimeSource(testScheduler, Instant.parse("2026-10-06T21:30:00Z"), amsterdam)
        val dao = FakeTaskDao()
        val h = TaskRepositoryHarness(taskDao = dao, dayClock = DayClock(backgroundScope, time)) { jsonOk("{}") }
        backgroundScope.launch { h.repository.getTodayTasks().collect { } }
        backgroundScope.launch { h.repository.getUpcomingTasks().collect { } }
        runCurrent()

        // The exclusive end of the 6th is local midnight: 22:00Z.
        assertEquals(listOf("2026-10-06T22:00:00Z"), dao.todayBoundaries)
        assertEquals(listOf("2026-10-06T22:00:00Z"), dao.upcomingBoundaries)

        advanceTimeBy(31.minutes)
        runCurrent()

        assertEquals(listOf("2026-10-06T22:00:00Z", "2026-10-07T22:00:00Z"), dao.todayBoundaries)
        assertEquals(listOf("2026-10-06T22:00:00Z", "2026-10-07T22:00:00Z"), dao.upcomingBoundaries)
    }

    @Test
    fun `a time zone change re-queries even when the date stays the same`() = runTest {
        val time = SchedulerTimeSource(testScheduler, Instant.parse("2026-10-06T12:00:00Z"), TimeZone.UTC)
        val clock = DayClock(backgroundScope, time)
        val dao = FakeTaskDao()
        val h = TaskRepositoryHarness(taskDao = dao, dayClock = clock) { jsonOk("{}") }
        backgroundScope.launch { h.repository.getTodayTasks().collect { } }
        runCurrent()
        assertEquals(listOf("2026-10-07T00:00:00Z"), dao.todayBoundaries)

        time.zone = amsterdam
        clock.refresh()
        runCurrent()

        assertEquals(listOf("2026-10-07T00:00:00Z", "2026-10-06T22:00:00Z"), dao.todayBoundaries)
    }
}
