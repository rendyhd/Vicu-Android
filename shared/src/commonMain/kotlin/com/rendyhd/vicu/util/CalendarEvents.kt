package com.rendyhd.vicu.util

import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * One occurrence of a calendar event, recurrence already expanded (docs/cross-app-semantics-v1.md
 * section 20.1): an event of a phone calendar, or one of your own Events ([PlanEvents]).
 */
data class CalendarEvent(
    /** Unique per occurrence. */
    val id: String,
    val calendarId: String,
    val title: String,
    val location: String? = null,
    /** An all-day event runs from local midnight to the local midnight after its last day. */
    val start: Instant,
    val end: Instant,
    val allDay: Boolean,
    /** Opaque (busy) or transparent (free). */
    val busy: Boolean,
    val tentative: Boolean,
    /** One of your own Events: its synced id and the day of this occurrence. */
    val planEventId: String? = null,
    val day: LocalDate? = null,
)

/** A timed event on a day's grid, clipped to that day: minutes from its local midnight. */
data class TimedEventOn(val event: CalendarEvent, val startMinute: Int, val endMinute: Int)

/**
 * Calendar events on the Plan screen (contract section 20): read-only for a connected calendar, and
 * busy time (20.2). The same as the desktop's `src/shared/calendar-events.ts`. Pure: the zone is
 * passed in.
 */
object CalendarEvents {
    /** The calendar id of your own Events. */
    const val MY_EVENTS_CALENDAR_ID = "vicu:events"

    /** Default colours for calendars, in the order they were added (20.9). */
    val CALENDAR_COLORS: List<String> = listOf("#7c6cf2", "#0a84ff", "#30a46c", "#e5484d", "#f76b15", "#12a594")

    private const val DAY_MINUTES = 24 * 60

    /** Minutes from the local midnight of [day], clipped to that day, or null when it misses the day (20.1). */
    fun eventMinutesOn(start: Instant, end: Instant, day: LocalDate, zone: TimeZone): Pair<Int, Int>? {
        val dayStart = day.atStartOfDayIn(zone)
        val dayEnd = day.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
        val from = maxOf(start, dayStart)
        val to = minOf(end, dayEnd)
        if (to <= from) return null
        // Wall-clock minutes, so a day with a clock change still lines up with the grid's hour lines.
        fun toMinute(at: Instant): Int =
            if (at >= dayEnd) DAY_MINUTES else at.toLocalDateTime(zone).let { it.hour * 60 + it.minute }
        val a = toMinute(from)
        val b = toMinute(to)
        return if (b > a) a to b else null
    }

    fun eventMinutesOn(event: CalendarEvent, day: LocalDate, zone: TimeZone): Pair<Int, Int>? =
        eventMinutesOn(event.start, event.end, day, zone)

    /** The timed events that touch [day]; an all-day event is not on the grid. */
    fun timedEventsOn(events: List<CalendarEvent>, day: LocalDate, zone: TimeZone): List<TimedEventOn> =
        events.mapNotNull { event ->
            if (event.allDay) return@mapNotNull null
            val (from, to) = eventMinutesOn(event, day, zone) ?: return@mapNotNull null
            TimedEventOn(event, from, to)
        }

    /** The all-day events on [day], for the row above the timeline. */
    fun allDayEventsOn(events: List<CalendarEvent>, day: LocalDate, zone: TimeZone): List<CalendarEvent> =
        events.filter { it.allDay && eventMinutesOn(it, day, zone) != null }

    /**
     * Busy only (20.8): the title becomes "Busy", or "Free" for a free event, and the place is
     * dropped. Done before anything is stored, handed on or drawn.
     */
    fun busyOnly(event: CalendarEvent): CalendarEvent =
        event.copy(title = if (event.busy) "Busy" else "Free", location = null)

    /**
     * Which calendars count as busy (20.2): your own Events always (each Event says for itself), a
     * connected calendar while it is shown and counts as busy.
     */
    fun calendarCounts(counting: Set<String>): (String) -> Boolean =
        { id -> id == MY_EVENTS_CALENDAR_ID || id in counting }

    /**
     * Busy time from calendars on [day]: timed, busy events of calendars that count, clipped to the
     * day. All-day events are not busy; tentative ones are.
     */
    fun eventBusyOfDay(
        events: List<CalendarEvent>,
        day: LocalDate,
        zone: TimeZone,
        counts: (String) -> Boolean = { true },
    ): List<BusyRange> = timedEventsOn(events, day, zone).mapNotNull { placed ->
        if (!placed.event.busy || !counts(placed.event.calendarId)) null else BusyRange(placed.startMinute, placed.endMinute)
    }
}
