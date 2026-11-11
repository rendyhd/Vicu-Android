package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/** Blocks and events in one overlap layout (contract 20.7), the desktop's `layoutWithEvents`. */
class PlannerTimelineEventsTest {
    private val zone = TimeZone.of("Europe/Amsterdam")
    private val day = LocalDate(2026, 11, 10)

    private fun at(local: String) = LocalDateTime.parse("2026-11-10T$local").toInstant(zone)

    private fun block(id: Long, from: String, to: String): Task =
        Task(id = id, title = "Task $id", projectId = 1, startDate = at(from).toString(), endDate = at(to).toString())

    private fun event(id: String, from: String, to: String) =
        CalendarEvent(id = id, calendarId = "work", title = id, start = at(from), end = at(to), allDay = false, busy = true, tentative = false)

    private fun laid(tasks: List<Task>, events: List<CalendarEvent>): DayLayout = PlannerTimeline.layoutWithEvents(
        PlannerTimeline.dayBlocks(tasks, day, zone),
        CalendarEvents.timedEventsOn(events, day, zone),
    )

    @Test
    fun anEventTakesTheLeftColumnWhereItTiesWithABlock() {
        val layout = laid(listOf(block(1, "10:00:00", "11:00:00")), listOf(event("call", "10:00:00", "11:00:00")))
        assertEquals(listOf(0, 2), layout.events.single().let { listOf(it.column, it.columnCount) })
        assertEquals(listOf(1, 2), layout.blocks.single().let { listOf(it.column, it.columnCount) })
    }

    @Test
    fun withoutOverlapEachHasTheWholeWidthAndAShortEventIsDrawnTallerThanItsTime() {
        val layout = laid(listOf(block(1, "09:00:00", "09:30:00")), listOf(event("pin", "12:00:00", "12:05:00")))
        assertEquals(1, layout.blocks.single().columnCount)
        val pin = layout.events.single()
        assertEquals(listOf(0, 1, 720, 725), listOf(pin.column, pin.columnCount, pin.startMinute, pin.endMinute))
        assertEquals(PlannerTimeline.MIN_ITEM_MINUTES, pin.drawnMinutes)
    }

    @Test
    fun withoutEventsTheBlocksStayAsTheyWere() {
        val blocks = PlannerTimeline.dayBlocks(listOf(block(1, "09:00:00", "10:00:00"), block(2, "09:30:00", "10:30:00")), day, zone)
        assertEquals(blocks, PlannerTimeline.layoutWithEvents(blocks, emptyList()).blocks)
    }
}
