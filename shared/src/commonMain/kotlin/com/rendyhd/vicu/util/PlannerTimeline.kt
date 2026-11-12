package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task
import kotlin.math.roundToInt
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** A block on a day's grid, in minutes from local midnight. */
data class PlacedBlock(
    val task: Task,
    val block: TimeBlock,
    val startMinute: Int,
    val endMinute: Int,
    /** How tall it is drawn: its length, or a little more when it is short and the room is free. */
    val drawnMinutes: Int = endMinute - startMinute,
    /** Which side-by-side column it takes where blocks overlap, and how many there are. */
    val column: Int = 0,
    val columnCount: Int = 1,
)

/** A calendar event on a day's grid, clipped to that day (contract 20.7). */
data class PlacedEvent(
    val event: CalendarEvent,
    val startMinute: Int,
    val endMinute: Int,
    val drawnMinutes: Int = endMinute - startMinute,
    val column: Int = 0,
    val columnCount: Int = 1,
)

/** A day's blocks and timed events in one overlap layout. */
data class DayLayout(val blocks: List<PlacedBlock>, val events: List<PlacedEvent>)

/** An open task with a time-of-day due date and no block (section 16.7). */
data class TimelineMarker(val task: Task, val minute: Int)

/** A stretch on the grid, in minutes from local midnight. */
data class GestureRange(val startMinute: Int, val endMinute: Int)

/**
 * The Plan screen's timeline: where things sit on the wall-clock grid and what a gesture means. Pure,
 * the same as the desktop's `src/renderer/lib/planner-timeline.ts`; the clock is passed in.
 */
object PlannerTimeline {
    const val DAY_MINUTES = 24 * 60

    /** A short block is drawn this tall so its title can be read, when the next block leaves the room. */
    const val MIN_ITEM_MINUTES = 22

    // --- What a day shows ----------------------------------------------------------------------

    /** Greedy interval partitioning; a connected group of overlaps shares its largest column count. */
    fun layoutOverlaps(items: List<PlacedBlock>): List<PlacedBlock> {
        val sorted = items.sortedWith(
            compareBy<PlacedBlock> { it.startMinute }.thenByDescending { it.endMinute }.thenBy { it.task.id },
        )
        val result = mutableListOf<PlacedBlock>()
        var group = mutableListOf<PlacedBlock>()
        var columnEnds = mutableListOf<Int>()
        var groupEnd = -1
        fun flush() {
            result += group.map { it.copy(columnCount = columnEnds.size) }
            group = mutableListOf()
            columnEnds = mutableListOf()
        }
        for (item in sorted) {
            if (item.startMinute >= groupEnd) flush()
            var column = columnEnds.indexOfFirst { it <= item.startMinute }
            if (column == -1) {
                column = columnEnds.size
                columnEnds += item.endMinute
            } else {
                columnEnds[column] = item.endMinute
            }
            group += item.copy(column = column)
            groupEnd = columnEnds.max()
        }
        flush()
        return result
    }

    private fun endMinuteOf(block: TimeBlock, zone: TimeZone): Int {
        val nextMidnight = block.day.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
        return if (block.end == nextMidnight) DAY_MINUTES else TimeBlocks.minutesOfDay(block.end, zone)
    }

    /** The blocks that start on [day], laid out side by side where they overlap. */
    fun dayBlocks(tasks: List<Task>, day: LocalDate, zone: TimeZone): List<PlacedBlock> {
        val placed = tasks.mapNotNull { task ->
            val block = TimeBlocks.blockOf(task, zone)?.takeIf { it.day == day } ?: return@mapNotNull null
            PlacedBlock(task, block, TimeBlocks.minutesOfDay(block.start, zone), endMinuteOf(block, zone))
        }
        // Columns go by the real times, so a block that starts where a short one ends sits under it.
        return layoutOverlaps(placed).map { item ->
            val next = placed.filter { it.startMinute >= item.endMinute }.minOfOrNull { it.startMinute } ?: DAY_MINUTES
            val room = next - item.startMinute
            item.copy(drawnMinutes = maxOf(item.endMinute - item.startMinute, minOf(MIN_ITEM_MINUTES, room)))
        }
    }

