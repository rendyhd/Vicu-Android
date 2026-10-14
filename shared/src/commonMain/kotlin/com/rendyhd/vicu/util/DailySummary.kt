package com.rendyhd.vicu.util

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * The rules of the daily summary notification, pure so they run the same in tests and workers.
 *
 * What it counts matches the desktop app (cross-app semantics, section 2): overdue is before the
 * start of the local today, "due today" is the local today whatever the time of day, and the last
 * category is tomorrow only (not every future task). Boundaries are local midnights, so a day
 * that is 23 or 25 hours long at a daylight-saving change still counts exactly its own tasks.
 */
object DailySummary {

    /** What the summary reports; a category the user switched off is 0. */
    data class Counts(val overdue: Int, val dueToday: Int, val dueTomorrow: Int) {
        val total: Int get() = overdue + dueToday + dueTomorrow
    }

    /** The exclusive ends of the three windows, as stored due-date strings. */
    data class Boundaries(
        val startOfToday: String,
        val startOfTomorrow: String,
        val startOfDayAfterTomorrow: String,
    )

    fun boundaries(today: LocalDate, zone: TimeZone): Boundaries = Boundaries(
        startOfToday = DueDates.startOfDay(today, zone).toString(),
        startOfTomorrow = DueDates.startOfTomorrow(today, zone).toString(),
        startOfDayAfterTomorrow = DueDates.startOfDay(today.plus(2, DateTimeUnit.DAY), zone).toString(),
    )

    /** "2 overdue, 3 today, 1 tomorrow": only the categories that have tasks. */
    fun headline(counts: Counts): String = buildList {
        if (counts.overdue > 0) add("${counts.overdue} overdue")
        if (counts.dueToday > 0) add("${counts.dueToday} today")
        if (counts.dueTomorrow > 0) add("${counts.dueTomorrow} tomorrow")
    }.joinToString(", ")

    /**
     * The next local wall-clock [hour]:[minute] strictly after [after]. Computed from the calendar
     * date, never by adding 24 hours, so it stays at the same local time across daylight-saving
     * changes (a time that does not exist on the day clocks go forward moves to just after the
     * gap; one that exists twice fires once).
     */
    fun nextOccurrence(after: Instant, zone: TimeZone, hour: Int, minute: Int): Instant {
        val time = LocalTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        val today = after.toLocalDateTime(zone).date
        val candidate = LocalDateTime(today, time).toInstant(zone)
        if (candidate > after) return candidate
        return LocalDateTime(today.plus(1, DateTimeUnit.DAY), time).toInstant(zone)
    }
}
