package com.rendyhd.vicu.ui.screens.plan

import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.RoutineDay
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.BusyRange
import com.rendyhd.vicu.util.CalendarEvent
import com.rendyhd.vicu.util.CalendarEvents
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.GestureRange
import com.rendyhd.vicu.util.PlacedBlock
import com.rendyhd.vicu.util.PlacedEvent
import com.rendyhd.vicu.util.PlannerHours
import com.rendyhd.vicu.util.PlannerTimeline
import com.rendyhd.vicu.util.TimeBlocks
import com.rendyhd.vicu.util.TimelineMarker
import com.rendyhd.vicu.util.withoutNestedSubtasks
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** A routine's time on the timeline: a small pill, never busy (contract 16.6). */
data class TimelineRoutine(val key: String, val minute: Int, val name: String, val done: Boolean)

/** What one day of the timeline shows. */
data class PlanDayContent(
    val day: LocalDate,
    val blocks: List<PlacedBlock>,
    val markers: List<TimelineMarker>,
    /** The day's tasks without a time (overdue ones on today), the first list of the drawer. */
    val withoutTime: List<Task>,
    /** The day's own tasks without a time, the row above its timeline (contract 16.4). */
    val topRow: List<Task> = emptyList(),
    val plannedMinutes: Int,
    val freeMinutes: Int,
    /** Timed events beside the blocks, and all-day ones for the top row (contract 20): the Plan screen only. */
    val events: List<PlacedEvent> = emptyList(),
    val allDayEvents: List<CalendarEvent> = emptyList(),
)

/**
 * One day of the 7-day strip. [load] is how full its work hours are, 0 to 1, for the bar under it:
 * planned blocks and the busy time of events (contract 20.2) that no block already covers, as
 * [eventMinutes].
 */
data class PlanStripDay(val day: LocalDate, val plannedMinutes: Int, val load: Float, val eventMinutes: Int = 0)

/**
 * A day from the tasks of its range: the same reading on Today's Day view and the Plan screen. Only
 * the Plan screen passes [events] (contract 20: never on Today); the busy ones of [counting]
 * calendars and your own Events are busy time (20.2).
 */
fun planDayContent(
    tasks: List<Task>,
    day: LocalDate,
    now: Instant,
    hours: PlannerHours,
    zone: TimeZone,
    events: List<CalendarEvent> = emptyList(),
    counting: Set<String> = emptySet(),
    isHeld: (Task) -> Boolean = { false },
): PlanDayContent {
    val layout = PlannerTimeline.layoutWithEvents(
        PlannerTimeline.dayBlocks(tasks, day, zone),
        CalendarEvents.timedEventsOn(events, day, zone),
    )
    val eventBusy = CalendarEvents.eventBusyOfDay(events, day, zone, CalendarEvents.calendarCounts(counting))
    return PlanDayContent(
        day = day,
        blocks = layout.blocks,
        markers = PlannerTimeline.dayMarkers(tasks, day, zone),
        withoutTime = PlannerTimeline.dayWithoutTime(tasks, day, now.toLocalDateTime(zone).date, zone, isHeld)
            .withoutNestedSubtasks(),
        topRow = PlannerTimeline.dayTopRow(tasks, day, zone, isHeld).withoutNestedSubtasks(),
        plannedMinutes = PlannerTimeline.plannedMinutes(tasks, day, zone),
        freeMinutes = TimeBlocks.freeMinutes(now, day, hours, TimeBlocks.busyOfDay(tasks, day, zone) + eventBusy, zone),
        events = layout.events,
        allDayEvents = CalendarEvents.allDayEventsOn(events, day, zone),
    )
}

/**
 * The seven days from [start] with their load: open blocks against the work hours, and the busy
 * events of [counting] calendars and your own Events (contract 20.2), overlaps counted once. Only
 * the Plan screen passes [events].
 */
fun planStrip(
    start: LocalDate,
    tasks: List<Task>,
    hours: PlannerHours,
    zone: TimeZone,
    events: List<CalendarEvent> = emptyList(),
    counting: Set<String> = emptySet(),
): List<PlanStripDay> {
    val work = (TimeBlocks.clockMinutes(hours.end) - TimeBlocks.clockMinutes(hours.start)).coerceAtLeast(1)
    val counts = CalendarEvents.calendarCounts(counting)
    return List(PLAN_STRIP_DAYS) { offset ->
        val day = start.plus(offset, DateTimeUnit.DAY)
        val planned = PlannerTimeline.plannedMinutes(tasks, day, zone)
        val blocks = TimeBlocks.busyOfDay(tasks, day, zone)
        val eventBusy = CalendarEvents.eventBusyOfDay(events, day, zone, counts)
        // Event time that no block covers: a block placed over a meeting is not counted twice.
        val eventMinutes = if (eventBusy.isEmpty()) 0 else (busyLength(blocks + eventBusy) - busyLength(blocks)).coerceAtLeast(0)
        PlanStripDay(day, planned, ((planned + eventMinutes).toFloat() / work).coerceIn(0f, 1f), eventMinutes)
    }
}

/** Minutes covered by [busy], overlaps counted once. */
internal fun busyLength(busy: List<BusyRange>): Int {
    var total = 0
    var end = Int.MIN_VALUE
    for (range in busy.filter { it.end > it.start }.sortedBy { it.start }) {
        val from = maxOf(range.start, end)
        if (range.end > from) total += range.end - from
        end = maxOf(end, range.end)
    }
    return total
}

const val PLAN_STRIP_DAYS = 7

/** "45m planned · 4h 20m free", the same as the desktop's totals. */
fun planTotalsText(plannedMinutes: Int, freeMinutes: Int): String =
    "${PlannerTimeline.formatLength(plannedMinutes)} planned · ${PlannerTimeline.formatLength(freeMinutes)} free"

/** A day's routines as pills at their times; a skipped one has nothing left to show. */
fun routinesOnTimeline(day: RoutineDay): List<TimelineRoutine> = day.occurrences
    .filterNot { it.status == OccurrenceStatus.SKIPPED }
    .map {
        TimelineRoutine(
            key = "${it.key}:${day.date}",
            minute = it.slot.reminderMinutes,
            name = it.routine.definition.name,
            done = it.status == OccurrenceStatus.COMPLETED,
        )
    }
    .sortedWith(compareBy({ it.minute }, { it.name }))

/** The text of a stretch on [day]'s grid, "15:00–15:30", in the user's clock. */
fun rangeText(day: LocalDate, range: GestureRange, zone: TimeZone, format: DateDisplayFormat): String =
    DateDisplay.formatBlockRange(
        TimeBlocks.atClock(day, range.startMinute, zone).toLocalDateTime(zone),
        TimeBlocks.atClock(day, range.endMinute, zone).toLocalDateTime(zone),
        format,
    )