    /**
     * Blocks and calendar events share one overlap layout, so neither hides the other (contract
     * 20.7). Events take the left columns where they tie: they were there first, a block is what you
     * place around them. An event is drawn at least [MIN_ITEM_MINUTES] tall, so its title fits.
     */
    fun layoutWithEvents(blocks: List<PlacedBlock>, events: List<TimedEventOn>): DayLayout {
        if (events.isEmpty()) return DayLayout(blocks, emptyList())
        data class Item(val start: Int, val end: Int, val order: Int, val block: PlacedBlock?, val event: TimedEventOn?)
        val items = events.mapIndexed { i, e -> Item(e.startMinute, e.endMinute, i, null, e) } +
            blocks.mapIndexed { i, b -> Item(b.startMinute, b.endMinute, events.size + i, b, null) }
        val sorted = items.sortedWith(compareBy<Item> { it.start }.thenByDescending { it.end }.thenBy { it.order })
        val outBlocks = mutableListOf<PlacedBlock>()
        val outEvents = mutableListOf<PlacedEvent>()
        var group = mutableListOf<Pair<Item, Int>>()
        var columnEnds = mutableListOf<Int>()
        var groupEnd = -1
        fun flush() {
            for ((item, column) in group) {
                if (item.block != null) {
                    outBlocks += item.block.copy(column = column, columnCount = columnEnds.size)
                } else if (item.event != null) {
                    val length = item.end - item.start
                    outEvents += PlacedEvent(
                        event = item.event.event,
                        startMinute = item.start,
                        endMinute = item.end,
                        drawnMinutes = maxOf(length, minOf(MIN_ITEM_MINUTES, DAY_MINUTES - item.start)),
                        column = column,
                        columnCount = columnEnds.size,
                    )
                }
            }
            group = mutableListOf()
            columnEnds = mutableListOf()
        }
        for (item in sorted) {
            if (item.start >= groupEnd) flush()
            var column = columnEnds.indexOfFirst { it <= item.start }
            if (column == -1) {
                column = columnEnds.size
                columnEnds += item.end
            } else {
                columnEnds[column] = item.end
            }
            group += item to column
            groupEnd = columnEnds.max()
        }
        flush()
        return DayLayout(outBlocks, outEvents)
    }

    /** An open task with a time-of-day due date on [day] and no block: a marker at that time (section 16.7). */
    fun dayMarkers(tasks: List<Task>, day: LocalDate, zone: TimeZone): List<TimelineMarker> =
        tasks.mapNotNull { task ->
            if (task.done || TimeBlocks.blockOf(task, zone) != null) return@mapNotNull null
            val due = DateUtils.parseIsoDate(task.dueDate) ?: return@mapNotNull null
            if (DueDates.isDateOnly(due, zone) || DueDates.localDateOf(due, zone) != day) return@mapNotNull null
            TimelineMarker(task, TimeBlocks.minutesOfDay(due, zone))
        }.sortedWith(compareBy({ it.minute }, { it.task.id }))

    /** How far a marker reaches above and below its time. */
    private const val MARKER_REACH_MINUTES = 10

    /**
     * Whether a due-time marker lies over a block of its day. It then keeps to the column's right edge,
     * above the block: a block's title is at its left, and neither may hide the other.
     */
    fun markerOverBlock(minute: Int, blocks: List<PlacedBlock>): Boolean = blocks.any {
        minute + MARKER_REACH_MINUTES > it.startMinute && minute - MARKER_REACH_MINUTES < it.startMinute + it.drawnMinutes
    }

    /**
     * What is on the selected day without a time. Overdue tasks count when the day is today, and a
     * task with a due time is on the timeline already. [isHeld] keeps a task the completion hold is
     * still showing.
     */
    fun dayWithoutTime(
        tasks: List<Task>,
        day: LocalDate,
        today: LocalDate,
        zone: TimeZone,
        isHeld: (Task) -> Boolean = { false },
    ): List<Task> = tasks.filter { task ->
        if (task.done && !isHeld(task)) return@filter false
        val dueDay = DueDates.localDateOf(task.dueDate, zone) ?: return@filter false
        if (TimeBlocks.blockOf(task, zone)?.day == day) return@filter false
        if (dueDay == day) DueDates.isDateOnly(task.dueDate, zone) else day == today && dueDay < today
    }

    /**
     * A day's tasks without a time, the row above its timeline (contract 16.4): due that day, date
     * only, and no block on that day. Overdue tasks are not due that day and are left out. [isHeld]
     * keeps a task the completion hold is still showing.
     */
    fun dayTopRow(
        tasks: List<Task>,
        day: LocalDate,
        zone: TimeZone,
        isHeld: (Task) -> Boolean = { false },
    ): List<Task> = tasks.filter { task ->
        if (task.done && !isHeld(task)) return@filter false
        if (DueDates.localDateOf(task.dueDate, zone) != day) return@filter false
        DueDates.isDateOnly(task.dueDate, zone) && TimeBlocks.blockOf(task, zone)?.day != day
    }

