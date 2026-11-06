package com.rendyhd.vicu.ui.screens.plan

import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.RoutineDay
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.GestureRange
import com.rendyhd.vicu.util.PlacedBlock
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
    val plannedMinutes: Int,
    val freeMinutes: Int,
)

/** One day of the 7-day strip. [load] is how full its work hours are, 0 to 1, for the bar under it. */
data class PlanStripDay(val day: LocalDate, val plannedMinutes: Int, val load: Float)

/** A day from the tasks of its range: the same reading on Today's Day view and the Plan screen. */
fun planDayContent(
    tasks: List<Task>,
    day: LocalDate,
    now: Instant,
    hours: PlannerHours,
    zone: TimeZone,
    isHeld: (Task) -> Boolean = { false },
): PlanDayContent = PlanDayContent(
    day = day,
    blocks = PlannerTimeline.dayBlocks(tasks, day, zone),
    markers = PlannerTimeline.dayMarkers(tasks, day, zone),
    withoutTime = PlannerTimeline.dayWithoutTime(tasks, day, now.toLocalDateTime(zone).date, zone, isHeld)
        .withoutNestedSubtasks(),
    plannedMinutes = PlannerTimeline.plannedMinutes(tasks, day, zone),
    freeMinutes = TimeBlocks.freeMinutes(now, day, hours, TimeBlocks.busyOfDay(tasks, day, zone), zone),
)

/** The seven days from [start] with their load: open blocks against the work hours. */
fun planStrip(start: LocalDate, tasks: List<Task>, hours: PlannerHours, zone: TimeZone): List<PlanStripDay> {
    val work = (TimeBlocks.clockMinutes(hours.end) - TimeBlocks.clockMinutes(hours.start)).coerceAtLeast(1)
    return List(PLAN_STRIP_DAYS) { offset ->
        val day = start.plus(offset, DateTimeUnit.DAY)
        val planned = PlannerTimeline.plannedMinutes(tasks, day, zone)
        PlanStripDay(day, planned, (planned.toFloat() / work).coerceIn(0f, 1f))
    }
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