    /** Minutes of open blocks that day, with overlaps counted once each (the header's "45m planned"). */
    fun plannedMinutes(tasks: List<Task>, day: LocalDate, zone: TimeZone): Int = tasks.sumOf { task ->
        if (task.done) 0 else TimeBlocks.blockOf(task, zone)?.takeIf { it.day == day }?.minutes ?: 0
    }

    /** "45m", "1h", "4h 20m". */
    fun formatLength(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "${m}m"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }

    // --- The request ---------------------------------------------------------------------------

    /**
     * One server filter for a view: a block starting in the range (done ones too, they stay on the
     * timeline), an open task due in it, and overdue tasks when today is shown.
     */
    fun planRangeFilter(days: List<LocalDate>, today: LocalDate, zone: TimeZone): String {
        val from = days.first().atStartOfDayIn(zone)
        val to = days.last().plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
        val due = "(due_date >= '$from' && due_date < '$to')"
        val overdue = if (today in days) {
            " || (due_date != '${Constants.NULL_DATE_STRING}' && due_date < '${today.atStartOfDayIn(zone)}')"
        } else {
            ""
        }
        return "(start_date >= '$from' && start_date < '$to') || (done = false && ($due$overdue))"
    }

    /**
     * Whether a row the plan query returned belongs to the days [firstDay] to [lastDay]: the query
     * is a superset, because a lone start date (contract 21.3) passes its `startDate` clause. A row
     * belongs when it is a block starting in the range, or open and due in it (or overdue while
     * [today] is shown). The desktop's `inPlanRange`.
     */
    fun inPlanRange(
        due: String?,
        start: String?,
        end: String?,
        done: Boolean,
        firstDay: LocalDate,
        lastDay: LocalDate,
        today: LocalDate,
        zone: TimeZone,
    ): Boolean {
        if (lastDay < firstDay) return false
        TimeBlocks.blockOf(start, end, zone)?.let { block -> if (block.day in firstDay..lastDay) return true }
        if (done) return false
        val dueAt = DateUtils.parseIsoDate(due) ?: return false
        val from = firstDay.atStartOfDayIn(zone)
        val to = lastDay.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
        if (dueAt >= from && dueAt < to) return true
        return today in firstDay..lastDay && dueAt < today.atStartOfDayIn(zone)
    }

    // --- Gestures ------------------------------------------------------------------------------

    private fun clamp(value: Int, low: Int, high: Int): Int = maxOf(low, minOf(high, value))

    /** The nearest 15-minute line. */
    fun snapMinutes(minutes: Double): Int =
        (minutes / TimeBlocks.STEP_MINUTES).roundToInt() * TimeBlocks.STEP_MINUTES

    /** Move a stretch by the finger's travel: it lands on a 15-minute line and stays inside the day. */
    fun movedRange(range: GestureRange, deltaMinutes: Double): GestureRange {
        val length = range.endMinute - range.startMinute
        val start = clamp(snapMinutes(range.startMinute + deltaMinutes), 0, DAY_MINUTES - length)
        return GestureRange(start, start + length)
    }

    /** The bottom edge to [minute]: at least one step, at most 12 hours, no later than the midnight. */
    fun endEdgeRange(range: GestureRange, minute: Double): GestureRange {
        val high = minOf(DAY_MINUTES, range.startMinute + TimeBlocks.MAX_BLOCK_MINUTES)
        return range.copy(endMinute = clamp(snapMinutes(minute), range.startMinute + TimeBlocks.STEP_MINUTES, high))
    }

    /** The top edge to [minute]: at least one step, at most 12 hours. The end stays. */
    fun startEdgeRange(range: GestureRange, minute: Double): GestureRange {
        val low = maxOf(0, range.endMinute - TimeBlocks.MAX_BLOCK_MINUTES)
        return range.copy(startMinute = clamp(snapMinutes(minute), low, range.endMinute - TimeBlocks.STEP_MINUTES))
    }

    /** A block cannot be placed on a past day (section 16.2). */
    fun canPlaceOn(day: LocalDate, today: LocalDate): Boolean = day >= today

    /** Where the scroller opens: an hour before now on today, the work hours' start on another day. */
    fun openingMinute(day: LocalDate, now: Instant, today: LocalDate, workStart: Int, zone: TimeZone): Int =
        if (day == today) maxOf(0, TimeBlocks.minutesOfDay(now, zone) - 60) else workStart

    /** The local date [now] falls on. */
    fun dayOf(now: Instant, zone: TimeZone): LocalDate = now.toLocalDateTime(zone).date
}
